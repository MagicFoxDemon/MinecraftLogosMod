package dev.logos.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** A typed AST node. Spells are compiled into trees of these and interpreted directly. */
public final class Node {
    public enum Kind { LIT, CTX, VAR, CALL }

    public final Kind kind;
    /** Fixed by the rune, except one returning ✱ (choose), which takes the type of the values it chooses among. */
    public Type type;
    public final Object value;
    public final Rune rune;
    public final List<Node> args = new ArrayList<>();
    public final Map<String, Node> kw = new LinkedHashMap<>();
    public final Set<String> flags = new HashSet<>();
    /** Keyword slots filled from defaults rather than written by the player. */
    public final Set<String> defaulted = new HashSet<>();
    public final List<Node> body = new ArrayList<>();
    public final Map<String, List<Node>> events = new LinkedHashMap<>();
    public final int line;
    public final int col;
    /** Compiler-attached data, e.g. the proof facts for {@code execute}. */
    public Object meta;
    /** For forms: the element inferred from their payload, used to derive visuals. */
    public Element element = Element.ARCANE;

    private Node(Kind kind, Type type, Object value, Rune rune, int line, int col) {
        this.kind = kind;
        this.type = type;
        this.value = value;
        this.rune = rune;
        this.line = line;
        this.col = col;
    }

    public static Node lit(Type type, Object value, int line, int col) {
        return new Node(Kind.LIT, type, value, null, line, col);
    }

    public static Node ctx(String name, Type type, int line, int col) {
        return new Node(Kind.CTX, type, name, null, line, col);
    }

    public static Node var(String name, Type type, int line, int col) {
        return new Node(Kind.VAR, type, name, null, line, col);
    }

    public static Node call(Rune rune, Type type, int line, int col) {
        return new Node(Kind.CALL, type, null, rune, line, col);
    }

    public Node arg(int i) {
        return i < args.size() ? args.get(i) : null;
    }

    /** Canonical identity for proof facts; only stable references (context names, variables) qualify. */
    public String identity() {
        return switch (kind) {
            case CTX, VAR -> (String) value;
            default -> null;
        };
    }

    /** Compact source-like rendering used in messages. */
    public String show() {
        return switch (kind) {
            case LIT -> value instanceof Double d ? fmt(d) : type == Type.DIMENSION ? "@" + value : type == Type.LOOK ? "&" + value : type == Type.PARTICLE ? "%" + value : type == Type.ANCHOR ? "^" + value : (type == Type.CREATURE || type == Type.MOB_EFFECT) ? String.valueOf(value).replace("minecraft:", "") : value instanceof TargetKind k ? k.word : value instanceof String s ? '"' + s + '"' : String.valueOf(value).toLowerCase();
            case CTX, VAR -> (String) value;
            case CALL -> {
                StringBuilder sb = new StringBuilder("(").append(rune.name);
                for (Node a : args) sb.append(' ').append(a.show());
                for (var e : kw.entrySet()) {
                    if (defaulted.contains(e.getKey())) continue;
                    Rune.Kw spec = rune.keywords.get(e.getKey());
                    sb.append(" :").append(e.getKey()).append(' ').append(spec != null && spec.choice() ? e.getValue().value : e.getValue().show());
                }
                for (String f : flags) sb.append(" :").append(f);
                if (!body.isEmpty() || !events.isEmpty()) sb.append(" …");
                yield sb.append(')').toString();
            }
        };
    }

    public static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.2f", d);
    }
}
