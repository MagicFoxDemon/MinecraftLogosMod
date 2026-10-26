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
import dev.logos.runtime.Effects;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The life damage burst (shared core plus wilting bloom and falling leaves) at each stage, and from the creature damage entry. */
@LDLRegisterClient(name = "logos_life_damage", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeDamageScenario implements UIScenario {
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "life").guiScale(2); }

    private static void burst(float x, float y, float z, Vec3 from, float scale) {
        Vec3 dir = new Vec3(x, y, z).subtract(from).normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.LIFE.ordinal(),
                (byte) Vfx.Burst.DAMAGE.ordinal(), x, y, z, (float) dir.x, (float) dir.y, (float) dir.z, scale, 0, -1, 0, -1));
    }

    /** One hit straight ahead, close enough to read the accents. */
    private static void hit() { burst(.5f, 102.4f, 2.5f, EYE, 1); }

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
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // A fresh hit for every age sample keeps screenshot readback time out of the next sample.
        s.step("contact", ctx -> hit()).ticks(1).screenshot("life_contact").ticks(24)
                .step("peak sample", ctx -> hit()).ticks(3).screenshot("life_peak").ticks(24)
                .step("late sample", ctx -> hit()).ticks(5).screenshot("life_late").ticks(24)
                .step("fade sample", ctx -> hit()).ticks(9).screenshot("life_fade")
                .ticks(20).screenshot("life_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> hit()).ticks(3).screenshot("life_night").ticks(24)
                .step("several targets, one large", ctx -> {
                    burst(-2.5f, 102.2f, 5, EYE, 1);
                    burst(.5f, 102.6f, 6, EYE, 2);
                    burst(3.5f, 102.2f, 5, EYE, 1);
                }).ticks(3).screenshot("life_several").ticks(24);
        s.runCommand("time set noon").teleportPlayer(-5.5, 101, 5, -90, 0).ticks(5)
                .step("side view of a hit along +Z", ctx -> burst(.5f, 102.2f, 5, new Vec3(.5, 102.2, -2), 1))
                .ticks(3).screenshot("life_side").ticks(24)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("real life damage on a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    // Players cannot write life; other mods' creatures deal it through this entry.
                    Effects.damage(sc.player(), target, 1, Element.LIFE);
                }).ticks(3).screenshot("life_real_damage").ticks(20)
                .check("creature life damage takes the life damage burst", ctx -> cache().containsKey("effect_burst/LIFE/" + Vfx.Burst.DAMAGE.ordinal()))
                .server("damage still applies", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("life damage did not apply");
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
