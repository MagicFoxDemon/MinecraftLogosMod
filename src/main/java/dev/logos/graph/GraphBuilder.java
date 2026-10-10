package dev.logos.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;

import dev.logos.graph.SpecialNodes.ContextValue;
import dev.logos.graph.SpecialNodes.Remember;
import dev.logos.graph.SpecialNodes.RememberKind;
import dev.logos.graph.SpecialNodes.SpellRoot;
import dev.logos.graph.SpecialNodes.Trigger;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.lang.Node;
import dev.logos.lang.Rune;
import dev.logos.lang.Spell;
import dev.logos.lang.Status;

/**
 * Lays out a compiled spell as a node graph. Flow runs down a column; each column keeps a lane
 * to its left for value expressions (one sub-lane per nesting level); event and body branches open
 * new columns to the right, far enough out to leave room for their own value lanes.
 */
public final class GraphBuilder {
    /** Measured in graph units at default zoom: a rune node is ~180 wide, a context node ~85. */
    private static final float NODE_W = 200;
    private static final float LANE_W = 215;
    private static final float GAP_X = 50;
    private static final float GAP_Y = 24;
    private static final float VALUE_GAP_Y = 12;

    private final SpellGraph graph = new SpellGraph();
    private final Map<String, PortModel> vars = new HashMap<>();
    private float y;

    private GraphBuilder() {}

    /** A graph containing only a fresh root node. */
    public static SpellGraph empty() {
        GraphBuilder b = new GraphBuilder();
        b.create(new SpellRoot(), 0, 0);
        return b.graph;
    }

    public static SpellGraph build(Spell spell) {
        GraphBuilder b = new GraphBuilder();
        float x = lanes(spell.body()) * LANE_W;
        SpellRoot root = b.create(new SpellRoot(), x, 0);
        root.setOption(SpellRoot.NAME, spell.name());
        root.setOption(SpellRoot.MANA, (int) Math.min(Integer.MAX_VALUE, Math.round(spell.mana())));
        root.setOption(SpellRoot.TRIGGER, Trigger.of(spell.trigger()));
        root.setOption(SpellRoot.PLAYERS, spell.affectsPlayers());
        root.setOption(SpellRoot.IMMOLATE, spell.immolate());
        // Only a declared intent is kept on the node; an inferred one is worked out again on compiling.
        root.setOption(SpellRoot.INTENT, spell.profile().declared() ? SpecialNodes.IntentChoice.of(spell.profile().primary()) : SpecialNodes.IntentChoice.AUTO);
        b.y = height(root) + GAP_Y;
        b.chain(spell.body(), root.out(SpellRoot.START), x);
        return b.graph;
    }

    private <T extends LogosNode> T create(T node, float x, float y) {
        graph.graphModel.createNodeModel(node, new Vector2f(x, y));
        return node;
    }

    private void wire(PortModel from, PortModel to) {
        if (from != null && to != null) graph.graphModel.createWire(to, from);
    }

    /** Rough rendered height: title, the flow strip, then rows of side-by-side inputs/outputs. */
    private static float height(LogosNode n) {
        int in = 0;
        int out = 0;
        for (PortModel p : n.model().getInputsById().values()) if (!p.getDataTypeHandle().equals(LTypes.FLOW)) in++;
        for (PortModel p : n.model().getOutputsById().values()) if (!p.getPortId().equals(RuneNode.NEXT)) out++;
        return 60 + 26 * Math.max(in, out);
    }

    // ---- lane widths -------------------------------------------------------------------------

    /** How many value lanes a column of statements needs to its left. */
    private static int lanes(List<Node> stmts) {
        int max = 0;
        for (Node s : stmts) {
            if (s.kind != Node.Kind.CALL) continue;
            switch (s.rune.name) {
                case "do" -> max = Math.max(max, lanes(s.body));
                case "let" -> max = Math.max(max, Math.max(depth(s.arg(1)), lanes(s.body)));
                case "if" -> max = Math.max(max, depth(s.arg(0)));
                default -> max = Math.max(max, argDepth(s));
            }
        }
        return max;
    }

    private static int argDepth(Node s) {
        int d = 0;
        for (Node a : s.args) d = Math.max(d, depth(a));
        for (var e : s.kw.entrySet()) if (!s.defaulted.contains(e.getKey())) d = Math.max(d, depth(e.getValue()));
        return d;
    }

    private static int depth(Node v) {
        if (v == null) return 0;
        return switch (v.kind) {
            case LIT, VAR -> 0;
            case CTX -> 1;
            case CALL -> 1 + argDepth(v);
        };
    }

    private float columnFor(List<Node> stmts, float parentX) {
        return parentX + NODE_W + GAP_X + lanes(stmts) * LANE_W;
    }

    // ---- flow --------------------------------------------------------------------------------

    private void chain(List<Node> stmts, PortModel from, float x) {
        PortModel prev = from;
        for (int i = 0; i < stmts.size(); i++) {
            Node s = stmts.get(i);
            if (s.rune.name.equals("do")) {
                List<Node> flat = new ArrayList<>(s.body);
                flat.addAll(stmts.subList(i + 1, stmts.size()));
                chain(flat, prev, x);
                return;
            }
            if (s.rune.name.equals("let")) {
                Remember r = create(new Remember(), x, y);
                r.setOption(Remember.KIND, RememberKind.of(s.arg(1).type));
                wire(prev, r.in(RuneNode.IN));
                float bottom = value(s.arg(1), r.in(Remember.VALUE), x - LANE_W, y);
                vars.put((String) s.arg(0).value, r.out(RuneNode.OUT));
                y = Math.max(y + height(r), bottom) + GAP_Y;
                // In the graph, everything after "remember" is inside its scope.
                List<Node> rest = new ArrayList<>(s.body);
                rest.addAll(stmts.subList(i + 1, stmts.size()));
                chain(rest, r.out(RuneNode.NEXT), x);
                return;
            }
            RuneNode n = statement(s, x);
            wire(prev, n.in(RuneNode.IN));
            prev = n.out(RuneNode.NEXT);
        }
    }

    private RuneNode statement(Node s, float x) {
        RuneNode n = create(RuneNodes.BY_RUNE.get(s.rune.name).get(), x, y);
        float top = y;
        float valuesBottom;
        if (s.rune.name.equals("if")) {
            valuesBottom = value(s.arg(0), n.in("cond"), x - LANE_W, top);
            List<Node> then = flatten(s.arg(1));
            List<Node> otherwise = s.arg(2) == null ? List.of() : flatten(s.arg(2));
            List<Node> both = new ArrayList<>(then);
            both.addAll(otherwise);
            float bx = columnFor(both, x);
            y = top;
            chain(then, n.out("then"), bx);
            chain(otherwise, n.out("else"), bx);
        } else {
            valuesBottom = arguments(n, s, x, top);
            List<Node> branches = new ArrayList<>(s.body);
            s.events.values().forEach(branches::addAll);
            float bx = columnFor(branches, x);
            y = top;
            for (var e : s.events.entrySet()) chain(e.getValue(), n.out(RuneNode.event(e.getKey())), bx);
            if (!s.body.isEmpty()) chain(s.body, n.out(RuneNode.BODY), bx);
        }
        y = Math.max(Math.max(y, top + height(n)), valuesBottom) + GAP_Y;
        return n;
    }

    private static List<Node> flatten(Node action) {
        return action.rune != null && action.rune.name.equals("do") ? action.body : List.of(action);
    }

    // ---- values ------------------------------------------------------------------------------

    /** Sets options and fills inputs; returns the bottom of the value nodes placed for them. */
    private float arguments(RuneNode n, Node s, float x, float top) {
        Rune r = s.rune;
        boolean rest = r.params.stream().anyMatch(Rune.Param::rest);
        if (rest) n.setOption(RuneNode.COUNT, s.args.size());
        for (String f : s.flags) n.setOption(RuneNode.flag(f), true);
        float vy = top;
        for (int i = 0; i < s.args.size(); i++) {
            Rune.Param p = rest ? r.params.getFirst() : r.params.get(i);
            if (p.type() == dev.logos.lang.Type.DIMENSION) {
                n.setOption(RuneNode.dim(p.name()), (String) s.args.get(i).value);
                continue;
            }
            if (p.type() == dev.logos.lang.Type.CREATURE || p.type() == dev.logos.lang.Type.MOB_EFFECT) {
                n.setOption(RuneNode.option(p), String.valueOf(s.args.get(i).value).replace("minecraft:", ""));
                continue;
            }
            if (p.type() == dev.logos.lang.Type.ANCHOR) {
                n.setOption(RuneNode.option(p), (String) s.args.get(i).value);
                continue;
            }
            if (p.type() == dev.logos.lang.Type.KIND) {
                n.setOption(RuneNode.option(p), ((dev.logos.lang.TargetKind) s.args.get(i).value).word);
                continue;
            }
            vy = value(s.args.get(i), n.in(rest ? p.name() + i : p.name()), x - LANE_W, vy);
        }
        for (var e : s.kw.entrySet()) {
            if (s.defaulted.contains(e.getKey())) continue;
            vy = value(e.getValue(), n.in(RuneNode.kw(e.getKey())), x - LANE_W, vy);
        }
        return vy;
    }

    /** Wires or inlines a value into {@code input}; nodes go at (x, vy). Returns the next free y. */
    private float value(Node v, PortModel input, float x, float vy) {
        if (input == null || v == null) return vy;
        switch (v.kind) {
            case LIT -> {
                if (v.type == dev.logos.lang.Type.ANCHOR) {
                    var av = new SpecialNodes.AnchorValue();
                    create(av, x + LANE_W - 140, vy);
                    av.setOption(SpecialNodes.AnchorValue.NAME, (String) v.value);
                    wire(av.out(RuneNode.OUT), input);
                    return vy + height(av) + VALUE_GAP_Y;
                }
                Object c = switch (v.value) {
                    case Double d -> d.floatValue();
                    case Status st -> st.name();
                    case Element e -> e;
                    case Motion m -> m;
                    default -> v.value;
                };
                if (input.getEmbeddedValue() != null) input.getEmbeddedValue().setValue(c);
                return vy;
            }
            case VAR -> {
                wire(vars.get((String) v.value), input);
                return vy;
            }
            case CTX -> {
                ContextValue cv = context((String) v.value);
                // Context nodes are narrow: sit them at the right edge of their lane, next to the consumer.
                create(cv, x + LANE_W - 110, vy);
                wire(cv.out(RuneNode.OUT), input);
                return vy + height(cv) + VALUE_GAP_Y;
            }
            case CALL -> {
                RuneNode n = create(RuneNodes.BY_RUNE.get(v.rune.name).get(), x, vy);
                float childBottom = arguments(n, v, x, vy);
                wire(n.out(RuneNode.OUT), input);
                return Math.max(vy + height(n), childBottom) + VALUE_GAP_Y;
            }
        }
        return vy;
    }

    private static ContextValue context(String name) {
        return switch (name) {
            case "caster" -> new SpecialNodes.Caster();
            case "target" -> new SpecialNodes.Target();
            case "here" -> new SpecialNodes.Here();
            case "aim" -> new SpecialNodes.Aim();
            case "up" -> new SpecialNodes.Up();
            case "down" -> new SpecialNodes.Down();
            case "self" -> new SpecialNodes.Self();
            default -> new SpecialNodes.Index();
        };
    }
}
