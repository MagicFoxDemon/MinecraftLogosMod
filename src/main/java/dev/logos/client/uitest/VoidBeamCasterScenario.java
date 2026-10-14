package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.client.CastPoints;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A lifeform uses its charge mount; explicit origins, custom trail particles and stale packets remain independent. */
@LDLRegisterClient(name = "logos_void_beam_casters", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidBeamCasterScenario implements UIScenario {
    private int lifeformId;
    @Override public void configure(ScenarioOptions o) { o.tags("logos", "vfx", "void", "beam").guiScale(2); }

    private static Map<?, ?> beams() {
        try { var f = LogosFx.class.getDeclaredField("BEAMS"); f.setAccessible(true); return (Map<?, ?>)f.get(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Vec3 drawnFrom(int id) {
        try {
            Object beam = beams().get(id);
            if (beam == null) throw new AssertionError("beam missing");
            var f = beam.getClass().getDeclaredField("voidBeam"); f.setAccessible(true);
            Object visual = f.get(beam);
            var p = visual.getClass().getDeclaredField("lastFrom"); p.setAccessible(true);
            return (Vec3)p.get(visual);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void hold(int id, int anchor, Vec3 from, Vec3 to, int particle) {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_HOLD.ordinal(), (byte)Element.VOID.ordinal(),
                (byte)0, (float)from.x, (float)from.y, (float)from.z, (float)to.x, (float)to.y, (float)to.z,
                .35f, id, anchor, 0, particle));
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 105, 3), 12)
                .fill(new BlockPos(-12, 100, -10), new BlockPos(12, 100, 15), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -3, 0, 10)
                .server("summon the beam's lifeform anchor", sc -> {
                    var m = PlayerMagic.of(sc.player()); m.maxMana = m.mana = 10000;
                    Casting.cast(sc.player(), Casting.compile("(spell :mana 6000 (summon mana_lifeform :level 3 :at (pos-of caster)))"), null, null);
                    var lf = (ManaLifeform)Familiars.latest(sc.player(), null);
                    if (lf == null) throw new AssertionError("no lifeform");
                    lf.snapTo(-3, 101.4, 4, -90, 0); lf.setNoAi(true); lifeformId = lf.getId();
                }).ticks(12)
                .step("lifeform holds a void beam", ctx -> {
                    var lf = Minecraft.getInstance().level.getEntity(lifeformId);
                    if (lf == null) throw new AssertionError("lifeform not tracked");
                    Vec3 from = lf.getEyePosition(0);
                    hold(27910, lifeformId, from, from.add(6, 0, 0), -1);
                }).ticks(5)
                .check("beam leaves the lifeform's charge point", ctx -> {
                    var lf = Minecraft.getInstance().level.getEntity(lifeformId);
                    Vec3 expected = CastPoints.drawn(lf);
                    // An off-screen lifeform has no freshly drawn bone and uses the shared body estimate.
                    if (expected == null) expected = lf.position().add(0, lf.getBbHeight() * .55, 0)
                            .add(lf.getViewVector(1).scale(lf.getBbWidth() * .5 + .25));
                    return drawnFrom(27910).distanceTo(expected) < .05;
                }).screenshot("void_beam_lifeform").ticks(20)
                .check("stale beam packets clean up persistent effects", ctx -> !beams().containsKey(27910))
                .step("explicit start and custom shedding particle", ctx -> hold(27911, -1,
                        new Vec3(-3, 102.4, 4), new Vec3(3, 102.4, 4), BuiltInRegistries.PARTICLE_TYPE.getId(ParticleTypes.END_ROD)))
                .ticks(5).check("explicit start stays where it was supplied", ctx ->
                        drawnFrom(27911).distanceTo(new Vec3(-3, 102.4, 4)) < 1e-4)
                .screenshot("void_beam_custom_trail").ticks(24)
                .server("remove preview lifeform", sc -> { var e = sc.level().getEntity(lifeformId); if (e != null) e.discard(); });
        s.teardown("remove lifeform", ctx -> {
            var level = Minecraft.getInstance().getSingleplayerServer();
            if (level != null) level.execute(() -> { var e = level.overworld().getEntity(lifeformId); if (e != null) e.discard(); });
        });
    }
}
