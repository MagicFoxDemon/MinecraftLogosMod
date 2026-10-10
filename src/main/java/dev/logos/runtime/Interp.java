package dev.logos.runtime;

import java.util.List;

import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.lang.Node;
import dev.logos.lang.Status;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Tree-walking interpreter over the typed AST. */
public final class Interp {
    private Interp() {}

    public static Object eval(Node n, Ctx c) {
        if (n == null) return null;
        return switch (n.kind) {
            case LIT -> n.type == dev.logos.lang.Type.ANCHOR ? anchor((String) n.value, c) : n.value;
            case CTX -> c.lookup((String) n.value);
            case VAR -> c.vars().get((String) n.value);
            case CALL -> c.inst().step() ? n.rune.exec.run(n, c) : null;
        };
    }

    /** ^name read as a place: the caster's anchor of that name. A missing anchor extinguishes the spell. */
    private static Object anchor(String name, Ctx c) {
        Place p = c.inst().caster instanceof net.minecraft.server.level.ServerPlayer sp ? Anchors.resolve(sp, name) : null;
        if (p == null) c.inst().terminate("锚点 ^" + name + " 不存在（拿着锚钉右键方块立下，或用 anchor 符文）");
        return p;
    }

    public static void run(List<Node> stmts, Ctx c) {
        for (Node s : stmts) {
            if (c.inst().dead) return;
            c.inst().touch(c.level(), c.here());
            eval(s, c);
        }
    }

    /** Runs the handler for {@code event} on node {@code n}, if the program wrote one. */
    public static void fire(Node n, String event, Ctx c) {
        List<Node> h = n.events.get(event);
        if (h != null && !c.inst().dead) run(h, c);
    }

    public static double num(Node n, Ctx c) {
        return eval(n, c) instanceof Double d && Double.isFinite(d) ? d : 0;
    }

    public static boolean bool(Node n, Ctx c) {
        return eval(n, c) instanceof Boolean b && b;
    }

    /** A position or direction as plain coordinates (a position's world is dropped). */
    public static Vec3 vec(Node n, Ctx c) {
        return switch (eval(n, c)) {
            case Place p -> p.pos();
            case Vec3 v -> v;
            case null, default -> c.here();
        };
    }

    /** A position with its world; plain coordinates are taken to be in the current world. */
    public static Place place(Node n, Ctx c) {
        return switch (eval(n, c)) {
            case Place p -> p;
            case Vec3 v -> new Place(c.level(), v);
            case null, default -> c.place();
        };
    }

    /** A ▦ block set: a single position counts as a set of one. */
    @SuppressWarnings("unchecked")
    public static List<Place> places(Node n, Ctx c) {
        return switch (eval(n, c)) {
            case Place p -> List.of(p);
            case Vec3 v -> List.of(new Place(c.level(), v));
            case List<?> l -> (List<Place>) l;
            case null, default -> List.of();
        };
    }

    public static String text(Node n, Ctx c) {
        return eval(n, c) instanceof String s ? s : "";
    }

    public static Entity entity(Node n, Ctx c) {
        Object v = eval(n, c);
        if (v instanceof Entity e) return e;
        if (v instanceof List<?> l && !l.isEmpty() && l.getFirst() instanceof Entity e) return e;
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Entity> entities(Node n, Ctx c) {
        Object v = eval(n, c);
        if (v instanceof Entity e) return List.of(e);
        if (v instanceof List<?> l) return (List<Entity>) l;
        return List.of();
    }

    public static Status status(Node n, Ctx c) {
        return (Status) eval(n, c);
    }

    public static Element element(Node n, Ctx c) {
        return eval(n, c) instanceof Element e ? e : Element.PHYSICAL;
    }

    public static Motion motion(Node n, Ctx c) {
        return eval(n, c) instanceof Motion m ? m : Motion.FORWARD;
    }

    /** A ⊙ filter; an absent slot admits everything, a plain string is an id or #tag. */
    public static Filter filter(Node n, Ctx c) {
        if (n == null) return Filter.ANY;
        return switch (eval(n, c)) {
            case Filter f -> f;
            case String s -> Filter.idOrTag(s);
            case null, default -> Filter.ANY;
        };
    }

    public static Node kw(Node n, String name) {
        return n.kw.get(name);
    }
}
