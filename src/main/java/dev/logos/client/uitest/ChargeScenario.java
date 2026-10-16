package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ChargeEffects;
import dev.logos.client.ChargeVisual;
import dev.logos.client.ChargeVisuals;
import dev.logos.client.LiveCharge;
import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * The shared charge entry (CHARGE_VFX.md): with a recording visual standing in for every element's, a
 * charge goes through charging, held and released (and plays out until it says it is done), a dropped
 * one fizzles, one whose updates stop is dropped, and a release never seen charging still plays. Then,
 * with the elements' own visuals back, a legacy element is left to {@link ChargeEffects}.
 */
@LDLRegisterClient(name = "logos_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ChargeScenario implements UIScenario {
    /** Every tick and end the recording visuals saw, oldest first: "tier:phase", "+" when the phase was entered, "end". */
    private static final List<String> SEEN = new ArrayList<>();
    /** Ticks a released or fizzled recording keeps playing out. */
    private static final int LINGER = 10;

    private static final class Recording implements ChargeVisual {
        @Override
        public boolean legacy() { return false; }

        @Override
        public boolean tick(Level level, LiveCharge c) {
            SEEN.add(c.tier() + ":" + c.phase() + (c.entered() ? "+" : ""));
            return !c.phase().over() || c.phaseAge(0) < LINGER;
        }

        @Override
        public void end() { SEEN.add("end"); }
    }

    private static int player() {
        var p = Minecraft.getInstance().player;
        if (p == null) throw new IllegalStateException("no player");
        return p.getId();
    }

    private static boolean saw(String entry) {
        return SEEN.contains(entry);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) throw new AssertionError(what + "; seen " + SEEN);
    }

    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "charge").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon")
                .clearArea(new BlockPos(0, 106, 0), 8)
                .fill(new BlockPos(-6, 100, -6), new BlockPos(6, 100, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, .5, 0, 10)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.MASTER_WAND.get())))
                .step("record every element's charge", ctx -> {
                    ChargeVisuals.substitute(e -> new Recording());
                    SEEN.clear();
                })
                .ticks(2);

        // Charging, held, released, and played out.
        s.step("a grand charge", ctx -> ChargePreview.release(player(), Element.FIRE, 2, 20, 10))
                .ticks(10)
                .check("it is charging", ctx -> {
                    LiveCharge c = ChargeVisuals.of(player());
                    expect(c != null && c.phase() == LiveCharge.Phase.CHARGING && c.tier() == 2 && c.element() == Element.FIRE,
                            "expected a grand fire charge under way");
                    float p = c.progress(0);
                    expect(p > .2f && p < .8f, "expected it part full, got " + p);
                    return true;
                })
                .ticks(15)
                .check("it is held once full", ctx -> {
                    LiveCharge c = ChargeVisuals.of(player());
                    expect(c != null && c.phase() == LiveCharge.Phase.HELD && c.progress(0) == 1, "expected it held");
                    return true;
                })
                .ticks(8)
                .check("it is released, then played out and dropped", ctx -> {
                    expect(SEEN.indexOf("2:CHARGING+") == 0, "the first tick enters charging");
                    expect(saw("2:HELD+") && saw("2:RELEASED+"), "held and released are each entered");
                    expect(SEEN.stream().filter(x -> x.endsWith("+")).count() == 3, "each phase is entered once");
                    return ChargeVisuals.of(player()) != null;
                })
                .ticks(LINGER + 3)
                .check("nothing is kept once it has played out", ctx -> {
                    expect(ChargeVisuals.count() == 0 && SEEN.getLast().equals("end"), "expected it ended and dropped");
                    expect(ChargeEffects.last() != null && ChargeEffects.last().released(), "the release is still recorded");
                    return true;
                });

        // Dropped part way.
        s.step("an elaborate charge dropped part way", ctx -> {
                    SEEN.clear();
                    ChargePreview.fizzle(player(), Element.FROST, 1, 20, .5f);
                })
                .ticks(13)
                .check("it fizzled", ctx -> {
                    LiveCharge c = ChargeVisuals.of(player());
                    expect(c != null && c.phase() == LiveCharge.Phase.FIZZLED, "expected it fizzled");
                    expect(c.progress(0) >= .3f && c.progress(0) <= .6f, "it keeps how full it was: " + c.progress(0));
                    expect(!saw("1:HELD+") && saw("1:FIZZLED+"), "never held, fizzled");
                    return true;
                })
                .ticks(LINGER + 3)
                .check("and is gone", ctx -> ChargeVisuals.count() == 0);

        // Updates stop (the caster went out of range): dropped with the synced charge, without a release.
        s.step("a charge whose updates stop", ctx -> {
                    SEEN.clear();
                    ChargePreview.release(player(), Element.VOID, 0, 40, 0);
                })
                .ticks(6)
                .step("stop sending", ctx -> ChargePreview.abandon())
                .ticks(60)
                .check("it is dropped", ctx -> {
                    expect(ChargeVisuals.count() == 0 && SEEN.getLast().equals("end"), "expected the stale charge dropped");
                    expect(!saw("0:RELEASED+") && !saw("0:FIZZLED+"), "neither released nor fizzled");
                    return true;
                });

        // A release heard without its charge (it came into range just as it went) still plays.
        s.step("a release never seen charging", ctx -> {
                    SEEN.clear();
                    dev.logos.client.ClientCharges.receive(dev.logos.net.ChargePayload.released(player(), Element.LIFE.ordinal(), 3));
                })
                .ticks(2)
                .check("it plays its release", ctx -> {
                    expect(SEEN.indexOf("3:RELEASED+") == 0, "the first tick enters released");
                    return true;
                })
                .ticks(LINGER + 3)
                .check("and is gone", ctx -> ChargeVisuals.count() == 0);

        // The elements' own visuals again: a legacy element is not kept here.
        s.step("the elements' own visuals", ctx -> ChargeVisuals.substitute(null))
                .step("an arcane charge", ctx -> {
                    if (ChargeVisuals.drawsOwn(Element.ARCANE)) return;
                    ChargePreview.release(player(), Element.ARCANE, 1, 10, 4);
                })
                .ticks(20)
                .check("a legacy element is left to the particles", ctx -> ChargeVisuals.drawsOwn(Element.ARCANE)
                        || ChargeVisuals.count() == 0 && ChargeEffects.last() != null && ChargeEffects.last().element() == Element.ARCANE);
        s.teardown("restore", ctx -> {
            ChargePreview.abandon();
            ChargeVisuals.substitute(null);
        });
    }
}
