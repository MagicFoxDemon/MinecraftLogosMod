package dev.logos.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

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
import dev.logos.lang.SourceNotes;
import dev.logos.lang.Spell;
import dev.logos.lang.Status;

/**
 * Lays out a compiled spell as a node graph. Flow runs down a column; each column keeps a lane
 * to its left for value expressions (one sub-lane per nesting level); event, body and then/else branches
 * open columns side by side to the right, far enough out to leave room for their own value lanes.
 * <p>Notes written into the source ({@link SourceNotes}) are drawn as comments are read: a note on a statement frames
 * it, with its values and branches, in a titled region whose text sits on a sticky note at the top; a note on the
 * whole spell is a sticky note above the root.
 */
public final class GraphBuilder {
    /** Measured in graph units at default zoom: flow nodes reach ~306 wide (beam), value nodes ~268 (enemies), context nodes ~60. */
    private static final float NODE_W = 310;
    private static final float LANE_W = 290;
    private static final float GAP_X = 50;
    private static final float GAP_Y = 24;
    private static final float VALUE_GAP_Y = 12;
    /** A region's title bar (the toolkit's placemat draws its title in this much), its margin round what it frames. */
    private static final float TITLE_H = 29, PAD = 12;
    /** A sticky note's width inside a region, and the overview note's above the root; its type size. */
    private static final float NOTE_W = NODE_W - 2 * PAD, OVERVIEW_W = 420;
    private static final int NOTE_FONT = 11;

    private final SpellGraph graph = new SpellGraph();
    private final Map<String, PortModel> vars = new HashMap<>();
    private final SourceNotes notes;
    /** The regions being laid out, outermost first, each the box of everything placed inside it so far. */
    private final List<float[]> open = new ArrayList<>();
    private float y;
    /** Right edge of the flow nodes placed so far in the branch being laid out. */
    private float right;

    private GraphBuilder(SourceNotes notes) {
        this.notes = notes;
    }

    /** A graph containing only a fresh root node. */
    public static SpellGraph empty() {
        GraphBuilder b = new GraphBuilder(SourceNotes.NONE);
        b.create(new SpellRoot(), 0, 0);
        return b.graph;
    }

    public static SpellGraph build(Spell spell) {
        return build(spell, SourceNotes.NONE);
    }

    /** The spell laid out with the notes its source carried, drawn as regions and sticky notes. */
    public static SpellGraph build(Spell spell, SourceNotes notes) {
        GraphBuilder b = new GraphBuilder(notes);
        float x = lanes(spell.body()) * LANE_W;
        SpellRoot root = b.create(new SpellRoot(), x, 0);
        root.setOption(SpellRoot.NAME, spell.name());
        root.setOption(SpellRoot.MANA, (int) Math.min(Integer.MAX_VALUE, Math.round(spell.mana())));
        root.setOption(SpellRoot.TRIGGER, Trigger.of(spell.trigger()));
        root.setOption(SpellRoot.PLAYERS, spell.affectsPlayers());
        root.setOption(SpellRoot.IMMOLATE, spell.immolate());
        // Only a declared intent is kept on the node; an inferred one is worked out again on compiling.
        root.setOption(SpellRoot.INTENT, spell.profile().declared() ? SpecialNodes.IntentChoice.of(spell.profile().primary()) : SpecialNodes.IntentChoice.AUTO);
        SourceNotes.Note head = notes.head();
        if (head != null) {
            // The whole spell's note hangs above the root, its title as the first line.
            String text = wrap(head.text().isEmpty() ? head.title() : head.title() + "\n" + head.text(), OVERVIEW_W);
            float h = noteHeight(text);
            b.sticky(text, x, -h - GAP_Y, OVERVIEW_W, h);
        }
        b.y = height(root) + GAP_Y;
        b.chain(spell.body(), root.out(SpellRoot.START), x);
        return b.graph;
    }

    private <T extends LogosNode> T create(T node, float x, float y) {
        graph.graphModel.createNodeModel(node, new Vector2f(x, y));
        if (!open.isEmpty()) {
            float w = width(node), h = height(node);
            for (float[] r : open) take(r, x, y, x + w, y + h);
        }
        return node;
    }

    /** Roughly how wide a node is drawn, for the regions round it: flow nodes are wide, values less, cards and contexts narrow. */
    private static float width(LogosNode n) {
        if (n instanceof ContextValue) return 60;
        if (n instanceof EnumValues.Value || n instanceof SpecialNodes.AnchorValue) return 130;
        if (n instanceof RuneNode r && r.rune().returns != dev.logos.lang.Type.ACTION) return 268;
        return NODE_W;
    }

    private static void take(float[] r, float left, float top, float right, float bottom) {
        r[0] = Math.min(r[0], left);
        r[1] = Math.min(r[1], top);
        r[2] = Math.max(r[2], right);
        r[3] = Math.max(r[3], bottom);
    }

    // ---- notes -------------------------------------------------------------------------------

    /** A region being laid out: its note, its text as wrapped, where its title bar starts, and the box of what it frames. */
    private record Region(SourceNotes.Note note, String text, float top, float noteHeight, float[] box) {}

    /** Starts a region for {@code note} at {@code x}: keeps room above for its title and sticky note, and frames what follows. */
    private @Nullable Region open(SourceNotes.@Nullable Note note, float x) {
        if (note == null) return null;
        float top = y;
        String text = wrap(note.text(), NOTE_W);
        float noteHeight = text.isEmpty() ? 0 : noteHeight(text) + PAD;
        y += header(note);
        float[] box = {x, y, x + NODE_W, y};
        open.add(box);
        return new Region(note, text, top, noteHeight, box);
    }

    /** The room a region for {@code note} keeps above what it frames: its title bar and sticky note. */
    private static float header(SourceNotes.@Nullable Note note) {
        if (note == null) return 0;
        String text = wrap(note.text(), NOTE_W);
        return TITLE_H + (text.isEmpty() ? 0 : noteHeight(text) + PAD) + PAD;
    }

    /**
     * How far below where {@code stmts} start their first node lands: the headers of the regions opening there,
     * following {@link #chain} (a block without a note runs on into what comes after it).
     */
    private float lead(List<Node> stmts) {
        if (stmts.isEmpty()) return 0;
        Node s = stmts.getFirst();
        SourceNotes.Note note = notes.at(s);
        if (s.rune.name.equals("do")) {
            if (note != null) return header(note) + lead(s.body);
            List<Node> flat = new ArrayList<>(s.body);
            flat.addAll(stmts.subList(1, stmts.size()));
            return lead(flat);
        }
        if (note == null && s.rune.name.equals("let") && s.arg(1).kind == Node.Kind.CALL && s.arg(1).rune.yields != null
                && s.arg(1).type == dev.logos.lang.Type.ACTION) note = notes.at(s.arg(1));
        return header(note);
    }

    /** Draws the region round what was laid out since {@link #open}, and moves on below it. */
    private void close(@Nullable Region r) {
        if (r == null) return;
        open.remove(r.box());
        float left = r.box()[0] - PAD, right = Math.max(r.box()[2], r.box()[0] + NODE_W) + PAD;
        float bottom = Math.max(r.box()[3], y - GAP_Y) + PAD;
        var mat = graph.graphModel.createPlacemat(r.note().title(), new Vector2f(left, r.top()), new Vector2f(right - left, bottom - r.top()));
        // Regions laid out later are drawn over earlier ones, so an inner region stays above the one it sits in.
        mat.setZOrder(open.size());
        if (r.noteHeight() > 0) sticky(r.text(), left + PAD, r.top() + TITLE_H, NOTE_W, r.noteHeight() - PAD);
        for (float[] outer : open) take(outer, left, r.top(), right, bottom);
        y = Math.max(y, bottom + GAP_Y);
    }

    private void sticky(String text, float x, float top, float width, float height) {
        var note = graph.graphModel.createStickyNote(new Vector2f(x, top));
        note.setContent(text);
        note.setFontSize(NOTE_FONT);
        note.setSize(new Vector2f(width, height));
    }

    /** Punctuation that never starts a line; a break before it is moved after it. */
    private static final String NO_LINE_START = "，。、；：！？）」』》〉,.;:!?)";

    /**
     * {@code text} broken into lines that fit a sticky note {@code width} wide at {@link #NOTE_FONT}, as the note's
     * text box does not wrap by itself: a Chinese character counts a full em, anything else about two thirds, with a
     * little room spare for the font's own widths.
     */
    static String wrap(String text, float width) {
        float usable = (width - 24) * 0.92f, em = NOTE_FONT;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) out.append('\n');
            float w = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                float cw = c < 0x2E80 ? em * 0.6f : em;
                if (w + cw > usable && w > 0 && NO_LINE_START.indexOf(c) < 0) {
                    out.append('\n');
                    w = 0;
                }
                out.append(c);
                w += cw;
            }
        }
        return out.toString();
    }

    /** How tall a sticky note must be to show {@code text}, already wrapped: its header, a row per line, its margin. */
    static float noteHeight(String text) {
        return 24 + text.split("\n", -1).length * (NOTE_FONT + 2) + 14;
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
        for (Node a : s.args) if (a.type != dev.logos.lang.Type.ACTION) d = Math.max(d, depth(a));
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

    /** Lays out {@code stmts} down a column from {@code from}; returns the last statement's way on. */
    private PortModel chain(List<Node> stmts, PortModel from, float x) {
        PortModel prev = from;
        for (int i = 0; i < stmts.size(); i++) {
            Node s = stmts.get(i);
            if (s.rune.name.equals("do")) {
                // A block with a note is framed as one; without one it has no node and simply runs on.
                Region region = open(notes.at(s), x);
                if (region != null) {
                    prev = chain(s.body, prev, x);
                    close(region);
                    continue;
                }
                List<Node> flat = new ArrayList<>(s.body);
                flat.addAll(stmts.subList(i + 1, stmts.size()));
                return chain(flat, prev, x);
            }
            if (s.rune.name.equals("let") && s.arg(1).kind == Node.Kind.CALL && s.arg(1).rune.yields != null && s.arg(1).type == dev.logos.lang.Type.ACTION) {
                // Keeping what a form or summon made: that node's own 此形 / 吾之使魔 output, not a remember node.
                SourceNotes.Note note = notes.at(s);
                Region region = open(note != null ? note : notes.at(s.arg(1)), x);
                RuneNode n = statement(s.arg(1), x);
                close(region);
                wire(prev, n.in(RuneNode.IN));
                vars.put((String) s.arg(0).value, n.out(RuneNode.YIELD));
                List<Node> rest = new ArrayList<>(s.body);
                rest.addAll(stmts.subList(i + 1, stmts.size()));
                return chain(rest, n.out(RuneNode.NEXT), x);
            }
            if (s.rune.name.equals("let")) {
                // A note on it frames what is remembered; the scope that follows is the rest of the column.
                Region region = open(notes.at(s), x);
                Remember r = create(new Remember(), x, y);
                r.setOption(Remember.KIND, RememberKind.of(s.arg(1).type));
                wire(prev, r.in(RuneNode.IN));
                float bottom = value(s.arg(1), r.in(Remember.VALUE), x - LANE_W, y);
                vars.put((String) s.arg(0).value, r.out(RuneNode.OUT));
                y = Math.max(y + height(r), bottom) + GAP_Y;
                close(region);
                // In the graph, everything after "remember" is inside its scope.
                List<Node> rest = new ArrayList<>(s.body);
                rest.addAll(stmts.subList(i + 1, stmts.size()));
                return chain(rest, r.out(RuneNode.NEXT), x);
            }
            Region region = open(notes.at(s), x);
            RuneNode n = statement(s, x);
            close(region);
            wire(prev, n.in(RuneNode.IN));
            prev = n.out(RuneNode.NEXT);
        }
        return prev;
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
            int restAt = s.rune.params.size() - 1;
            if (restAt >= 0 && s.rune.params.get(restAt).rest() && s.rune.params.get(restAt).type() == dev.logos.lang.Type.ACTION) {
                for (int i = restAt; i < s.args.size(); i++) branches.put(n.out(s.rune.params.get(restAt).name() + (i - restAt)), flatten(s.args.get(i)));
            }
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
     * A branch that opens with a noted region keeps room for its header, so the first nodes of all
     * branches line up below the lowest header: no wire runs down through a neighbour's first node.
     * Leaves {@code y} at the bottom of the lowest branch.
     */
    private void branches(RuneNode n, float top, float x, Map<PortModel, List<Node>> branches) {
        boolean clear = branches.size() == 1 && lanes(branches.values().iterator().next()) == 0;
        float start = clear ? top : top + height(n) + GAP_Y;
        float bottom = top;
        float edge = x + NODE_W;
        float outer = right;
        float lead = 0;
        for (List<Node> b : branches.values()) lead = Math.max(lead, lead(b));
        for (var b : branches.entrySet()) {
            float bx = edge + GAP_X + lanes(b.getValue()) * LANE_W;
            y = start + lead - lead(b.getValue());
            right = bx + NODE_W;
            chain(b.getValue(), b.getKey(), bx);
            bottom = Math.max(bottom, y);
            edge = right;
            outer = Math.max(outer, right);
        }
        right = outer;
        y = bottom;
    }

    /** A branch's statements: a {@code do}'s body, unless the block carries a note and so is framed whole. */
    private List<Node> flatten(Node action) {
        boolean block = action.rune != null && action.rune.name.equals("do");
        return block && notes.at(action) == null ? action.body : List.of(action);
    }

    // ---- values ------------------------------------------------------------------------------

    /** Sets options and fills inputs; returns the bottom of the value nodes placed for them. */
    private float arguments(RuneNode n, Node s, float x, float top) {
        Rune r = s.rune;
        // The repeated slot, if any, is the last; its values are numbered from where it starts.
        int restAt = r.params.stream().anyMatch(Rune.Param::rest) ? r.params.size() - 1 : -1;
        if (restAt >= 0) n.setOption(RuneNode.COUNT, s.args.size() - restAt);
        if (r.returns == dev.logos.lang.Type.ANY) n.setOption(RuneNode.KIND, RememberKind.of(s.type));
        for (String f : s.flags) n.setOption(RuneNode.flag(f), true);
        for (var e : s.kw.entrySet()) {
            Rune.Kw k = r.keywords.get(e.getKey());
            if (k != null && k.choice()) n.setOption(RuneNode.choice(k.name()), (String) e.getValue().value);
        }
        float vy = top;
        for (int i = 0; i < s.args.size(); i++) {
            boolean repeated = restAt >= 0 && i >= restAt;
            Rune.Param p = r.params.get(repeated ? restAt : i);
            // Branches (switch) are laid out by statement(), not filled in as values.
            if (p.type() == dev.logos.lang.Type.ACTION) continue;
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
            if (p.type() == dev.logos.lang.Type.KIND && s.args.get(i).value instanceof dev.logos.lang.TargetKind kind) {
                n.setOption(RuneNode.option(p), kind.word);
                continue;
            }
            vy = value(s.args.get(i), n.in(repeated ? p.name() + (i - restAt) : p.name()), x - LANE_W, vy);
        }
        for (var e : s.kw.entrySet()) {
            if (s.defaulted.contains(e.getKey())) continue;
            vy = value(e.getValue(), n.in(RuneNode.kw(e.getKey())), x - LANE_W, vy);
            // A construct's shape (a vein's motion) decides which inputs show: redraw so the hidden ones follow it.
            if (e.getValue().type == dev.logos.lang.Type.SHAPE || e.getValue().type == dev.logos.lang.Type.MOTION) n.model().defineNode();
        }
        return vy;
    }

    /** Wires or inlines a value into {@code input}; nodes go at (x, vy). Returns the next free y. */
    private float value(Node v, PortModel input, float x, float vy) {
        if (input == null || v == null) return vy;
        switch (v.kind) {
            case LIT -> {
                if (v.type == dev.logos.lang.Type.CREATURE && LTypes.LOOK.equals(input.getDataTypeHandle())) {
                    // A creature worn as a look: its catalogue card, since the slot's own text means an item.
                    var card = new EnumValues.Creature();
                    card.selected((String) v.value);
                    create(card, x + LANE_W - 140, vy);
                    wire(card.out(RuneNode.OUT), input);
                    return vy + height(card) + VALUE_GAP_Y;
                }
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
            case "form" -> new SpecialNodes.Form();
            default -> new SpecialNodes.Index();
        };
    }
}
