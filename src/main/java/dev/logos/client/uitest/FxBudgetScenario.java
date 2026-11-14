package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosConfig;
import dev.logos.client.FieldVisuals;
import dev.logos.client.LogosFx;
import dev.logos.client.VeinVisuals;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.net.FxPayload.Kind;
import dev.logos.runtime.AreaWeather;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Far more spell FX than a client will play, through the same entry the packets use: lasting effects stop at their caps, one-shots
 * and particles at the tick's budget, and ending what was never drawn is safe. Then real casts over the connection: a batch still
 * draws, and a following zone still moves on the client while one packet tells only of it.
 */
@LDLRegisterClient(name = "logos_fx_budget", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FxBudgetScenario implements UIScenario {
    private static final int FIRST_ID = 800000, MANY = 700;
    private static int effectsSpent, particlesSpent, trailsHeld, beamsHeld, veinHeadsHeld;
    private static long fieldsHeld;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx").guiScale(2); }

    private static void fx(Kind kind, Element e, int variant, Vec3 at, Vec3 aux, float size, int id, int look, float spin, int particle) {
        LogosFx.handle(new FxPayload((byte) kind.ordinal(), (byte) e.ordinal(), (byte) variant, (float) at.x, (float) at.y, (float) at.z,
                (float) aux.x, (float) aux.y, (float) aux.z, size, id, look, spin, particle));
    }

    /** Spot {@code i} of a wide patch in front of the player. */
    private static Vec3 spot(int i) {
        return new Vec3(i % 30 - 15 + .5, 102 + (i / 30) % 6, 10 + (i / 180) * 2);
    }

    private static int budgetSpent(String counter) {
        try {
            var field = LogosFx.class.getDeclaredField("BUDGET");
            field.setAccessible(true);
            Object budget = field.get(null);
            var spent = budget.getClass().getDeclaredField(counter);
            spent.setAccessible(true);
            return spent.getInt(budget);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void holdEverything() {
        Vec3 ahead = new Vec3(0, 0, 1);
        for (int i = 0; i < MANY; i++) {
            Vec3 at = spot(i);
            fx(Kind.TRAIL, Element.values()[i % 7], 0, at, ahead, .3f, FIRST_ID + i, -1, 0, -1);
            fx(Kind.BEAM_HOLD, Element.values()[i % 7], 0, at, at.add(0, 0, 6), .3f, FIRST_ID + 1000 + i, -1, 0, -1);
            fx(Kind.FIELD_HOLD, Element.values()[i % 7], 0, at, new Vec3(1, 200, 20), 1.5f, FIRST_ID + 2000 + i, -1, 0, -1);
            fx(Kind.VEIN_HOLD, Element.values()[i % 7], 0, at, ahead, 1, FIRST_ID + 3000 + i, 1, 1, 0);
        }
    }

    private static void endEverything() {
        for (int i = 0; i < MANY; i++) {
            Vec3 at = spot(i);
            fx(Kind.TRAIL_END, Element.FIRE, 0, at, Vec3.ZERO, 1, FIRST_ID + i, -1, 0, -1);
            fx(Kind.BEAM_END, Element.FIRE, 0, at, Vec3.ZERO, 1, FIRST_ID + 1000 + i, -1, 0, -1);
            fx(Kind.FIELD_END, Element.FIRE, 0, at, Vec3.ZERO, 1, FIRST_ID + 2000 + i, -1, 0, -1);
            fx(Kind.VEIN_END, Element.FIRE, 0, at, Vec3.ZERO, 0, FIRST_ID + 3000 + i, -1, 0, -1);
        }
    }

    private static long liveFields() {
        return FieldVisuals.all().stream().filter(f -> f.phase() != dev.logos.client.LiveField.Phase.ENDED).count();
    }

    private static void cast(ServerPlayer player, String source, Vec3 here) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.baseMaxMana = magic.mana = 100000;
        var inst = Casting.cast(player, Casting.compile(source), null, here);
        if (inst == null || inst.dead) throw new IllegalStateException("real test spell failed: " + (inst == null ? "refused" : inst.endReason()));
    }

    /** The client's zone standing about where the player is, or null. */
    private static AreaWeather.Zone zoneOnPlayer() {
        var mc = Minecraft.getInstance();
        for (AreaWeather.Zone z : AreaWeather.known(mc.level)) {
            if (Math.abs(z.x() - mc.player.getX()) < 1.5 && Math.abs(z.z() - mc.player.getZ()) < 1.5) return z;
        }
        return null;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 20), 40)
                .fill(new BlockPos(-30, 100, -10), new BlockPos(30, 100, 50), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -6, 0, 5).ticks(10);

        s.step("hold far too many of everything", ctx -> {
            FieldVisuals.clear();
            VeinVisuals.clear();
            LogosFx.clearRays();
            holdEverything();
            // Read at once: the first frames of this many take long enough for what is not reminded to go stale.
            trailsHeld = LogosFx.drawnTrails().size();
            beamsHeld = LogosFx.drawnBeamLengths().size();
            fieldsHeld = liveFields();
            veinHeadsHeld = VeinVisuals.all().size();
        }).ticks(1).screenshot("fx_budget_lasting")
                .check("projectiles stop at the cap", ctx -> trailsHeld == LogosConfig.FX_MAX_TRAILS.get())
                .check("beams stop at the cap", ctx -> beamsHeld == LogosConfig.FX_MAX_BEAMS.get())
                .check("fields stop at the cap", ctx -> fieldsHeld == LogosConfig.FX_MAX_FIELDS.get())
                .check("vein heads stop at the cap", ctx -> veinHeadsHeld == LogosConfig.FX_MAX_VEIN_HEADS.get())
                .step("end them, drawn or not", ctx -> endEverything()).ticks(2)
                .check("no projectile is left", ctx -> LogosFx.drawnTrails().isEmpty())
                .check("no beam is left", ctx -> LogosFx.drawnBeamLengths().isEmpty())
                .check("no field is still up", ctx -> liveFields() == 0)
                .check("played-out fields stay under the cap", ctx -> FieldVisuals.all().size() <= LogosConfig.FX_MAX_FIELDS.get())
                .check("played-out vein heads stay under the cap", ctx -> VeinVisuals.all().size() <= LogosConfig.FX_MAX_VEIN_HEADS.get())
                .step("clear", ctx -> {
                    FieldVisuals.clear();
                    VeinVisuals.clear();
                    LogosFx.clearRays();
                }).ticks(1);

        s.step("many rays at once", ctx -> {
            for (int i = 0; i < MANY; i++) {
                Vec3 at = spot(i);
                fx(Kind.BEAM, Element.values()[i % 7], Vfx.RayEnd.OPEN.ordinal(), at, at.add(0, 0, 5), 1, 0, -1, 0, -1);
            }
        }).ticks(1).screenshot("fx_budget_rays")
                .check("rays stop at the cap", ctx -> LogosFx.liveRays() <= LogosConfig.FX_MAX_RAYS.get() && LogosFx.liveRays() > 0)
                .step("clear", ctx -> LogosFx.clearRays()).ticks(1);

        s.step("a burst on a hundred targets, and far more particles", ctx -> {
            for (int i = 0; i < MANY; i++) {
                Vec3 at = spot(i);
                fx(Kind.BURST, Element.values()[i % 7], Vfx.Burst.DAMAGE.ordinal(), at, Vec3.ZERO, 1, 0, -1, 0, -1);
                fx(Kind.PHANTASM, Element.ARCANE, 64, at, Vec3.ZERO, 1, 0, -1, 0, BuiltInRegistries.PARTICLE_TYPE.getId(ParticleTypes.END_ROD));
            }
            effectsSpent = budgetSpent("effects");
            particlesSpent = budgetSpent("particles");
        }).ticks(2).screenshot("fx_budget_bursts")
                .check("bursts stop at the tick's budget", ctx -> effectsSpent == LogosConfig.FX_EFFECTS_PER_TICK.get())
                .check("particles stop at the tick's budget", ctx -> particlesSpent == LogosConfig.FX_PARTICLES_PER_TICK.get())
                .step("the budget comes back", ctx -> {
                    effectsSpent = budgetSpent("effects");
                    fx(Kind.BURST, Element.FIRE, Vfx.Burst.DAMAGE.ordinal(), spot(0), Vec3.ZERO, 1, 0, -1, 0, -1);
                })
                .check("the next tick starts afresh", ctx -> effectsSpent < LogosConfig.FX_EFFECTS_PER_TICK.get()).ticks(40);

        s.server("a real projectile, over the connection", sc -> cast(sc.player(), "(spell :mana 50 (projectile :speed 0.2 :life 3))", null)).ticks(5)
                .screenshot("fx_budget_real_projectile")
                .check("it arrives in its batch and is drawn", ctx -> LogosFx.drawnTrails().size() == 1)
                .ticks(60).check("its end arrives", ctx -> LogosFx.drawnTrails().isEmpty());

        s.server("a following zone over one standing still", sc -> {
            cast(sc.player(), "(spell :mana 5000 (weather clear :radius 3 :life 15))", new Vec3(.5, 101, 30.5));
            cast(sc.player(), "(spell :mana 5000 (weather clear :radius 3 :life 15 :follow))", null);
        }).ticks(5)
                .check("the client knows both", ctx -> AreaWeather.known(Minecraft.getInstance().level).size() >= 2)
                .check("one stands on the player", ctx -> zoneOnPlayer() != null)
                .teleportPlayer(10.5, 101, 0.5, 0, 5).ticks(10)
                .check("it followed the player on the client", ctx -> zoneOnPlayer() != null)
                .check("the other stayed", ctx -> AreaWeather.known(Minecraft.getInstance().level).stream().anyMatch(z -> Math.abs(z.z() - 30.5) < 0.1 && Math.abs(z.x() - .5) < 0.1));
    }
}
