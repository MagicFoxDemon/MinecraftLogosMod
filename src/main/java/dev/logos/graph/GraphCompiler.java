package dev.logos.graph;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;

import dev.logos.graph.SpecialNodes.ContextValue;
import dev.logos.graph.SpecialNodes.Remember;
import dev.logos.graph.SpecialNodes.SpellRoot;
import dev.logos.graph.SpecialNodes.Trigger;
import dev.logos.lang.Compiler;
import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import dev.logos.lang.LogosError;
import dev.logos.lang.Motion;
import dev.logos.lang.Printer;
import dev.logos.lang.Rune;
import dev.logos.lang.SExpr;
import dev.logos.lang.SExpr.Atom;
import dev.logos.lang.SExpr.ListExpr;
import dev.logos.lang.Spell;
import dev.logos.lang.Type;

/**
 * Compiles a spell graph into Logos syntax, then through the regular {@link Compiler}.
 * <p>
 * Flow wires become statement order, event outputs become {@code (on-xxx …)} handlers, value
 * wires become nested expressions, and inline port constants become literals. Every generated
 * syntax node carries the id of the graph node it came from (in its "line"), so a compile error
 * points back at the node that caused it.
 */
public final class GraphCompiler {
    /** Either a spell (and its generated source) or an error, optionally attached to a node. */
    public record Result(@Nullable Spell spell, @Nullable String source, @Nullable String error, @Nullable LogosNode errorNode) {
        public boolean ok() {
            return spell != null;
        }
    }

    private static final class GraphError extends RuntimeException {
        final LogosNode node;

        GraphError(LogosNode node, String message) {
            super(message);
            this.node = node;
        }
    }

    private final List<LogosNode> ids = new ArrayList<>();
    private final Map<LogosNode, Integer> idOf = new HashMap<>();
    private final Set<LogosNode> flowPath = new HashSet<>();
    private final Set<LogosNode> valuePath = new HashSet<>();

    private GraphCompiler() {}

    public static Result compile(SpellGraph graph) {
        return new GraphCompiler().run(graph);
    }

    private Result run(SpellGraph graph) {
        List<SpellRoot> roots = new ArrayList<>();
        for (var n : graph.getNodes()) if (n instanceof SpellRoot r) roots.add(r);
        if (roots.isEmpty()) return new Result(null, null, "缺少 ☉ 术式（启式）节点：从节点库的「☉ 启式」中添加一个", null);
        if (roots.size() > 1) return new Result(null, null, "一个术式图只能有一个 ☉ 启式节点", roots.get(1));
        SpellRoot root = roots.getFirst();
        List<SExpr> top;
        String src;
        try {
            List<SExpr> items = new ArrayList<>();
            items.add(atom(root, "spell"));
            String name = Objects.requireNonNullElse(root.option(SpellRoot.NAME, String.class), "无名术式").replace("\"", "");
            Integer mana = root.option(SpellRoot.MANA, Integer.class);
            Trigger trigger = Objects.requireNonNullElse(root.option(SpellRoot.TRIGGER, Trigger.class), Trigger.CAST);
            items.add(atom(root, ":name"));
            items.add(new SExpr.Str(name.isBlank() ? "无名术式" : name, id(root), 1));
            items.add(atom(root, ":mana"));
            items.add(atom(root, String.valueOf(mana == null ? 40 : mana)));
            items.add(atom(root, ":trigger"));
            items.add(atom(root, trigger.id()));
            if (Boolean.TRUE.equals(root.option(SpellRoot.PLAYERS, Boolean.class))) {
                items.add(atom(root, ":players"));
                items.add(atom(root, "true"));
            }
            // Older graphs have no such option: immolation stays allowed, as it is by default.
            if (Boolean.FALSE.equals(root.option(SpellRoot.IMMOLATE, Boolean.class))) {
                items.add(atom(root, ":immolate"));
                items.add(atom(root, "false"));
            }
            SpecialNodes.IntentChoice intent = root.option(SpellRoot.INTENT, SpecialNodes.IntentChoice.class);
            if (intent != null && intent.intent() != null) {
                items.add(atom(root, ":intent"));
                items.add(atom(root, intent.intent().word));
            }
            List<SExpr> body = chain(root.out(SpellRoot.START));
            if (body.isEmpty()) throw new GraphError(root, "启式的 ▶ 施放 还没有连接任何行动");
            items.addAll(body);
            top = List.of(new ListExpr(items, id(root), 1));
            src = Printer.print(top);
        } catch (GraphError e) {
            return new Result(null, null, e.getMessage(), e.node);
        }
        try {
            return new Result(Compiler.compile(top, src), src, null, null);
        } catch (LogosError e) {
            LogosNode node = e.line >= 1 && e.line <= ids.size() ? ids.get(e.line - 1) : null;
            String where = node == null ? "" : "「" + node.title().trim() + "」：";
            return new Result(null, src, where + e.getMessage(), node);
        }
    }

    // ---- flow --------------------------------------------------------------------------------

    /** Statements reached from a flow output, in reading order when it fans out. */
    private List<SExpr> chain(@Nullable PortModel flowOut) {
        List<SExpr> out = new ArrayList<>();
        for (PortModel p : LogosNode.connected(flowOut)) {
            LogosNode n = LogosNode.of(p);
            if (n != null) statementsFrom(n, out);
        }
        return out;
    }

    private void statementsFrom(LogosNode n, List<SExpr> out) {
        if (!flowPath.add(n)) throw new GraphError(n, "流程形成了回路。需要重复请用 ♄ 重复 repeat，需要自我引用请用 ♄ 递归 recurse");
        try {
            if (n instanceof Remember r) {
                // let scopes over everything after it in the flow
                List<SExpr> let = new ArrayList<>();
                let.add(atom(r, "let"));
                let.add(atom(r, var(r)));
                SExpr value = expr(r.in(Remember.VALUE), r);
                if (value == null) throw new GraphError(r, "「记住」需要接入一个" + r.kind().type.label());
                let.add(value);
                let.addAll(chain(r.out(RuneNode.NEXT)));
                if (let.size() == 3) throw new GraphError(r, "「记住」之后没有任何行动使用它");
                out.add(new ListExpr(let, id(r), 1));
                return;
            }
            if (!(n instanceof RuneNode rn) || rn.rune().returns != Type.ACTION) {
                throw new GraphError(n, "只有行动节点可以接在流程 ▶ 上");
            }
            out.add(statement(rn));
            out.addAll(chain(rn.out(RuneNode.NEXT)));
        } finally {
            flowPath.remove(n);
        }
    }

    private SExpr statement(RuneNode n) {
        Rune r = n.rune();
        List<SExpr> items = new ArrayList<>();
        items.add(atom(n, r.name));
        if (r.name.equals("if")) {
            SExpr cond = expr(n.in("cond"), n);
            items.add(cond != null ? cond : atom(n, "false"));
            items.add(block(n, chain(n.out("then"))));
            List<SExpr> otherwise = chain(n.out("else"));
            if (!otherwise.isEmpty()) items.add(block(n, otherwise));
            return new ListExpr(items, id(n), 1);
        }
        arguments(n, items);
        for (Rune.EventSpec e : r.events.values()) {
            List<SExpr> handler = chain(n.out(RuneNode.event(e.name())));
            if (handler.isEmpty()) continue;
            List<SExpr> h = new ArrayList<>();
            h.add(atom(n, e.name()));
            h.addAll(handler);
            items.add(new ListExpr(h, id(n), 1));
        }
        if (r.body) items.addAll(chain(n.out(RuneNode.BODY)));
        return new ListExpr(items, id(n), 1);
    }

    private ListExpr block(LogosNode n, List<SExpr> stmts) {
        List<SExpr> items = new ArrayList<>();
        items.add(atom(n, "do"));
        items.addAll(stmts);
        return new ListExpr(items, id(n), 1);
    }

    /** Positional slots, keyword slots and flags of a rune node. */
    private void arguments(RuneNode n, List<SExpr> items) {
        Rune r = n.rune();
        boolean skipped = false;
        for (Rune.Param p : r.params) {
            if (p.type() == Type.ACTION) continue;
            if (p.type() == Type.DIMENSION) {
                String id = n.option(RuneNode.dim(p.name()), String.class);
                items.add(atom(n, "@" + (id == null || id.isBlank() ? Dimensions.DEFAULT : id.trim())));
                continue;
            }
            if (p.type() == Type.CREATURE) {
                String cr = n.option(RuneNode.option(p), String.class);
                items.add(atom(n, cr == null || cr.isBlank() ? "zombie" : cr.strip().toLowerCase(Locale.ROOT)));
                continue;
            }
            if (p.type() == Type.MOB_EFFECT) {
                String ef = n.option(RuneNode.option(p), String.class);
                items.add(atom(n, ef == null || ef.isBlank() ? "speed" : ef.strip().toLowerCase(Locale.ROOT)));
                continue;
            }
            if (p.type() == Type.ANCHOR) {
                String a = n.option(RuneNode.option(p), String.class);
                items.add(atom(n, "^" + (a == null || a.isBlank() ? "家" : a.strip())));
                continue;
            }
            if (p.type() == Type.KIND) {
                String k = n.option(RuneNode.option(p), String.class);
                items.add(atom(n, k == null || k.isBlank() ? TargetKinds.DEFAULT : k.trim()));
                continue;
            }
            if (p.rest()) {
                Integer count = n.option(RuneNode.COUNT, Integer.class);
                for (int i = 0; i < Math.max(1, count == null ? 2 : count); i++) {
                    SExpr e = expr(n.in(p.name() + i), n, p.type());
                    if (e == null) throw new GraphError(n, "缺少输入 " + p.type().label() + " " + p.name() + (i + 1));
                    items.add(e);
                }
                continue;
            }
            SExpr e = expr(n.in(p.name()), n, p.type());
            if (e == null) {
                if (p.defaultSrc() == null) throw new GraphError(n, "缺少输入 " + (p.broadcast() ? "○实体/◎实体群" : p.type().label()) + " " + p.name());
                skipped = true;
                continue;
            }
            if (skipped) throw new GraphError(n, "可选输入 " + p.name() + " 之前的可选输入也必须给出");
            items.add(e);
        }
        for (Rune.Kw k : r.keywords.values()) {
            if (k.flag()) {
                if (Boolean.TRUE.equals(n.option(RuneNode.flag(k.name()), Boolean.class))) items.add(atom(n, ":" + k.name()));
                continue;
            }
            PortModel port = n.in(RuneNode.kw(k.name()));
            SExpr e = expr(port, n, k.type());
            if (e == null) continue;
            // An unwired constant equal to the keyword's default adds nothing; leave it out to keep the source readable.
            if (port != null && !port.isConnected() && e instanceof Atom a && k.defaultSrc() != null && sameLiteral(a.text(), k.defaultSrc())) continue;
            items.add(atom(n, ":" + k.name()));
            items.add(e);
        }
    }

    // ---- values ------------------------------------------------------------------------------

    /** The expression feeding an input: a wired value node, or the port's inline constant. */
    private @Nullable SExpr expr(@Nullable PortModel input, LogosNode owner) {
        return expr(input, owner, null);
    }

    /** {@code want} distinguishes free text (¶, written as a "string") from symbol-like constants. */
    private @Nullable SExpr expr(@Nullable PortModel input, LogosNode owner, @Nullable Type want) {
        if (input == null) return null;
        List<PortModel> sources = input.getConnectedPorts();
        if (!sources.isEmpty()) {
            LogosNode src = LogosNode.of(sources.getFirst());
            if (src == null) return null;
            return switch (src) {
                case ContextValue cv -> atom(cv, cv.contextName());
                case SpecialNodes.AnchorValue av -> atom(av, "^" + av.anchor());
                case Remember r -> atom(r, var(r));
                case RuneNode rn -> valueCall(rn);
                default -> throw new GraphError(src, "这个节点不能作为值使用");
            };
        }
        var constant = input.getEmbeddedValue();
        Object v = constant == null ? null : constant.getValue();
        return switch (v) {
            case null -> null;
            case Float f -> atom(owner, num(f));
            case Double d -> atom(owner, num(d.floatValue()));
            case Integer i -> atom(owner, String.valueOf(i));
            case Boolean b -> atom(owner, b.toString());
            case Element e -> atom(owner, e.name().toLowerCase());
            case Motion m -> atom(owner, m.name().toLowerCase());
            case ConstructShape sh -> atom(owner, sh.word());
            case String s when want == Type.LOOK -> s.isBlank() ? null : atom(owner, "&" + s.trim().replaceFirst("^&", "").toLowerCase(Locale.ROOT));
            case String s when want == Type.PARTICLE -> s.isBlank() ? null : atom(owner, "%" + s.trim().replaceFirst("^%", "").toLowerCase(Locale.ROOT));
            case String s when want == Type.TEXT || want == Type.FILTER -> s.isBlank() ? null : new SExpr.Str(s.trim().replace("\"", ""), id(owner), 1);
            case String s -> s.isBlank() ? null : atom(owner, s.trim());
            default -> null;
        };
    }

    private SExpr valueCall(RuneNode n) {
        if (!valuePath.add(n)) throw new GraphError(n, "数值连线形成了回路");
        try {
            List<SExpr> items = new ArrayList<>();
            items.add(atom(n, n.rune().name));
            arguments(n, items);
            return items.size() == 1 && !n.rune().hasRequiredParams() ? atom(n, n.rune().name) : new ListExpr(items, id(n), 1);
        } finally {
            valuePath.remove(n);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private int id(LogosNode n) {
        return idOf.computeIfAbsent(n, k -> {
            ids.add(k);
            return ids.size();
        });
    }

    private Atom atom(LogosNode n, String text) {
        return new Atom(text, id(n), 1);
    }

    private String var(Remember r) {
        return "$m" + id(r);
    }

    static String num(float f) {
        return new BigDecimal(Float.toString(f)).stripTrailingZeros().toPlainString();
    }

    private static boolean sameLiteral(String a, String b) {
        if (a.equals(b)) return true;
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
