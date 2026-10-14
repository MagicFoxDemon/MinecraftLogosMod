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
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Heal bursts — leaves, light ring, trails and motes — through their stages, day and night, on a large body, and from a real heal. */
@LDLRegisterClient(name = "logos_heal_burst", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class HealBurstScenario implements UIScenario {
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "heal").guiScale(2); }

    /** A heal as the server sends it: no direction, at the middle of a body of the given scale. */
    private static void burst(float x, float y, float z, float scale) {
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.LIFE.ordinal(), (byte) Vfx.Burst.HEAL.ordinal(),
                x, y, z, 0, 0, 0, scale, 0, -1, 0, -1));
    }

    /** A cow-sized heal straight ahead, a player-sized one and a large one off to each side. */
    private static void heals() {
        burst(.5f, 101.7f, 3, 1);
        burst(-3.5f, 101.9f, 6, 1.2f);
        burst(4.5f, 102.4f, 6.5f, 2.2f);
    }

    private static Map<?, ?> cache() {
        try {
            var field = LogosFx.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.GRASS_BLOCK.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 10).ticks(10);
        // Fresh heals for every age sample keep screenshot readback time out of the next sample.
        s.step("contact", ctx -> heals()).ticks(1).screenshot("heal_contact").ticks(30)
                .step("bloom age sample", ctx -> heals()).ticks(4).screenshot("heal_bloom").ticks(30)
                .step("rise age sample", ctx -> heals()).ticks(9).screenshot("heal_rise").ticks(30)
                .step("fade age sample", ctx -> heals()).ticks(15).screenshot("heal_fade").ticks(30)
                .step("tail age sample", ctx -> heals()).ticks(21).screenshot("heal_tail")
                .ticks(20).screenshot("heal_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> heals()).ticks(5).screenshot("heal_night").ticks(30)
                .runCommand("time set noon").teleportPlayer(.5, 105, -3, 0, 32).ticks(5)
                .step("from above", ctx -> heals()).ticks(5).screenshot("heal_above").ticks(30)
                .teleportPlayer(.5, 101, -2, 0, 10).ticks(5);

        s.server("a hurt cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    target.setHealth(target.getMaxHealth() - 4);
                })
                .ticks(5)
                .step("reset routing observation", ctx -> cache().clear())
                .server("real heal on the cow", sc -> {
                    PlayerMagic magic = PlayerMagic.of(sc.player());
                    magic.maxMana = magic.mana = 10000;
                    if (Casting.cast(sc.player(), Casting.compile("(heal (nearest :radius 12) 2)"), null, null) == null)
                        throw new IllegalStateException("real test spell was refused");
                }).ticks(5).screenshot("heal_real").ticks(30)
                .check("heal takes the heal burst, not the generic one", ctx -> cache().containsKey("effect_burst/LIFE/" + Vfx.Burst.HEAL.ordinal())
                        && !cache().containsKey("burst/LIFE/0"))
                .server("the heal applies", sc -> {
                    if (target == null || target.getHealth() <= target.getMaxHealth() - 4)
                        throw new IllegalStateException("spell heal did not apply");
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
