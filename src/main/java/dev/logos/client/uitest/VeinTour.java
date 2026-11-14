package dev.logos.client.uitest;

import java.util.List;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ClientCharges;
import dev.logos.client.LiveVein;
import dev.logos.client.VeinVisual;
import dev.logos.client.VeinVisuals;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The showcase every element's vein preview runs (see VEIN_VFX.md), from real casts of that element: a vein passing
 * over the ground, and one running into a wall; a vein that lingers three seconds, as it thrusts up, once its head has
 * stopped, close up, and as it fades; a lingering ring seen from above; a lingering vein climbing a wall; and a
 * lingering vein by night. An element subclasses it, registers it as {@code logos_<element>_vein}, and may add its
 * own steps in {@link #extra}.
 */
public abstract class VeinTour implements UIScenario {
    protected abstract Element element();

    /** Client scenes only: the looks to draw instead of the element's own (see {@link VeinVisuals#substitute}); null for its own. */
    protected @Nullable BiFunction<Element, Boolean, VeinVisual> visuals() { return null; }

    /** Steps the element adds after the tour. */
    protected void extra(ScenarioBuilder s) {}

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "vein", element().name().toLowerCase()).guiScale(2).scenarioTimeoutMs(300_000);
    }

    /** What the veins do to what they strike, which makes them this element's: damage of it, unless the element says otherwise. */
    protected String onHit() {
        return "(damage target 2 " + element().name().toLowerCase() + ")";
    }

    /** A vein of {@code shape} with {@link #onHit}, so the vein is this element's. */
    private String vein(String shape) {
        return "(vein " + shape + " (on-hit " + onHit() + "))";
    }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.baseMaxMana = magic.mana = 100000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused: " + source);
    }

    /** A screenshot with the client's clock stopped ({@link ClientCharges#freeze}), so it is caught when the tour says. */
    protected static ScenarioBuilder shot(ScenarioBuilder s, String name) {
        return s.step("hold still for " + name, ctx -> ClientCharges.freeze(true)).screenshot(name)
                .step("go on after " + name, ctx -> ClientCharges.freeze(false));
    }

    private static boolean gone() {
        return VeinVisuals.all().isEmpty();
    }

    private static boolean stopped() {
        List<LiveVein> all = VeinVisuals.all();
        return !all.isEmpty() && all.stream().allMatch(LiveVein::ended);
    }

    private static boolean ago(int ticks) {
        return VeinVisuals.all().stream().anyMatch(v -> v.sinceEnd() >= ticks);
    }

    private String name(String what) {
        return element().name().toLowerCase() + "_vein_" + what;
    }

    /** The caster at the start of the line, looking down it (south, toward the wall at z 14). */
    private static ScenarioBuilder atStart(ScenarioBuilder s) {
        return s.teleportPlayer(.5, 101, .5, 0, 25).ticks(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 108, 6), 18)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 22), Blocks.SMOOTH_STONE.defaultBlockState())
                // A wall four high across the line at z 14.
                .fill(new BlockPos(-14, 101, 14), new BlockPos(14, 104, 14), Blocks.STONE_BRICKS.defaultBlockState())
                .step("start from nothing", ctx -> VeinVisuals.substitute(visuals()))
                .ticks(2);

        // Passing over the ground, seen from the side.
        atStart(s).server("a passing vein", sc -> cast(sc.player(), vein(":range 12")))
                .teleportPlayer(8, 104, 2, 62, 18)
                .waitUntil("its head is half way", ctx -> VeinVisuals.all().stream().anyMatch(v -> v.pos().z > 5));
        shot(s, name("pass"));
        s.waitUntil("it ran out", ctx -> gone());

        // Running into the wall.
        atStart(s).server("a vein into the wall", sc -> cast(sc.player(), vein(":range 20")))
                .teleportPlayer(6, 104, 8, 50, 18)
                // A passing head is dropped the tick it stops, so wait for it to reach the wall rather than to stop.
                .waitUntil("it reached the wall", ctx -> VeinVisuals.all().stream().anyMatch(v -> v.pos().z > 12.8)).ticks(2);
        shot(s, name("wall"));
        s.waitUntil("it was dropped", ctx -> gone());

        // Lingering three seconds.
        atStart(s).server("a lingering vein", sc -> cast(sc.player(), vein(":linger 3 :range 10")))
                .teleportPlayer(8, 104, 2, 62, 18)
                .waitUntil("its head is half way", ctx -> VeinVisuals.all().stream().anyMatch(v -> v.pos().z > 5));
        shot(s, name("linger_rise"));
        s.waitUntil("its head stopped", ctx -> stopped()).ticks(8);
        shot(s, name("linger"));
        s.teleportPlayer(2.6, 101.4, 12.2, 155, 12).ticks(2);
        shot(s, name("linger_close"));
        s.teleportPlayer(8, 104, 2, 62, 18).waitUntil("the first of it is fading", ctx -> ago(50));
        shot(s, name("linger_fade"));
        s.waitUntil("it faded", ctx -> gone());

        // A lingering ring, from above.
        atStart(s).server("a lingering ring", sc -> cast(sc.player(), vein(":motion ring :range 5 :linger 2")))
                .teleportPlayer(.5, 110, -7, 0, 52)
                .waitUntil("it spread", ctx -> stopped()).ticks(4);
        shot(s, name("ring"));
        s.waitUntil("the ring faded", ctx -> gone());

        // Climbing the wall, lingering.
        atStart(s).server("a lingering climb", sc -> cast(sc.player(), vein(":climb :linger 3 :range 22")))
                .teleportPlayer(7, 105, 9, 55, 15)
                .waitUntil("it climbs the wall's face", ctx -> VeinVisuals.all().stream().anyMatch(v -> !v.ended()
                        && v.face() == Direction.NORTH && v.pos().y > 103));
        shot(s, name("climb"));
        s.waitUntil("its head stopped", ctx -> stopped()).ticks(4);
        shot(s, name("climb_held"));
        s.waitUntil("the climb faded", ctx -> gone());

        // By night.
        s.runCommand("time set midnight");
        atStart(s).server("a lingering vein by night", sc -> cast(sc.player(), vein(":linger 3 :range 10")))
                .teleportPlayer(8, 104, 2, 62, 18)
                .waitUntil("its head stopped", ctx -> stopped()).ticks(6);
        shot(s, name("night"));
        s.waitUntil("it faded by night", ctx -> gone()).runCommand("time set noon");
        extra(s);
        s.teardown("restore", ctx -> {
            ClientCharges.freeze(false);
            VeinVisuals.substitute(null);
        });
    }
}
