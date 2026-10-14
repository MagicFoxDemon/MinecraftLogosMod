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

/** Frost damage bursts — the shared core with ice shards and frost mist — through their stages and from a real spell. */
@LDLRegisterClient(name = "logos_frost_damage", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FrostDamageScenario implements UIScenario {
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "frost").guiScale(2); }

    private static void burst(float x, float y, float z, Vec3 from, float scale) {
        Vec3 dir = new Vec3(x, y, z).subtract(from).normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.FROST.ordinal(), (byte) Vfx.Burst.DAMAGE.ordinal(),
                x, y, z, (float) dir.x, (float) dir.y, (float) dir.z, scale, 0, -1, 0, -1));
    }

    /** A close hit straight ahead, and a normal and a large one off to each side. */
    private static void hits() {
        burst(.5f, 102.2f, 3, EYE, 1);
        burst(-3.5f, 102.2f, 6, EYE, 1);
        burst(4.5f, 102.6f, 6, EYE, 2);
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
        s.step("contact", ctx -> hits()).ticks(1).screenshot("frost_contact").ticks(30)
                .step("peak age sample", ctx -> hits()).ticks(4).screenshot("frost_peak").ticks(30)
                .step("shards age sample", ctx -> hits()).ticks(8).screenshot("frost_shards").ticks(30)
                .step("fade age sample", ctx -> hits()).ticks(13).screenshot("frost_fade")
                .ticks(20).screenshot("frost_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> hits()).ticks(4).screenshot("frost_night").ticks(30)
                .runCommand("time set noon").teleportPlayer(-5.5, 101, 5, -90, 0).ticks(5)
                .step("side view of a hit along +Z", ctx -> burst(.5f, 102.2f, 5, new Vec3(.5, 102.2, -2), 1))
                .ticks(5).screenshot("frost_side").ticks(30)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("real frost damage on a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    PlayerMagic magic = PlayerMagic.of(sc.player());
                    magic.maxMana = magic.mana = 10000;
                    if (Casting.cast(sc.player(), Casting.compile("(damage (nearest :radius 12) 1 frost)"), null, null) == null)
                        throw new IllegalStateException("real test spell was refused");
                }).ticks(4).screenshot("frost_real_damage").ticks(30)
                .check("frost damage takes the frost damage burst", ctx -> cache().containsKey("effect_burst/FROST/" + Vfx.Burst.DAMAGE.ordinal()))
                .server("damage still applies", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("spell damage did not apply");
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
