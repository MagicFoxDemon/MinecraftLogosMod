package dev.logos.gametest;

import java.util.Set;
import java.util.stream.Collectors;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wire.WireModel;

import dev.logos.graph.ContextSources;
import dev.logos.graph.ContextSources.Meaning;
import dev.logos.graph.LogosNode;
import dev.logos.graph.RuneNode;
import dev.logos.graph.RuneNodes;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import net.minecraft.gametest.framework.GameTestHelper;

/** Context interpretation follows executable branches rather than canvas position or value-node placement. */
final class ContextSourceGameTests {
    private ContextSourceGameTests() {}

    static void meanings(GameTestHelper h) {
        sharedUses(h);
        rememberedValue(h);
        inheritedBindings(h);
        branchOverrides(h);
        h.succeed();
    }

    private static void sharedUses(GameTestHelper h) {
        SpellGraph g = new SpellGraph();
        var root = add(g, new SpecialNodes.SpellRoot());
        var field = add(g, new RuneNodes.Field());
        var projectile = add(g, new RuneNodes.Projectile());
        var here = add(g, new SpecialNodes.Here());
        var above = add(g, new RuneNodes.Above());
        var outer = add(g, new RuneNodes.Echo());
        var inner = add(g, new RuneNodes.Echo());
        var after = add(g, new RuneNodes.Echo());
        wire(g, field.in(RuneNode.IN), root.out(SpecialNodes.SpellRoot.START));
        wire(g, projectile.in(RuneNode.IN), field.out(RuneNode.event("on-tick")));
        wire(g, outer.in(RuneNode.IN), field.out(RuneNode.event("on-tick")));
        wire(g, inner.in(RuneNode.IN), projectile.out(RuneNode.event("on-hit")));
        wire(g, after.in(RuneNode.IN), field.out(RuneNode.NEXT));
        var selected = wire(g, above.in("pos"), here.out(RuneNode.OUT));
        for (var echo : new RuneNodes.Echo[]{outer, inner, after}) wire(g, echo.in("value"), above.out(RuneNode.OUT));
        var result = ContextSources.analyze(g, selected);
        h.assertTrue(result.uses().size() == 3, "shared expression must expose all three execution branches");
        h.assertTrue(meanings(result).equals(Set.of(Meaning.FIELD_POSITION, Meaning.PROJECTILE_CONTACT, Meaning.CAST_POSITION)),
                "nested hit, field tick and ordinary next need different here meanings");
        h.assertTrue(!result.incomplete(), "ordinary shared expression must resolve completely");
        h.assertTrue(ContextSources.analyze(g, wire(g, projectile.in(RuneNode.kw("from")), above.out(RuneNode.OUT))) == null,
                "ordinary value wires do not masquerade as context wires");
        // The selected connection is unused if it only feeds an orphan expression.
        var orphan = add(g, new RuneNodes.Above());
        h.assertTrue(ContextSources.analyze(g, wire(g, orphan.in("pos"), here.out(RuneNode.OUT))).uses().isEmpty(),
                "orphan values must be explicitly unresolved");
        // Editing flow must invalidate the explanation without changing or recompiling the spell.
        var before = g.graphModel.getNodeModels().size();
        var recurse = add(g, new RuneNodes.Recurse());
        wire(g, recurse.in(RuneNode.IN), projectile.out(RuneNode.NEXT));
        h.assertTrue(ContextSources.analyze(g, selected).recursive(), "recursive re-entry must be disclosed");
        h.assertTrue(g.graphModel.getNodeModels().size() == before + 1, "analysis must not create nodes");
    }

    private static void rememberedValue(GameTestHelper h) {
        SpellGraph g = new SpellGraph();
        var root = add(g, new SpecialNodes.SpellRoot());
        var field = add(g, new RuneNodes.Field());
        var remember = add(g, new SpecialNodes.Remember());
        var projectile = add(g, new RuneNodes.Projectile());
        var echo = add(g, new RuneNodes.Echo());
        var here = add(g, new SpecialNodes.Here());
        wire(g, field.in(RuneNode.IN), root.out(SpecialNodes.SpellRoot.START));
        wire(g, remember.in(RuneNode.IN), field.out(RuneNode.event("on-tick")));
        wire(g, projectile.in(RuneNode.IN), remember.out(RuneNode.NEXT));
        wire(g, echo.in(RuneNode.IN), projectile.out(RuneNode.event("on-hit")));
        wire(g, echo.in("value"), remember.out(RuneNode.OUT));
        var result = ContextSources.analyze(g, wire(g, remember.in(SpecialNodes.Remember.VALUE), here.out(RuneNode.OUT)));
        h.assertTrue(result.uses().size() == 1 && result.uses().getFirst().consumer() == remember,
                "let captures context at assignment, not at later reads");
        h.assertTrue(meanings(result).equals(Set.of(Meaning.FIELD_POSITION)), "saved position belongs to the field tick");
    }

    private static void inheritedBindings(GameTestHelper h) {
        SpellGraph g = new SpellGraph();
        var root = add(g, new SpecialNodes.SpellRoot());
        var construct = add(g, new RuneNodes.Construct());
        var projectile = add(g, new RuneNodes.Projectile());
        var echo = add(g, new RuneNodes.Echo());
        var self = add(g, new SpecialNodes.Self());
        var target = add(g, new SpecialNodes.Target());
        var targetEcho = add(g, new RuneNodes.Echo());
        wire(g, construct.in(RuneNode.IN), root.out(SpecialNodes.SpellRoot.START));
        wire(g, projectile.in(RuneNode.IN), construct.out(RuneNode.event("on-block")));
        wire(g, echo.in(RuneNode.IN), projectile.out(RuneNode.event("on-tick")));
        wire(g, targetEcho.in(RuneNode.IN), projectile.out(RuneNode.event("on-tick")));
        var result = ContextSources.analyze(g, wire(g, echo.in("value"), self.out(RuneNode.OUT)));
        h.assertTrue(result.uses().getFirst().binding().source() == construct, "nested projectile keeps the outer construct self");
        h.assertTrue(meanings(result).equals(Set.of(Meaning.CONSTRUCT_SELF)), "self remains a construct, not a projectile");
        var targets = ContextSources.analyze(g, wire(g, targetEcho.in("value"), target.out(RuneNode.OUT)));
        h.assertTrue(meanings(targets).equals(Set.of(Meaning.CONSTRUCT_TARGET)), "nonbinding event inherits target");
    }

    private static void branchOverrides(GameTestHelper h) {
        SpellGraph g = new SpellGraph();
        var root = add(g, new SpecialNodes.SpellRoot());
        root.setOption(SpecialNodes.SpellRoot.TRIGGER, SpecialNodes.Trigger.ATTACK);
        var repeat = add(g, new RuneNodes.Repeat());
        var nested = add(g, new RuneNodes.Repeat());
        var delay = add(g, new RuneNodes.Delay());
        var echo = add(g, new RuneNodes.Echo());
        var index = add(g, new SpecialNodes.Index());
        var here = add(g, new SpecialNodes.Here());
        var position = add(g, new RuneNodes.Echo());
        wire(g, repeat.in(RuneNode.IN), root.out(SpecialNodes.SpellRoot.START));
        wire(g, nested.in(RuneNode.IN), repeat.out(RuneNode.BODY));
        wire(g, delay.in(RuneNode.IN), nested.out(RuneNode.BODY));
        wire(g, echo.in(RuneNode.IN), delay.out(RuneNode.BODY));
        wire(g, position.in(RuneNode.IN), delay.out(RuneNode.BODY));
        var result = ContextSources.analyze(g, wire(g, echo.in("value"), index.out(RuneNode.OUT)));
        h.assertTrue(result.uses().getFirst().binding().source() == nested, "innermost repeat supplies i through delay");
        h.assertTrue(meanings(result).equals(Set.of(Meaning.REPEAT_INDEX)), "repeat index must resolve");
        h.assertTrue(meanings(ContextSources.analyze(g, wire(g, position.in("value"), here.out(RuneNode.OUT)))).equals(Set.of(Meaning.ATTACK_POSITION)),
                "repeat and delay retain invocation position");
        // Invalid cyclic flow is bounded rather than hanging the workbench.
        wire(g, repeat.in(RuneNode.IN), delay.out(RuneNode.NEXT));
        h.assertTrue(ContextSources.analyze(g, resultWire(g, here)).incomplete(), "cyclic flow must be reported");
    }

    private static WireModel resultWire(SpellGraph g, SpecialNodes.Here here) {
        var echo = add(g, new RuneNodes.Echo());
        // Unused wire is enough to request analysis of the reachable cyclic flow.
        return wire(g, echo.in("value"), here.out(RuneNode.OUT));
    }

    private static Set<Meaning> meanings(ContextSources.Analysis result) {
        return result.uses().stream().map(use -> use.binding().meaning()).collect(Collectors.toSet());
    }

    private static WireModel wire(SpellGraph g, PortModel input, PortModel output) {
        return g.graphModel.createWire(input, output);
    }

    private static <T extends LogosNode> T add(SpellGraph g, T node) {
        g.graphModel.createNodeModel(node, new Vector2f(0, 0));
        return node;
    }
}
