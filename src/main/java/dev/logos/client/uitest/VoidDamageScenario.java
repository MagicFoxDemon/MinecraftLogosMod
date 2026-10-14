package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import dev.logos.client.LogosFx;
import dev.logos.client.VoidDamageMaterial;
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
import net.minecraft.world.phys.Vec3;

/** The void damage rift over the shared core: its stages, day and night, sizes, side view and real spell damage. */
@LDLRegisterClient(name = "logos_void_damage", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidDamageScenario implements UIScenario {
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "void").guiScale(2); }

    private static void burst(float x, float y, float z, Vec3 from, float scale) {
        Vec3 dir = new Vec3(x, y, z).subtract(from).normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.VOID.ordinal(), (byte) Vfx.Burst.DAMAGE.ordinal(),
                x, y, z, (float) dir.x, (float) dir.y, (float) dir.z, scale, 0, -1, 0, -1));
    }

    private static void hit() { burst(.5f, 102.2f, 3, EYE, 1); }

    private static Map<?, ?> cache() {
        try {
            var field = LogosFx.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void cast(net.minecraft.server.level.ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.check("rift material survives Photon cloning", ctx -> {
            var material = new VoidDamageMaterial();
            return material.copy() instanceof VoidDamageMaterial
                    && IMaterial.deserializeWrapper(material.serializeWrapper()) instanceof VoidDamageMaterial;
        });
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // Fresh hits for every age sample keep screenshot readback time out of the next sample.
        s.step("contact", ctx -> hit()).ticks(1).screenshot("void_damage_contact").ticks(24)
                .step("peak age sample", ctx -> hit()).ticks(3).screenshot("void_damage_peak").ticks(24)
                .step("collapse age sample", ctx -> hit()).ticks(6).screenshot("void_damage_collapse").ticks(24)
                .step("fade age sample", ctx -> hit()).ticks(10).screenshot("void_damage_fade")
                .ticks(20).screenshot("void_damage_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> hit()).ticks(3).screenshot("void_damage_night").ticks(24)
                .step("night collapse", ctx -> hit()).ticks(6).screenshot("void_damage_night_collapse").ticks(24)
                .step("small and large targets", ctx -> {
                    burst(-2.5f, 102.2f, 5, EYE, .6f);
                    burst(3.5f, 102.2f, 5, EYE, 2);
                }).ticks(3).screenshot("void_damage_sizes").ticks(24);
        s.runCommand("time set noon").teleportPlayer(-5.5, 101, 5, -90, 0).ticks(5)
                .step("side view of a hit along +Z", ctx -> burst(.5f, 102.2f, 5, new Vec3(.5, 102.2, 0), 1))
                .ticks(3).screenshot("void_damage_side").ticks(24)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("real void damage on a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    cast(sc.player(), "(damage (nearest :radius 12) 1 void)");
                }).ticks(3).screenshot("void_damage_real").ticks(20)
                .check("void damage takes the void damage burst", ctx -> cache().containsKey("effect_burst/VOID/" + Vfx.Burst.DAMAGE.ordinal())
                        && !cache().containsKey("burst/VOID/0"))
                .server("damage still applies", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("spell damage did not apply");
                    target.discard();
                    target = null;
                });
        s.teleportPlayer(.5, 101, 1.2, 0, 0).ticks(3)
                .step("close starfield inspection", ctx -> burst(.5f, 102.4f, 3, new Vec3(.5, 102.62, 1.2), 1))
                .ticks(3).screenshot("void_damage_close").ticks(24);
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
