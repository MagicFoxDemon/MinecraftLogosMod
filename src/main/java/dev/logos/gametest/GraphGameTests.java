package dev.logos.gametest;

import java.util.Map;

import org.joml.Vector2f;

import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.Spell;
import net.minecraft.gametest.framework.GameTestHelper;

/** Node-graph editor model tests (no rendering): source ↔ graph equivalence and port typing. */
final class GraphGameTests {
    private GraphGameTests() {}

    /** Every example survives source → graph → source with the same meaning. */
    static void roundtrip(GameTestHelper h) {
        for (Map.Entry<String, String> e : Examples.ALL.entrySet()) {
            Spell original = Compiler.compile(e.getValue());
            SpellGraph graph = GraphBuilder.build(original);
            GraphCompiler.Result r = GraphCompiler.compile(graph);
            h.assertTrue(r.ok(), e.getKey() + ": graph does not compile: " + r.error() + "\n" + r.source());
            Spell back = r.spell();
            h.assertTrue(back.name().equals(original.name()) && back.trigger().equals(original.trigger()) && back.mana() == original.mana(),
                    e.getKey() + ": header changed");
            h.assertTrue(Math.abs(back.stability() - original.stability()) < 0.05,
                    e.getKey() + ": stability " + original.stability() + " → " + back.stability() + "\n" + r.source());
            h.assertTrue(back.notes().equals(original.notes()), e.getKey() + ": proofs changed " + original.notes() + " → " + back.notes());
            // And the regenerated source is itself a stable fixed point.
            Spell again = Compiler.compile(r.source());
            h.assertTrue(again.stability() == back.stability(), e.getKey() + ": regenerated source is not stable");
            // The wand stores the drawing as NBT: saving and reloading must give the same spell.
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            graph.graphModel.serialize(out);
            SpellGraph reloaded = new SpellGraph();
            reloaded.graphModel.deserialize(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            GraphCompiler.Result rr = GraphCompiler.compile(reloaded);
            h.assertTrue(rr.ok() && rr.source().equals(r.source()), e.getKey() + ": graph changed after save/load: " + rr.error() + "\n" + rr.source());
        }
        h.succeed();
    }

    /** Wires only snap between compatible slot shapes. */
    static void rejectsBadWires(GameTestHelper h) {
        SpellGraph g = new SpellGraph();
        var enemies = place(g, new RuneNodes.Enemies());
        var caster = place(g, new SpecialNodes.Caster());
        var here = place(g, new SpecialNodes.Here());
        var damage = place(g, new RuneNodes.Damage());
        var execute = place(g, new RuneNodes.Execute());
        var hp = place(g, new RuneNodes.Hp());
        var model = g.graphModel;
        h.assertTrue(model.isCompatiblePort(caster.out(RuneNode.OUT), damage.in("target")), "○ entity → broadcast slot should connect");
        h.assertTrue(model.isCompatiblePort(enemies.out(RuneNode.OUT), damage.in("target")), "◎ entities → broadcast slot should connect");
        h.assertTrue(model.isCompatiblePort(caster.out(RuneNode.OUT), execute.in("target")), "○ entity → ○ slot should connect");
        h.assertTrue(!model.isCompatiblePort(enemies.out(RuneNode.OUT), execute.in("target")), "◎ entities must not fit execute's ○ slot");
        h.assertTrue(!model.isCompatiblePort(here.out(RuneNode.OUT), damage.in("target")), "◇ position must not fit an entity slot");
        h.assertTrue(!model.isCompatiblePort(hp.out(RuneNode.OUT), damage.in("target")), "□ number must not fit an entity slot");
        h.assertTrue(model.isCompatiblePort(hp.out(RuneNode.OUT), damage.in("amount")), "□ number → □ amount should connect");
        h.assertTrue(!model.isCompatiblePort(damage.out(RuneNode.NEXT), hp.in("entity")), "▶ flow must not fit a value slot");
        h.succeed();
    }

    /** Graph diagnostics name the node that is wrong. */
    static void errorsPointAtNodes(GameTestHelper h) {
        SpellGraph g = GraphBuilder.empty();
        var root = g.getNodes().stream().filter(n -> n instanceof SpecialNodes.SpellRoot).map(n -> (SpecialNodes.SpellRoot) n).findFirst().orElseThrow();
        var damage = place(g, new RuneNodes.Damage());
        var target = place(g, new SpecialNodes.Target());
        g.graphModel.createWire(damage.in(RuneNode.IN), root.out(SpecialNodes.SpellRoot.START));
        g.graphModel.createWire(damage.in("target"), target.out(RuneNode.OUT));
        GraphCompiler.Result r = GraphCompiler.compile(g);
        h.assertTrue(!r.ok() && r.errorNode() == target && r.error().contains("target"),
                "using target outside an event should be blamed on the target node, got: " + r.error() + " @ " + r.errorNode());
        h.succeed();
    }

    private static <T extends dev.logos.graph.LogosNode> T place(SpellGraph g, T node) {
        g.graphModel.createNodeModel(node, new Vector2f(0, 0));
        return node;
    }
}
