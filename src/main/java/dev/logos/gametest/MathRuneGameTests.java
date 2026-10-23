package dev.logos.gametest;

import java.util.List;
import java.util.Map;

import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.SpellGraph;
import dev.logos.lang.Compiler;
import dev.logos.lang.Node;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Interp;
import dev.logos.runtime.Place;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Interpreter and graph persistence checks for number and dimension-bearing position arithmetic. */
final class MathRuneGameTests {
    private MathRuneGameTests() {}

    private static Node expression(String source) {
        return Compiler.compile("(spell :mana 100 (echo " + source + "))").body().getFirst().arg(0);
    }

    static void values(GameTestHelper h) {
        @SuppressWarnings("removal")
        var caster = h.makeMockServerPlayerInLevel();
        var spell = Compiler.compile("(spell :mana 100 (echo 0))");
        var instance = new SpellInstance(spell, caster, h.getLevel(), 100, 0);
        var context = new Ctx(instance, h.getLevel(), new Vec3(2, 4, 6), new Vec3(1, 0, 0), null, Map.of(), 0, 0);
        h.assertTrue(Interp.num(expression("(lerp 10 20 2)"), context) == 30, "number blending must extrapolate");
        h.assertTrue(Interp.num(expression("(clamp (lerp 10 20 -2) :min -5 :max 5)"), context) == -5, "bounds compose with other expressions");
        h.assertTrue(Interp.num(expression("(round -1.5)"), context) == -2, "nearest rounds negative halves away from zero");
        h.assertTrue(Interp.num(expression("(round -1.2 :mode \"up\")"), context) == -1, "up is ceiling");
        h.assertTrue(Interp.num(expression("(round -1.2 :mode \"down\")"), context) == -2, "down is floor");
        for (int i = 0; i < 32; i++) {
            double integer = Interp.num(expression("(random 6 :min 2 :kind \"integer\")"), context);
            double legacy = Interp.num(expression("(random -5)"), context);
            h.assertTrue(integer >= 2 && integer <= 6 && integer == Math.floor(integer), "integer draw respects bounds");
            h.assertTrue(legacy > -5 && legacy <= 0, "legacy negative random scale remains supported");
        }
        for (double alpha : new double[]{-1, 0, 0.5, 1, 2}) {
            Place point = (Place) Interp.eval(expression("(lerp-pos here (above here 10) " + alpha + ")"), context);
            h.assertTrue(point.level() == h.getLevel() && point.pos().distanceTo(new Vec3(2, 4 + 10 * alpha, 6)) < 1e-9,
                    "position blending preserves its dimension and extends the line");
        }
        h.assertTrue(instance.mana == 100, "math runes cost no mana");
        Object wrongWorld = Interp.eval(expression("(lerp-pos here (in-dimension @minecraft:the_nether here) 0.5)"), context);
        h.assertTrue(wrongWorld == null && instance.dead && instance.endReason().contains("同一维度"), "different dimensions cannot be silently blended");
        h.succeed();
    }

    static void roundtrip(GameTestHelper h) {
        for (String source : List.of("random", "(random -5)", "(random 8 :min 2 :kind \"integer\")",
                "(round -1.5)", "(round -1.5 :mode \"up\")", "(round -1.5 :mode \"down\")",
                "(clamp 3)", "(clamp 3 :min -2 :max 2)", "(lerp 10 20 1.5)",
                "(lerp-pos here (above here 10) -0.5)")) {
            var graph = GraphBuilder.build(Compiler.compile("(spell :mana 100 (echo " + source + "))"));
            var compiled = GraphCompiler.compile(graph);
            h.assertTrue(compiled.ok(), "math graph must compile: " + source + ": " + compiled.error());
            var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            graph.graphModel.serialize(out);
            var restored = new SpellGraph();
            restored.graphModel.deserialize(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            var after = GraphCompiler.compile(restored);
            h.assertTrue(after.ok() && compiled.source().equals(after.source()), "mode, bounds and inputs must survive saving: " + source);
            for (var n : restored.getNodes()) if (n instanceof dev.logos.graph.RuneNode rune) {
                h.assertTrue(!rune.doc().contains("尚待补充"), "all math inputs must have help: " + rune.word());
            }
        }
        h.succeed();
    }
}
