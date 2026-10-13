package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Nature bloom/leaves, repeated contacts and real server wall/entity/expiry packet routing. */
@LDLRegisterClient(name = "logos_life_projectile_impact", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeProjectileImpactScenario implements UIScenario {
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "life", "impact").guiScale(2); }

    private static void impact(float x, float y, float radius) {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.PROJECTILE_IMPACT.ordinal(), (byte)Element.LIFE.ordinal(),
                (byte)0, x, y, 4, 0, 0, -1, radius, 18500, -1, 0, -1));
    }

    private static Map<?, ?> cache() {
        try {
            var field = LogosFx.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<?, ?>)field.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void resetCache() {
        cache().remove("projectile_impact/LIFE/0");
        cache().remove("projectile_expiry/LIFE/0");
    }

    private static void cast(net.minecraft.server.level.ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        s.step("ordinary impact", ctx -> impact(.5f, 102.3f, .3f))
                .ticks(2).screenshot("life_impact_flash")
                .ticks(24)
                // Fresh bursts keep screenshot readback time out of the next age sample.
                .step("softening age sample", ctx -> impact(.5f, 102.3f, .3f))
                .ticks(4).screenshot("life_impact_softening").ticks(24)
                .step("fading age sample", ctx -> impact(.5f, 102.3f, .3f))
                .ticks(7).screenshot("life_impact_fading").ticks(24)
                .step("leaves age sample", ctx -> impact(.5f, 102.3f, .3f))
                .ticks(11).screenshot("life_impact_leaves")
                .ticks(24).screenshot("life_impact_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("larger night impact", ctx -> impact(.5f, 102.3f, .7f))
                .ticks(2).screenshot("life_impact_night")
                .ticks(24).step("night fading age sample", ctx -> impact(.5f, 102.3f, .7f))
                .ticks(7).screenshot("life_impact_night_fading").ticks(24);
        s.runCommand("time set noon").ticks(3)
                .step("eight simultaneous impacts", ctx -> {
                    for (int i = 0; i < 8; i++) impact((i % 4 - 1.5f) * 1.2f, 102.3f + i / 4 * .9f, .3f);
                }).ticks(2).screenshot("life_impact_volley").ticks(22);

        s.step("reset wall routing observation", ctx -> resetCache())
                .fill(new BlockPos(0, 101, 4), new BlockPos(0, 104, 4), Blocks.STONE.defaultBlockState())
                .server("real life projectile into wall", sc -> cast(sc.player(),
                        "(projectile :speed 0.4 :life 2 (on-hit (heal target 6)))"))
                .ticks(15).screenshot("life_impact_real_wall").ticks(12)
                .check("wall collision triggers impact without expiry flash", ctx ->
                        cache().containsKey("projectile_impact/LIFE/0") && !cache().containsKey("projectile_expiry/LIFE/0"));

        s.fill(new BlockPos(0, 101, 4), new BlockPos(0, 104, 4), Blocks.AIR.defaultBlockState())
                .step("reset expiry routing observation", ctx -> resetCache())
                .server("real short lived projectile", sc -> cast(sc.player(),
                        "(projectile :speed 0.1 :life 0.15 (on-hit (heal target 6)))"))
                .ticks(12).check("natural expiry does not trigger impact", ctx ->
                        !cache().containsKey("projectile_impact/LIFE/0") && cache().containsKey("projectile_expiry/LIFE/0"))
                .ticks(14);

        s.step("reset entity routing observation", ctx -> resetCache())
                .server("real projectile into cow", sc -> {
                    target = EntityType.COW.create(sc.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 2);
                    target.setNoAi(true);
                    target.setHealth(target.getMaxHealth() - 6);
                    sc.level().addFreshEntity(target);
                    cast(sc.player(), "(projectile :speed 0.4 :life 2 (on-hit (heal target 6)))");
                }).ticks(10).screenshot("life_impact_real_entity").ticks(14)
                .check("entity collision triggers impact without expiry flash", ctx ->
                        cache().containsKey("projectile_impact/LIFE/0") && !cache().containsKey("projectile_expiry/LIFE/0"))
                .server("healing still applies", sc -> {
                    if (target == null || target.getHealth() < target.getMaxHealth() - .1f)
                        throw new IllegalStateException("projectile healing did not apply");
                    target.discard();
                    target = null;
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
