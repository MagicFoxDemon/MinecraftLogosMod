package dev.logos.gametest;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Interp;
import dev.logos.runtime.Place;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** ⚖ Value runes, evaluated directly in a live spell's context. */
final class ValueGameTests {
    private ValueGameTests() {}

    /** Evaluates expressions as a spell cast by one mock player at relative (1.5, 2, 1.5) would. */
    private record Values(GameTestHelper h, ServerPlayer caster) {
        @SuppressWarnings("removal")
        Values(GameTestHelper h) {
            this(h, h.makeMockServerPlayerInLevel());
        }

        Object value(String expr) {
            var spell = Casting.compile("(let $v " + expr + " (do))");
            var inst = new SpellInstance(spell, caster, h.getLevel(), 1000, 1);
            Vec3 here = h.absoluteVec(new Vec3(1.5, 2, 1.5));
            return Interp.eval(spell.body().getFirst().arg(1), new Ctx(inst, h.getLevel(), here, new Vec3(0, 0, 1), null, Map.of(), 0, 0));
        }

        double num(String expr) {
            return (Double) value(expr);
        }
    }

    /** random keeps (random max), takes a lower bound, and in whole mode lands on every whole number, ends included. */
    static void random(GameTestHelper h) {
        Values v = new Values(h);
        for (int k = 0; k < 200; k++) {
            double plain = v.num("(random 5)");
            h.assertTrue(plain >= 0 && plain < 5, "(random 5) out of [0, 5): " + plain);
            double ranged = v.num("(random 3 :min -2)");
            h.assertTrue(ranged >= -2 && ranged < 3, "(random 3 :min -2) out of [-2, 3): " + ranged);
            double swapped = v.num("(random 1 :min 4)");
            h.assertTrue(swapped >= 1 && swapped <= 4, "swapped bounds out of [1, 4]: " + swapped);
        }
        Set<Double> faces = new HashSet<>();
        for (int k = 0; k < 400; k++) {
            double d = v.num("(random 6 :min 1 :whole)");
            h.assertTrue(d == Math.rint(d) && d >= 1 && d <= 6, "a die rolled " + d);
            faces.add(d);
        }
        h.assertTrue(faces.size() == 6, "every face from 1 to 6 should come up in 400 rolls, saw " + faces);
        h.assertTrue(v.num("(random 0.8 :min 0.2 :whole)") == 1, "no whole number between 0.2 and 0.8: the nearest is 1");
        h.succeed();
    }

    /** mix and mix-pos give a at 0, b at 1, and keep going along the same line beyond either end. */
    static void mix(GameTestHelper h) {
        Values v = new Values(h);
        h.assertTrue(v.num("(mix 2 10 0)") == 2 && v.num("(mix 2 10 1)") == 10 && v.num("(mix 2 10 0.25)") == 4, "mix inside [0, 1]");
        h.assertTrue(v.num("(mix 0 10 1.5)") == 15 && v.num("(mix 0 10 -0.5)") == -5, "mix past either end stays on the line");
        Vec3 here = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        Vec3 mid = ((Place) v.value("(mix-pos here (above here 8) 0.5)")).pos();
        h.assertTrue(mid.distanceTo(here.add(0, 4, 0)) < 1e-9, "mix-pos 0.5 is the midpoint, got " + mid);
        Vec3 past = ((Place) v.value("(mix-pos here (above here 8) -1)")).pos();
        h.assertTrue(past.distanceTo(here.add(0, -8, 0)) < 1e-9, "mix-pos -1 lies beyond a, got " + past);
        h.succeed();
    }

    /** round rounds half away from zero, or up, or down; clamp holds a number within its bounds, 0~1 by default. */
    static void roundClamp(GameTestHelper h) {
        Values v = new Values(h);
        h.assertTrue(v.num("(round 2.5)") == 3 && v.num("(round -2.5)") == -3 && v.num("(round 2.4)") == 2, "round to nearest, half away from zero");
        h.assertTrue(v.num("(round 2.1 :mode up)") == 3 && v.num("(round -2.1 :mode up)") == -2, "round up");
        h.assertTrue(v.num("(round 2.9 :mode down)") == 2 && v.num("(round -2.1 :mode down)") == -3, "round down");
        h.assertTrue(v.num("(clamp 1.7)") == 1 && v.num("(clamp -3)") == 0 && v.num("(clamp 0.4)") == 0.4, "clamp to 0~1 by default");
        h.assertTrue(v.num("(clamp 12 :min 2 :max 10)") == 10 && v.num("(clamp 5 :min 10 :max 2)") == 5, "clamp to given bounds, swapped when reversed");
        h.succeed();
    }

    /** Every waveform starts at 0 rising and peaks a quarter cycle in (square holds +amp, then -amp); period, amp and phase scale it. */
    static void wave(GameTestHelper h) {
        Values v = new Values(h);
        String[][] cases = {
                // shape, value at 0, 0.25, 0.5, 0.75 of a cycle
                {"sine", "0", "1", "0", "-1"}, {"triangle", "0", "1", "0", "-1"},
                {"saw", "0", "0.5", "-1", "-0.5"}, {"square", "1", "1", "-1", "-1"}};
        for (String[] c : cases) {
            for (int k = 0; k < 4; k++) {
                double got = v.num("(wave " + (k * 0.25) + " :shape " + c[0] + ")");
                h.assertTrue(Math.abs(got - Double.parseDouble(c[k + 1])) < 1e-9, c[0] + " at " + (k * 0.25) + " should be " + c[k + 1] + ", got " + got);
            }
        }
        h.assertTrue(Math.abs(v.num("(wave 5 :period 20 :amp 3)") - 3) < 1e-9, "period 20, amp 3: a quarter period in peaks at 3");
        h.assertTrue(Math.abs(v.num("(wave 0 :phase 0.25)") - 1) < 1e-9, "phase 0.25 starts at the peak");
        h.assertTrue(Math.abs(v.num("(wave -0.25 :shape triangle)") + 1) < 1e-9, "negative x keeps cycling");
        h.assertTrue(v.num("(wave 3 :period 0)") == 0, "period 0 gives 0");
        h.succeed();
    }

    /** choose picks by index from 0, held to the nearest end or wrapped round, and only evaluates what it picks. */
    static void choose(GameTestHelper h) {
        Values v = new Values(h);
        h.assertTrue(v.num("(choose 0 10 20 30)") == 10 && v.num("(choose 1.6 10 20 30)") == 30, "index from 0, rounded");
        h.assertTrue(v.num("(choose 7 10 20 30)") == 30 && v.num("(choose -2 10 20 30)") == 10, "out of range holds to the nearest end");
        h.assertTrue(v.num("(choose 3 10 20 30 :wrap)") == 10 && v.num("(choose -1 10 20 30 :wrap)") == 30, ":wrap goes round");
        Vec3 here = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        Vec3 up = ((Place) v.value("(choose 1 here (above here 3))")).pos();
        h.assertTrue(up.distanceTo(here.add(0, 3, 0)) < 1e-9, "choose works on places too, got " + up);
        // Only the chosen option runs: the unchosen look-point would cost 1 mana.
        var spell = Casting.compile("(let $v (choose 0 here (look-point)) (do))");
        var inst = new SpellInstance(spell, v.caster(), h.getLevel(), 1000, 1);
        Interp.eval(spell.body().getFirst().arg(1), new Ctx(inst, h.getLevel(), here, new Vec3(0, 0, 1), null, Map.of(), 0, 0));
        h.assertTrue(inst.mana == 1000, "the unchosen option must not be evaluated, mana " + inst.mana);
        h.succeed();
    }
}
