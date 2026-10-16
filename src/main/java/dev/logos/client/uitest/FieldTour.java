package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientCharges;
import dev.logos.lang.Element;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The showcase every element's field preview runs (see FIELD_VFX.md), played by {@link FieldPreview} so
 * nothing is cast: a field round the caster seen from the front (its opening, a pulse, a body entering,
 * its clock half way and nearly round, its end); a wide field seen from its edge; a field that follows
 * the caster seen in first person from inside; and the first again by night. An element subclasses it,
 * registers it as {@code logos_<element>_field}, and may add its own steps in {@link #extra}.
 */
public abstract class FieldTour implements UIScenario {
    protected abstract Element element();

    /** Steps the element adds after the tour. */
    protected void extra(ScenarioBuilder s) {}

    @Override
    public void configure(ScenarioOptions options) {
        // The tour takes about a minute; an element may add more.
        options.tags("logos", "vfx", "field", element().name().toLowerCase()).guiScale(2).scenarioTimeoutMs(300_000);
    }

    protected static int player() {
        var p = Minecraft.getInstance().player;
        if (p == null) throw new IllegalStateException("no player");
        return p.getId();
    }

    protected static void camera(CameraType type) {
        Minecraft.getInstance().options.setCameraType(type);
    }

    /** Waits until {@link FieldPreview#elapsed()} reaches {@code tick}: on the field's own clock, whatever the steps between cost. */
    protected static ScenarioBuilder at(ScenarioBuilder s, int tick) {
        return s.waitUntil("field tick " + tick, ctx -> FieldPreview.elapsed() >= tick);
    }

    /** A screenshot with the clock stopped ({@link ClientCharges#freeze}), so it is caught when the tour says. */
    protected static ScenarioBuilder shot(ScenarioBuilder s, String name) {
        return s.step("hold still for " + name, ctx -> ClientCharges.freeze(true)).screenshot(name)
                .step("go on after " + name, ctx -> ClientCharges.freeze(false));
    }

    private String name(String what) {
        return element().name().toLowerCase() + "_" + what;
    }

    @Override
    public void define(ScenarioBuilder s) {
        Element e = element();
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 110, 0), 20)
                .fill(new BlockPos(-18, 100, -18), new BlockPos(18, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 20)
                .step("show the HUD, look from the front", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    camera(CameraType.THIRD_PERSON_FRONT);
                })
                .ticks(10);

        // Round the caster: 8 seconds, a pulse every second, someone entering at 3 s.
        s.step("a field round the caster", ctx -> FieldPreview.start(e, new Vec3(.5, 102, .5), 4, 160, 20, -1));
        shot(at(s, 2), name("open_early"));
        shot(at(s, 6), name("open_late"));
        shot(at(s, 18), name("steady"));
        shot(at(s, 21), name("pulse"));
        s.step("someone enters", ctx -> FieldPreview.enter(new Vec3(3, 101.9, 1.5), -1));
        shot(at(s, 64), name("enter"));
        shot(at(s, 80), name("half"));
        shot(at(s, 150), name("nearly_round"));
        shot(at(s, 164), name("ending"));
        shot(at(s, 172), name("ended"));
        at(s, 220).check("the field is over", ctx -> !FieldPreview.busy());

        // A wide one, seen from its edge.
        s.step("from the edge", ctx -> camera(CameraType.FIRST_PERSON)).teleportPlayer(.5, 107, -12, 0, 35).ticks(5)
                .step("a wide field", ctx -> FieldPreview.start(e, new Vec3(.5, 101, 2), 9, 120, 30, -1));
        shot(at(s, 8), name("wide_open"));
        shot(at(s, 40), name("wide_steady"));
        shot(at(s, 62), name("wide_pulse"));
        at(s, 110);
        at(s, 170).check("the wide field is over", ctx -> !FieldPreview.busy());

        // One that follows the caster, from inside in first person.
        s.teleportPlayer(.5, 101, .5, 0, 15).ticks(5)
                .step("a field that follows", ctx -> FieldPreview.start(e, new Vec3(.5, 102, .5), 5, 100, 20, player()));
        shot(at(s, 30), name("fp_inside"));
        shot(at(s, 41), name("fp_pulse"));
        at(s, 95);
        at(s, 150).check("the following field is over", ctx -> !FieldPreview.busy());

        // By night.
        s.step("front again", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).runCommand("time set midnight").ticks(5)
                .step("a field by night", ctx -> FieldPreview.start(e, new Vec3(.5, 102, .5), 5, 100, 20, -1));
        shot(at(s, 30), name("night"));
        shot(at(s, 41), name("night_pulse"));
        at(s, 95);
        at(s, 150).check("the night field is over", ctx -> !FieldPreview.busy());
        s.runCommand("time set noon");
        extra(s);
        s.teardown("restore", ctx -> {
            ClientCharges.freeze(false);
            FieldPreview.abandon();
            camera(CameraType.FIRST_PERSON);
        });
    }
}
