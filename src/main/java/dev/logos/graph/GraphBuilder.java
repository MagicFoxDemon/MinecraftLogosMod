package dev.logos.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * to its left for value expressions (one sub-lane per nesting level); event, body and then/else branches
 * open columns side by side to the right, far enough out to leave room for their own value lanes.
 */
public final class GraphBuilder {
    /** Measured in graph units at default zoom: flow nodes reach ~306 wide (beam), value nodes ~268 (enemies), context nodes ~60. */
    private static final float NODE_W = 310;
    private static final float LANE_W = 290;
    private static final float GAP_X = 50;
    private static final float GAP_Y = 24;
    private static final float VALUE_GAP_Y = 12;

    private final SpellGraph graph = new SpellGraph();
    private final Map<String, PortModel> vars = new HashMap<>();
    private float y;
    /** Right edge of the flow nodes placed so far in the branch being laid out. */
    private float right;

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

    /**
     * Rendered height, fitted to the editor at default zoom: a title, rows of side-by-side
     * inputs/outputs, option rows, and a strip for each vertical flow port (▶ above, ▶ below).
     */
    private static float height(LogosNode n) {
        int in = 0;
        int out = 0;
        int flow = 0;
        for (PortModel p : n.model().getInputsById().values()) {
            if (p.getPortId().equals(RuneNode.IN)) flow++;
            else if (!p.getDataTypeHandle().equals(LTypes.FLOW)) in++;
        }
        for (PortModel p : n.model().getOutputsById().values()) {
            if (p.getPortId().equals(RuneNode.NEXT) || p.getPortId().equals(SpellRoot.START)) flow++;
            else out++;
        }
        int rows = Math.max(in, out);
        int options = n.getNodeOptions().size();
        return 21 + 16 * rows + (options > 0 ? 4 + 16 * options : 0) + 24 * flow + (rows > 0 && flow > 0 ? 6 : 0);
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
        right = Math.max(right, x + NODE_W);
        float top = y;
        float valuesBottom;
        Map<PortModel, List<Node>> branches = new LinkedHashMap<>();
        if (s.rune.name.equals("if")) {
            valuesBottom = value(s.arg(0), n.in("cond"), x - LANE_W, top);
            branches.put(n.out("then"), flatten(s.arg(1)));
            if (s.arg(2) != null) branches.put(n.out("else"), flatten(s.arg(2)));
        } else {
            valuesBottom = arguments(n, s, x, top);
            for (var e : s.events.entrySet()) branches.put(n.out(RuneNode.event(e.getKey())), e.getValue());
            if (!s.body.isEmpty()) branches.put(n.out(RuneNode.BODY), s.body);
        }
        branches.values().removeIf(List::isEmpty);
        branches(n, top, x, branches);
        y = Math.max(Math.max(y, top + height(n)), valuesBottom) + GAP_Y;
        return n;
    }

    /**
     * Lays out a node's event, body or then/else branches side by side to its right, each in its own
     * column past everything the previous one placed, so no wire has to pass another branch to reach
     * its target. Wires leave the node's side ports, so when they would cross value lanes or other
     * branches, all branches start below the node and every wire reaches its target from above.
     * Leaves {@code y} at the bottom of the lowest branch.
     */
    private void branches(RuneNode n, float top, float x, Map<PortModel, List<Node>> branches) {
        boolean clear = branches.size() == 1 && lanes(branches.values().iterator().next()) == 0;
        float start = clear ? top : top + height(n) + GAP_Y;
        float bottom = top;
        float edge = x + NODE_W;
        float outer = right;
        for (var b : branches.entrySet()) {
            float bx = edge + GAP_X + lanes(b.getValue()) * LANE_W;
            y = start;
            right = bx + NODE_W;
            chain(b.getValue(), b.getKey(), bx);
            bottom = Math.max(bottom, y);
            edge = right;
            outer = Math.max(outer, right);
        }
        right = outer;
        y = bottom;
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
        for (var e : s.kw.entrySet()) {
            Rune.Kw k = r.keywords.get(e.getKey());
            if (k != null && k.choice()) n.setOption(RuneNode.choice(k.name()), (String) e.getValue().value);
        }
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
