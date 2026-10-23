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
        enumCardsRoundtrip(h);
        choiceRoundtrip(h);
        h.succeed();
    }

    /** A choice keyword becomes the node's dropdown and comes back as the same word; its default is left out. */
    private static void choiceRoundtrip(GameTestHelper h) {
        for (String mode : new String[] {"up", "down", "nearest"}) {
            String src = "(spell :mana 10 (heal caster (round 2.5 :mode " + mode + ")))";
            SpellGraph graph = GraphBuilder.build(Compiler.compile(src));
            GraphCompiler.Result r = GraphCompiler.compile(graph);
            h.assertTrue(r.ok(), "round :mode " + mode + ": graph does not compile: " + r.error());
            boolean written = r.source().contains(":mode " + mode);
            h.assertTrue(mode.equals("nearest") ? !written && !r.source().contains(":mode") : written,
                    "round :mode " + mode + " did not survive the graph:\n" + r.source());
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            graph.graphModel.serialize(out);
            SpellGraph reloaded = new SpellGraph();
            reloaded.graphModel.deserialize(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            h.assertTrue(GraphCompiler.compile(reloaded).source().equals(r.source()), "round :mode " + mode + " changed after save/load");
        }
    }

    /** Registry cards, wired former options and typed inline IDs keep their meaning through graph saves. */
    private static void enumCardsRoundtrip(GameTestHelper h) {
        record Case(dev.logos.graph.EnumValues.Kind kind, String source, String rune, String port, String value) {}
        var cases = java.util.List.of(
                new Case(dev.logos.graph.EnumValues.Kind.CREATURE, "(summon zombie)", "summon", "creature", "minecraft:skeleton"),
                new Case(dev.logos.graph.EnumValues.Kind.DIMENSION, "(blink (in-dimension @minecraft:the_nether))", "in-dimension", "dimension", "minecraft:the_end"),
                new Case(dev.logos.graph.EnumValues.Kind.EFFECT, "(potion caster speed 2)", "potion", "effect", "minecraft:regeneration"),
                new Case(dev.logos.graph.EnumValues.Kind.KIND, "(glow (entities :filter (kind all)) 1)", "kind", "kind", "hostile"),
                new Case(dev.logos.graph.EnumValues.Kind.ITEM, "(equip caster &minecraft:stone)", "equip", "item", "minecraft:diamond_sword"),
                new Case(dev.logos.graph.EnumValues.Kind.ITEM, "(projectile)", "projectile", RuneNode.kw("look"), "minecraft:diamond_sword"),
                new Case(dev.logos.graph.EnumValues.Kind.BLOCK, "(construct)", "construct", RuneNode.kw("look"), "minecraft:stone"),
                new Case(dev.logos.graph.EnumValues.Kind.PARTICLE, "(phantasm %end_rod)", "phantasm", "particle", "minecraft:flame"),
                new Case(dev.logos.graph.EnumValues.Kind.ELEMENT, "(damage caster 1)", "damage", "element", "fire"),
                new Case(dev.logos.graph.EnumValues.Kind.MOTION, "(projectile)", "projectile", RuneNode.kw("motion"), "homing"),
                new Case(dev.logos.graph.EnumValues.Kind.SHAPE, "(construct)", "construct", RuneNode.kw("shape"), "dome"),
                new Case(dev.logos.graph.EnumValues.Kind.WEATHER, "(weather rain)", "weather", "weather", "snow"));
        for (Case c : cases) {
            SpellGraph graph = GraphBuilder.build(Compiler.compile("(spell :mana 1000 " + c.source() + ")"));
            RuneNode consumer = graph.getNodes().stream().filter(n -> n instanceof RuneNode rn && rn.word().equals(c.rune()))
                    .map(n -> (RuneNode) n).findFirst().orElseThrow();
            var card = place(graph, c.kind().create());
            card.setOption(dev.logos.graph.EnumValues.VALUE, c.value());
            String unwired = GraphCompiler.compile(graph).source();
            h.assertTrue(graph.graphModel.isCompatiblePort(card.out(RuneNode.OUT), consumer.in(c.port())), c.kind() + " connector is incompatible");
            var wire = graph.graphModel.createWire(consumer.in(c.port()), card.out(RuneNode.OUT));
            GraphCompiler.Result compiled = GraphCompiler.compile(graph);
            h.assertTrue(compiled.ok() && compiled.source().contains(c.value()), c.kind() + " did not compile: " + compiled.error());
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            graph.graphModel.serialize(out);
            SpellGraph loaded = new SpellGraph();
            loaded.graphModel.deserialize(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                    h.getLevel().registryAccess(), out.buildResult()));
            GraphCompiler.Result back = GraphCompiler.compile(loaded);
            h.assertTrue(back.ok() && back.source().equals(compiled.source()), c.kind() + " changed after saving: " + back.error());
            graph.graphModel.deleteWire(wire);
            GraphCompiler.Result disconnected = GraphCompiler.compile(graph);
            h.assertTrue(disconnected.ok() && disconnected.source().equals(unwired), c.kind() + " lost its original fallback after disconnecting");
        }
        SpellGraph graph = new SpellGraph();
        var creature = place(graph, new dev.logos.graph.EnumValues.Creature());
        var particle = place(graph, new dev.logos.graph.EnumValues.Particle());
        var summon = place(graph, new RuneNodes.Summon());
        var phantasm = place(graph, new RuneNodes.Phantasm());
        var selector = place(graph, new RuneNodes.Entities());
        h.assertTrue(!graph.graphModel.isCompatiblePort(creature.out(RuneNode.OUT), phantasm.in("particle")), "creature ID fits a particle input");
        h.assertTrue(!graph.graphModel.isCompatiblePort(particle.out(RuneNode.OUT), summon.in("creature")), "particle ID fits a creature input");
        h.assertTrue(graph.graphModel.isCompatiblePort(creature.out(dev.logos.graph.EnumValues.FILTER), selector.in(RuneNode.kw("filter"))), "registry filter connector is incompatible");
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
