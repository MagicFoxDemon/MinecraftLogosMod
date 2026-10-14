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
import net.minecraft.world.phys.Vec3;

/** The fire damage burst up close: its stages, night, a large target, a side view and a real fire spell on a cow. */
@LDLRegisterClient(name = "logos_fire_damage", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FireDamageScenario implements UIScenario {
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "fire").guiScale(2); }

    private static void burst(float x, float y, float z, Vec3 from, float scale) {
        Vec3 dir = new Vec3(x, y, z).subtract(from).normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.FIRE.ordinal(), (byte) Vfx.Burst.DAMAGE.ordinal(),
                x, y, z, (float) dir.x, (float) dir.y, (float) dir.z, scale, 0, -1, 0, -1));
    }

    /** One hit straight ahead and one at a slant, so both the flame spray and its direction show. */
    private static void pair(float scale) {
        burst(-.7f, 102.2f, 3, EYE, scale);
        burst(1.9f, 102f, 3.4f, new Vec3(-3, 102.2, 1), scale);
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
                .clearArea(new BlockPos(0, 104, 3), 10)
                .fill(new BlockPos(-10, 100, -6), new BlockPos(10, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // A fresh pair for every age sample keeps screenshot readback time out of the next sample.
        s.step("contact", ctx -> pair(1)).ticks(1).screenshot("fire_contact").ticks(28)
                .step("peak", ctx -> pair(1)).ticks(3).screenshot("fire_peak").ticks(28)
                .step("flames out", ctx -> pair(1)).ticks(6).screenshot("fire_flames").ticks(28)
                .step("fade", ctx -> pair(1)).ticks(12).screenshot("fire_fade").ticks(28)
                .step("smoke tail", ctx -> pair(1)).ticks(20).screenshot("fire_smoke")
                .ticks(25).screenshot("fire_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> pair(1)).ticks(3).screenshot("fire_night").ticks(28)
                .step("night smoke", ctx -> pair(1)).ticks(14).screenshot("fire_night_smoke").ticks(28)
                .runCommand("time set noon").ticks(3)
                .step("large target", ctx -> burst(.5f, 102.4f, 4.5f, EYE, 2)).ticks(3).screenshot("fire_large").ticks(28);
        s.teleportPlayer(-4.5, 101, 3, -90, 0).ticks(5)
                .step("side view of a hit along +Z", ctx -> burst(.5f, 102.2f, 3, new Vec3(.5, 102.2, -2), 1))
                .ticks(3).screenshot("fire_side").ticks(28)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("real fire damage on a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    PlayerMagic magic = PlayerMagic.of(sc.player());
                    magic.maxMana = magic.mana = 10000;
                    if (Casting.cast(sc.player(), Casting.compile("(damage (nearest :radius 12) 1 fire)"), null, null) == null)
                        throw new IllegalStateException("real test spell was refused");
                }).ticks(3).screenshot("fire_real_damage").ticks(25)
                .check("fire damage takes the fire damage burst", ctx -> cache().containsKey("effect_burst/FIRE/" + Vfx.Burst.DAMAGE.ordinal()))
                .server("damage still applies", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("spell damage did not apply");
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
