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

/** The physical damage burst (shared core plus crossed slashes): its stages, night, size, side view and a real spell hit. */
@LDLRegisterClient(name = "logos_physical_damage", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PhysicalDamageScenario implements UIScenario {
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst").guiScale(2); }

    private static void burst(float x, float y, float z, Vec3 from, float scale) {
        Vec3 dir = new Vec3(x, y, z).subtract(from).normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.PHYSICAL.ordinal(),
                (byte) Vfx.Burst.DAMAGE.ordinal(), x, y, z, (float) dir.x, (float) dir.y, (float) dir.z, scale, 0, -1, 0, -1));
    }

    /** A close hit straight ahead and a farther one to the side, both coming from the player. */
    private static void pair(float scale) {
        burst(-.6f, 102.2f, 2.5f, EYE, scale);
        burst(2.6f, 102.2f, 6, EYE, scale);
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
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // Fresh hits for every age sample keep screenshot readback time out of the next sample.
        s.step("contact", ctx -> pair(1)).ticks(1).screenshot("physical_contact").ticks(24)
                .step("first slash peak", ctx -> pair(1)).ticks(2).screenshot("physical_peak").ticks(24)
                .step("crossing slash", ctx -> pair(1)).ticks(4).screenshot("physical_cross").ticks(24)
                .step("fade", ctx -> pair(1)).ticks(8).screenshot("physical_fade")
                .ticks(20).screenshot("physical_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> pair(1)).ticks(3).screenshot("physical_night").ticks(24)
                .step("large targets", ctx -> pair(2)).ticks(3).screenshot("physical_large").ticks(24);
        s.runCommand("time set noon").teleportPlayer(-5.5, 101, 5, -90, 0).ticks(5)
                .step("side view of a hit along +Z", ctx -> burst(.5f, 102.2f, 5, new Vec3(.5, 102.2, 1), 1))
                .ticks(3).screenshot("physical_side").ticks(24)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("real physical damage on a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    PlayerMagic magic = PlayerMagic.of(sc.player());
                    magic.maxMana = magic.mana = 10000;
                    if (Casting.cast(sc.player(), Casting.compile("(damage (nearest :radius 12) 1 physical)"), null, null) == null)
                        throw new IllegalStateException("real test spell was refused");
                }).ticks(3).screenshot("physical_real_damage").ticks(20)
                .check("damage takes the physical damage burst", ctx -> cache().containsKey("effect_burst/PHYSICAL/" + Vfx.Burst.DAMAGE.ordinal()))
                .server("damage still applies", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("spell damage did not apply");
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
