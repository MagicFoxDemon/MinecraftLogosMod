package dev.logos.gametest;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Interp;
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
}
