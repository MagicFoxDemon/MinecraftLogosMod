package dev.logos.lang;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import dev.logos.LogosConfig;
import dev.logos.lang.SExpr.Atom;
import dev.logos.lang.SExpr.ListExpr;

/**
 * Turns S-expressions into a typed AST. Responsibilities: slot type checking, context
 * availability ({@code target} only exists where an event provides one), stability accounting,
 * proof extraction for {@code execute}, and element inference for visuals.
 */
public final class Compiler {
    public static final double DEFAULT_MANA = 40;

    public static final Map<String, Double> MAGNITUDES = new LinkedHashMap<>();
    public static final Map<String, Type> CONTEXT = new LinkedHashMap<>();
    /** Whether an item id exists. The game installs a registry lookup; plain unit tests accept any well-formed id. */
    public static Predicate<String> itemExists = id -> true;
    /** Whether a particle type exists and can be played without extra options; installed by the game like {@link #itemExists}. */
    public static Predicate<String> particleExists = id -> true;
    /** Whether a creature (living entity type) exists; installed by the game like {@link #itemExists}. */
    public static Predicate<String> creatureExists = id -> true;
    public static Predicate<String> effectExists = id -> true;

    static {
        MAGNITUDES.put("minor", 2.0);
        MAGNITUDES.put("standard", 5.0);
        MAGNITUDES.put("major", 10.0);
        MAGNITUDES.put("grand", 20.0);
        CONTEXT.put("caster", Type.ENTITY);
        CONTEXT.put("target", Type.ENTITY);
        CONTEXT.put("here", Type.POS);
        CONTEXT.put("aim", Type.DIR);
        CONTEXT.put("up", Type.DIR);
        CONTEXT.put("down", Type.DIR);
        CONTEXT.put("i", Type.NUM);
        CONTEXT.put("self", Type.ENTITY);
    }

    /** Lexical scope: which context values exist here and what has been proven. */
    record Scope(boolean hasTarget, Map<String, Type> vars, List<Fact> facts, int depth, boolean hasSelf) {
        Scope enter(boolean bindsTarget) {
            List<Fact> f = bindsTarget ? facts.stream().filter(x -> !x.key().equals("target")).toList() : facts;
            return new Scope(hasTarget || bindsTarget, vars, f, depth + 1, hasSelf);
        }

        Scope withVar(String name, Type type) {
            Map<String, Type> v = new HashMap<>(vars);
            v.put(name, type);
            return new Scope(hasTarget, v, facts.stream().filter(x -> !x.key().equals(name)).toList(), depth, hasSelf);
        }

        Scope withFacts(List<Fact> more) {
            List<Fact> f = new ArrayList<>(facts);
            f.addAll(more);
            return new Scope(hasTarget, vars, f, depth, hasSelf);
        }
    }

    private double stability;
    private boolean recursive;
    private boolean affectsPlayers;
    private boolean immolate = true;
    private final List<String> notes = new ArrayList<>();

    public static Spell compile(String src) {
        return new Compiler().compileSpell(Reader.read(src), src);
    }

    /** Compiles already-parsed syntax (e.g. generated from a node graph); {@code src} is kept as the spell's source. */
    public static Spell compile(List<SExpr> top, String src) {
        return new Compiler().compileSpell(top, src);
    }

    private Spell compileSpell(List<SExpr> top, String src) {
        if (top.isEmpty()) throw new LogosError("空白的书页：没有任何符文", 0, 0);
        String name = "无名术式";
        String trigger = "cast";
        double mana = DEFAULT_MANA;
        List<Intent> declared = new ArrayList<>();
        List<SExpr> bodyRaw = top;
        if (top.size() == 1 && top.getFirst() instanceof ListExpr l && "spell".equals(l.head())) {
            bodyRaw = new ArrayList<>();
            List<SExpr> items = l.items();
            for (int i = 1; i < items.size(); i++) {
                SExpr it = items.get(i);
                if (it instanceof Atom a && a.text().startsWith(":")) {
                    if (i + 1 >= items.size()) throw err(a, "关键字 " + a.text() + " 后缺少值");
                    SExpr v = items.get(++i);
                    switch (a.text()) {
                        case ":name" -> name = v instanceof SExpr.Str s ? s.text() : v.toString();
                        case ":mana" -> {
                            Double m = v instanceof Atom va ? number(va.text()) : null;
                            if (m == null || m < 1 || m > LogosConfig.maxInjectedMana()) {
                                throw err(v, ":mana 需要 1~" + LogosConfig.maxInjectedMana() + " 之间的数字（注入的魔力，上限见配置 maxInjectedMana）");
                            }
                            mana = m;
                        }
                        case ":trigger" -> {
                            trigger = v.toString();
                            if (!Spell.TRIGGERS.contains(trigger)) throw err(v, "未知的启式 '" + trigger + "'，可用: " + Spell.TRIGGERS);
                        }
                        case ":players" -> {
                            if (!(v instanceof Atom pa) || !(pa.text().equals("true") || pa.text().equals("false"))) {
                                throw err(v, ":players 需要 true 或 false（是否允许法术作用于其他玩家）");
                            }
                            affectsPlayers = pa.text().equals("true");
                        }
                        case ":immolate" -> {
                            if (!(v instanceof Atom ia) || !(ia.text().equals("true") || ia.text().equals("false"))) {
                                throw err(v, ":immolate 需要 true 或 false（持焚式法杖时，是否允许焚毁载页来突破承载上限）");
                            }
                            immolate = ia.text().equals("true");
                        }
                        case ":intent" -> {
                            // One intent, or several joined by +, the first filing the spell: heal, area+control
                            for (String w : v.toString().split("[+]")) {
                                Intent in = Intent.parse(w.strip());
                                if (in == null) throw err(v, "未知的意图 '" + w + "'，可用: " + Intent.words() + "（几个用 + 连起来，第一个为主）");
                                if (!declared.contains(in)) declared.add(in);
                            }
                        }
                        default -> throw err(a, "spell 没有 " + a.text() + " 插槽，可用: :name :mana :trigger :players :immolate :intent");
                    }
                } else {
                    bodyRaw.add(it);
                }
            }
            if (bodyRaw.isEmpty()) throw err(l, "术式没有任何行动");
        }
        Scope root = new Scope(!trigger.equals("cast"), Map.of(), List.of(), 0, false);
        List<Node> body = statements(bodyRaw, root);
        if (recursive) notes.add("含有递归 (recurse)：每层递归都会重新支付魔力，最多 " + LogosConfig.maxRecursion() + " 层（配置 maxRecursion）");
        return new Spell(name, trigger, affectsPlayers, immolate, mana, Math.round(stability * 10) / 10.0, body, notes, src, SpellProfile.of(body, declared));
    }

    private List<Node> statements(List<SExpr> raw, Scope s) {
        List<Node> out = new ArrayList<>();
        for (SExpr e : raw) {
            Node n = expr(e, s);
            if (n.type != Type.ACTION) {
                throw err(e, "这里需要一个 " + Type.ACTION.label() + "，但 " + n.show() + " 是 " + n.type.label() + "（它不会改变世界）");
            }
            out.add(n);
        }
        return out;
    }

    private Node expr(SExpr e, Scope s) {
        return switch (e) {
            case SExpr.Str st -> Node.lit(Type.TEXT, st.text(), st.line(), st.col());
            case Atom a -> atom(a, s);
            case ListExpr l -> list(l, s);
        };
    }

    private Node atom(Atom a, Scope s) {
        String t = a.text();
        int l = a.line(), c = a.col();
        if (t.startsWith(":")) throw err(a, "关键字 " + t + " 必须写在某个符文的括号里面");
        Double num = number(t);
        if (num != null) return Node.lit(Type.NUM, num, l, c);
        if (MAGNITUDES.containsKey(t)) return Node.lit(Type.NUM, MAGNITUDES.get(t), l, c);
        if (t.equals("true") || t.equals("false")) return Node.lit(Type.BOOL, t.equals("true"), l, c);
        if (CONTEXT.containsKey(t)) {
            if (t.equals("target") && !s.hasTarget()) {
                throw err(a, "这里没有 target：只有在 on-hit / on-enter / on-kill / each 内部，或 受伤/攻击/击杀 启式中，才存在被选中的对象");
            }
            if (t.equals("self") && !s.hasSelf()) {
                throw err(a, "这里没有 self：只有在炼成体 (construct) 的事件里，self 才指那个炼成体");
            }
            return Node.ctx(t, CONTEXT.get(t), l, c);
        }
        if (t.startsWith("$")) {
            Type vt = s.vars().get(t);
            if (vt == null) throw err(a, "变量 " + t + " 尚未定义，请先用 (let " + t + " 值 …)");
            return Node.var(t, vt, l, c);
        }
        Element el = Element.parse(t);
        if (el != null) return Node.lit(Type.ELEMENT, el, l, c);
        Motion m = Motion.parse(t);
        if (m != null) {
            stability += m.stability;
            return Node.lit(Type.MOTION, m, l, c);
        }
        ConstructShape shape = ConstructShape.parse(t);
        if (shape != null) return Node.lit(Type.SHAPE, shape, l, c);
        Weather weather = Weather.parse(t);
        if (weather != null) return Node.lit(Type.WEATHER, weather, l, c);
        Status st = Status.parse(t);
        if (st != null) return Node.lit(Type.STATUS, st, l, c);
        if (t.startsWith("&")) return look(a);
        if (t.startsWith("%")) return particle(a);
        if (t.startsWith("^")) return anchorName(a);
        if (t.startsWith("@") && t.length() > 1) {
            String dim = t.substring(1);
            return Node.lit(Type.DIMENSION, dim.contains(":") ? dim : "minecraft:" + dim, l, c);
        }
        Rune r = Runes.get(t);
        if (r != null) {
            if (r.hasRequiredParams()) throw err(a, "符文 " + t + " 需要参数: " + r.signature());
            return call(r, List.of(), a, s);
        }
        throw err(a, "未知的词 '" + t + "'" + suggest(t));
    }

    /** {@code &mace} / {@code &minecraft:fire_charge}: an item whose model a spell body wears. */
    private static Node look(Atom a) {
        String id = registryId(a.text().substring(1));
        if (id == null) {
            throw err(a, "外观 " + a.text() + " 不是物品 id，写法如 &mace、&fire_charge、&模组id:物品名");
        }
        if (!itemExists.test(id)) throw err(a, "没有叫 " + id + " 的物品：外观只能借用游戏里存在的物品");
        return Node.lit(Type.LOOK, id, a.line(), a.col());
    }

    /** {@code %portal} / {@code %minecraft:gust_emitter_large}: a particle type, found by its registry name. */
    private static Node particle(Atom a) {
        String id = registryId(a.text().substring(1));
        if (id == null) throw err(a, "粒子 " + a.text() + " 不是注册名，写法如 %portal、%gust_emitter_large、%模组id:粒子名");
        if (!particleExists.test(id)) {
            throw err(a, "没有叫 " + id + " 的粒子，或者它需要额外参数（如 dust 的颜色、block 的方块），目前只支持无参数的粒子");
        }
        return Node.lit(Type.PARTICLE, id, a.line(), a.col());
    }

    /** {@code ^家}: an anchor by name. Whether it exists is only known when the spell runs. */
    private static Node anchorName(Atom a) {
        String name = a.text().substring(1);
        if (name.isEmpty() || name.length() > 16 || !name.matches("[^\\s()\\[\\]\";^]+")) {
            throw err(a, "锚点名 " + a.text() + " 需要 1~16 个字，不能有空格或括号，写法如 ^家、^二楼");
        }
        return Node.lit(Type.ANCHOR, name, a.line(), a.col());
    }

    /** The one creature of this mod that summon knows by its bare name. */
    public static final String MANA_LIFEFORM = "logos:mana_lifeform";

    private static Node creature(SExpr e) {
        String id = e instanceof Atom a ? registryId(a.text()) : null;
        // The mana lifeform is this mod's own creature: written bare, it needs no logos: prefix.
        if ("minecraft:mana_lifeform".equals(id)) id = MANA_LIFEFORM;
        if (id == null) throw err(e, "summon 需要一个生物名，如 zombie、skeleton、minecraft:wither、模组id:生物名");
        if (!creatureExists.test(id)) throw err(e, "没有叫 " + id + " 的生物");
        return Node.lit(Type.CREATURE, id, e.line(), e.col());
    }

    private static Node effect(SExpr e) {
        String id = e instanceof Atom a ? registryId(a.text()) : null;
        if (id == null) throw err(e, "需要一个药水效果的注册名，如 speed、minecraft:regeneration、模组id:效果名");
        if (!effectExists.test(id)) throw err(e, "没有叫 " + id + " 的药水效果");
        return Node.lit(Type.MOB_EFFECT, id, e.line(), e.col());
    }

    private static Node kind(SExpr e) {
        TargetKind k = e instanceof Atom a ? TargetKind.parse(a.text()) : null;
        if (k == null) throw err(e, "kind 需要一个种类: " + TargetKind.words());
        return Node.lit(Type.KIND, k, e.line(), e.col());
    }

    /** {@code name} or {@code ns:name} as a full registry id, or null if malformed. */
    private static String registryId(String s) {
        String id = s.contains(":") ? s : "minecraft:" + s;
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? id : null;
    }

    private Node list(ListExpr l, Scope s) {
        String head = l.head();
        if (head == null) throw err(l, "括号中的第一个词必须是符文名");
        if (head.startsWith("on-")) {
            List<String> owners = Runes.all().stream().filter(r -> r.events.containsKey(head)).map(r -> r.name).toList();
            throw err(l, "事件 (" + head + " …) 只能写在会产生它的符文内部" + (owners.isEmpty() ? "" : "，例如 " + owners));
        }
        return switch (head) {
            case "let" -> let(l, s);
            case "if" -> ifNode(l, s);
            case "spell" -> throw err(l, "spell 只能写在书的最外层");
            default -> {
                Rune r = Runes.get(head);
                if (r == null) throw err(l, "未知的符文 '" + head + "'" + suggest(head));
                yield call(r, l.items().subList(1, l.items().size()), l, s);
            }
        };
    }

    private Node call(Rune r, List<SExpr> items, SExpr at, Scope s) {
        Node n = Node.call(r, r.returns, at.line(), at.col());
        boolean hasRest = r.params.stream().anyMatch(Rune.Param::rest);
        List<SExpr> pos = new ArrayList<>();
        List<SExpr> bodyRaw = new ArrayList<>();
        Map<String, SExpr> kwRaw = new LinkedHashMap<>();
        Map<String, List<SExpr>> evRaw = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            SExpr it = items.get(i);
            if (it instanceof Atom a && a.text().startsWith(":") && a.text().length() > 1) {
                String k = a.text().substring(1);
                Rune.Kw kw = r.keywords.get(k);
                if (kw == null) {
                    throw err(a, r.name + " 没有 :" + k + " 插槽" + (r.keywords.isEmpty() ? "" : "，可用: " + r.keywords.keySet().stream().map(x -> ":" + x).toList()));
                }
                if (kw.flag()) {
                    n.flags.add(k);
                    continue;
                }
                if (i + 1 >= items.size()) throw err(a, ":" + k + " 后缺少值");
                kwRaw.put(k, items.get(++i));
            } else if (it instanceof ListExpr le && le.head() != null && r.events.containsKey(le.head())) {
                evRaw.computeIfAbsent(le.head(), x -> new ArrayList<>()).addAll(le.items().subList(1, le.items().size()));
            } else if (pos.size() < r.params.size() || hasRest) {
                if (r.body && pos.size() >= r.params.size() - (int) r.params.stream().filter(p -> p.defaultSrc() != null).count()
                        && it instanceof ListExpr le2 && isAction(le2)) {
                    bodyRaw.add(it);
                } else {
                    pos.add(it);
                }
            } else if (r.body) {
                bodyRaw.add(it);
            } else {
                throw err(it, r.name + " 的插槽已满，多出了 " + it + "。写法: " + r.signature());
            }
        }

        for (int i = 0; i < r.params.size(); i++) {
            Rune.Param p = r.params.get(i);
            if (p.rest()) {
                if (pos.size() <= i) throw err(at, r.name + " 至少需要一个 " + p.type().label() + "。写法: " + r.signature());
                for (int j = i; j < pos.size(); j++) n.args.add(check(r, p.name(), p.type(), p.broadcast(), expr(pos.get(j), s), pos.get(j)));
                break;
            }
            Node arg;
            if (i < pos.size() && p.type() == Type.KIND) {
                // Kind words (enemies, items…) are also selector names; inside (kind …) they name a kind.
                arg = kind(pos.get(i));
            } else if (i < pos.size() && p.type() == Type.CREATURE) {
                // Creature names (zombie, skeleton…) are written bare inside (summon …).
                arg = creature(pos.get(i));
            } else if (i < pos.size() && p.type() == Type.MOB_EFFECT) {
                // Effect ids (speed, minecraft:regeneration…) are written bare, like creatures.
                arg = effect(pos.get(i));
            } else if (i < pos.size()) {
                arg = expr(pos.get(i), s);
            } else if (p.defaultSrc() != null) {
                arg = expr(Reader.read(p.defaultSrc()).getFirst(), s);
            } else {
                throw err(at, r.name + " 缺少插槽 " + p.type().label() + " " + p.name() + "。写法: " + r.signature());
            }
            n.args.add(check(r, p.name(), p.type(), p.broadcast(), arg, i < pos.size() ? pos.get(i) : at));
        }
        for (Rune.Kw k : r.keywords.values()) {
            if (k.flag()) continue;
            SExpr raw = kwRaw.get(k.name());
            if (k.choice()) {
                String word = raw == null ? k.defaultSrc() : raw instanceof Atom a ? a.text() : "";
                if (!k.choices().contains(word)) throw err(raw, r.name + " 的 :" + k.name() + " 只能是 " + String.join(" / ", k.choices()));
                n.kw.put(k.name(), Node.lit(Type.TEXT, word, (raw != null ? raw : at).line(), (raw != null ? raw : at).col()));
                if (raw == null) n.defaulted.add(k.name());
                continue;
            }
            Node v = raw != null ? expr(raw, s) : k.defaultSrc() != null ? expr(Reader.read(k.defaultSrc()).getFirst(), s) : null;
            if (v == null) continue;
            n.kw.put(k.name(), check(r, ":" + k.name(), k.type(), false, v, raw != null ? raw : at));
            if (raw == null) n.defaulted.add(k.name());
        }
        stability += r.stability * (1 + 0.2 * s.depth());
        for (var e : evRaw.entrySet()) {
            Rune.EventSpec spec = r.events.get(e.getKey());
            Scope inner = s.enter(spec.bindsTarget());
            // Inside a construct's events, self names that construct.
            if (r.name.equals("construct")) inner = new Scope(inner.hasTarget(), inner.vars(), inner.facts(), inner.depth(), true);
            n.events.put(e.getKey(), statements(e.getValue(), inner));
        }
        if (!bodyRaw.isEmpty()) n.body.addAll(statements(bodyRaw, r.bodyBindsTarget ? s.enter(true) : s));
        postCheck(n, s, at);
        return n;
    }

    /** A list whose head is an ACTION-returning rune (or special form) is a body statement, not an argument. */
    private static boolean isAction(ListExpr l) {
        String h = l.head();
        if (h == null) return false;
        if (h.equals("let") || h.equals("if")) return true;
        Rune r = Runes.get(h);
        return r != null && r.returns == Type.ACTION;
    }

    private Node check(Rune r, String slot, Type want, boolean broadcast, Node got, SExpr at) {
        if (!want.accepts(got.type, broadcast)) {
            String shape = broadcast ? want.broadcastLabel() : want.label();
            throw err(at, "插槽形状不符：" + r.name + " 的 " + slot + " 需要 " + shape + "，但接入的 " + got.show() + " 是 " + got.type.label());
        }
        return got;
    }

    private Node let(ListExpr l, Scope s) {
        List<SExpr> items = l.items();
        if (items.size() < 4 || !(items.get(1) instanceof Atom name) || !name.text().startsWith("$")) {
            throw err(l, "let 的写法: (let $名字 值 行动…)");
        }
        Node value = expr(items.get(2), s);
        if (value.type == Type.ACTION) throw err(items.get(2), "let 只能记住值，不能记住行动");
        Rune r = Runes.get("let");
        Node n = Node.call(r, Type.ACTION, l.line(), l.col());
        n.args.add(Node.lit(Type.TEXT, name.text(), name.line(), name.col()));
        n.args.add(value);
        n.body.addAll(statements(items.subList(3, items.size()), s.withVar(name.text(), value.type)));
        stability += r.stability;
        return n;
    }

    private Node ifNode(ListExpr l, Scope s) {
        List<SExpr> items = l.items();
        if (items.size() < 3 || items.size() > 4) throw err(l, "if 的写法: (if 命题 行动 [否则的行动])，多个行动请用 (do …) 包起来");
        Rune r = Runes.get("if");
        Node cond = check(r, "命题", Type.BOOL, false, expr(items.get(1), s), items.get(1));
        List<Fact> facts = new ArrayList<>();
        extractFacts(cond, facts);
        Node n = Node.call(r, Type.ACTION, l.line(), l.col());
        n.args.add(cond);
        n.args.add(statements(List.of(items.get(2)), s.withFacts(facts)).getFirst());
        if (items.size() == 4) n.args.add(statements(List.of(items.get(3)), s).getFirst());
        stability += r.stability * (1 + 0.2 * s.depth());
        return n;
    }

    // ---- Lex Proof ---------------------------------------------------------------------------

    private static void extractFacts(Node c, List<Fact> out) {
        if (c.kind != Node.Kind.CALL) return;
        switch (c.rune.name) {
            case "and" -> c.args.forEach(a -> extractFacts(a, out));
            case "<", "<=" -> {
                upper(c.arg(0), c.arg(1), out);
                lower(c.arg(1), c.arg(0), out);
            }
            case ">", ">=" -> {
                upper(c.arg(1), c.arg(0), out);
                lower(c.arg(0), c.arg(1), out);
            }
            case "has" -> {
                String key = c.arg(0).identity();
                if (key != null && c.arg(1).kind == Node.Kind.LIT) out.add(new Fact(key, Fact.Kind.HAS, 0, (Status) c.arg(1).value));
            }
            default -> {}
        }
    }

    /** {@code x ≤ k} where k is a constant. */
    private static void upper(Node x, Node k, List<Fact> out) {
        if (k.kind != Node.Kind.LIT || !(k.value instanceof Double v) || x.kind != Node.Kind.CALL) return;
        String key = x.arg(0) == null ? null : x.arg(0).identity();
        if (key == null) return;
        if (x.rune.name.equals("hp-ratio")) out.add(new Fact(key, Fact.Kind.HP_RATIO_MAX, v, null));
        if (x.rune.name.equals("hp")) out.add(new Fact(key, Fact.Kind.HP_MAX, v, null));
    }

    /** {@code x ≥ k} where k is a constant. */
    private static void lower(Node x, Node k, List<Fact> out) {
        if (k.kind != Node.Kind.LIT || !(k.value instanceof Double v) || x.kind != Node.Kind.CALL) return;
        if (x.rune.name.equals("stacks") && x.arg(0).identity() != null && x.arg(1).kind == Node.Kind.LIT) {
            out.add(new Fact(x.arg(0).identity(), Fact.Kind.STACKS_MIN, v, (Status) x.arg(1).value));
        }
    }

    private void postCheck(Node n, Scope s, SExpr at) {
        switch (n.rune.name) {
            case "mark", "unmark" -> {
                Node st = n.arg(1);
                if (st.kind == Node.Kind.LIT && !((Status) st.value).isMark()) {
                    throw err(at, n.rune.name + " 只能操作自定义印记 (#名字)；燃烧请用 ignite，冻结请用 freeze");
                }
            }
            case "repeat" -> {
                if (n.arg(0).kind == Node.Kind.LIT && (Double) n.arg(0).value > LogosConfig.maxRepeat()) {
                    throw err(at, "repeat 最多 " + LogosConfig.maxRepeat() + " 次（配置 maxRepeat）");
                }
            }
            case "execute" -> {
                String key = n.arg(0).identity();
                List<Fact> proof = key == null ? List.of() : s.facts().stream().filter(f -> f.key().equals(key)).toList();
                n.meta = proof;
                if (proof.isEmpty()) {
                    notes.add("execute " + n.arg(0).show() + "：未给出任何证明 → 代价 = 4 × 最大生命值");
                } else {
                    notes.add("execute " + n.arg(0).show() + "：已证明 " + proof.stream().map(Fact::describe).toList() + " → " + ExecuteCost.formula(proof));
                }
            }
            case "recurse" -> recursive = true;
            case "players" -> {
                if (!affectsPlayers) {
                    throw err(at, "这个术式不作用于玩家：在启式中写 :players true（节点：☉ 术式 → 影响玩家）才能选择玩家");
                }
            }
            case "kind" -> {
                if (n.arg(0).value == TargetKind.PLAYERS && !affectsPlayers) {
                    throw err(at, "这个术式不作用于玩家：在启式中写 :players true（节点：☉ 术式 → 影响玩家）才能筛选玩家");
                }
            }
            case "anchor", "unanchor" -> {
                if (n.arg(0).kind != Node.Kind.LIT) throw err(at, n.rune.name + " 的锚点名要直接写成 ^名字");
            }
            case "blocks" -> blockFilter(n.kw.get("filter"), at);
            case "block-is" -> blockFilter(n.arg(1), at);
            default -> {}
        }
        if (n.rune.category == Category.FORM) n.element = inferElement(n);
    }

    /** Blocks are only told apart by id or #tag; entity kinds such as {@code enemies} cannot pick blocks. */
    private static void blockFilter(Node f, SExpr at) {
        if (f == null || f.kind != Node.Kind.CALL) return;
        if (f.rune.name.equals("kind") && f.arg(0).value instanceof TargetKind k && !k.forBlocks) {
            throw err(at, "方块筛选不能用实体种类 " + k.word + "：方块只能按 id、#标签 或 (kind all) 筛选");
        }
        f.args.forEach(a -> blockFilter(a, at));
    }

    // ---- Visual inference --------------------------------------------------------------------

    private static Element inferElement(Node form) {
        Map<Element, Integer> counts = new EnumMap<>(Element.class);
        walk(form, counts, true);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Element.ARCANE);
    }

    /** A whole spell's look, by the same rules a form's is inferred by (a wand charge is drawn in it). */
    public static Element inferElement(List<Node> body) {
        Map<Element, Integer> counts = new EnumMap<>(Element.class);
        body.forEach(n -> walk(n, counts, false));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Element.ARCANE);
    }

    private static void walk(Node n, Map<Element, Integer> counts, boolean root) {
        if (n.kind != Node.Kind.CALL) return;
        if (!root) {
            Element e = switch (n.rune.name) {
                case "damage", "damage-all" -> n.args.size() > 2 && n.arg(2).kind == Node.Kind.LIT ? (Element) n.arg(2).value : null;
                case "ignite", "ignite-all" -> Element.FIRE;
                case "lightning" -> Element.LIGHTNING;
                case "freeze", "freeze-all" -> Element.FROST;
                case "drain", "execute" -> Element.VOID;
                case "heal", "shield" -> Element.LIFE;
                case "infuse", "infuse-block" -> Element.ARCANE;
                default -> null;
            };
            if (e != null) counts.merge(e, 1, Integer::sum);
        }
        n.args.forEach(a -> walk(a, counts, false));
        n.body.forEach(a -> walk(a, counts, false));
        n.events.values().forEach(l -> l.forEach(a -> walk(a, counts, false)));
    }

    // ---- Helpers -----------------------------------------------------------------------------

    static Double number(String t) {
        if (!t.matches("-?\\d+(\\.\\d+)?")) return null;
        return Double.parseDouble(t);
    }

    private static LogosError err(SExpr at, String msg) {
        return new LogosError(msg, at.line(), at.col());
    }

    private static String suggest(String word) {
        List<String> vocab = new ArrayList<>();
        Runes.all().forEach(r -> vocab.add(r.name));
        vocab.addAll(CONTEXT.keySet());
        vocab.addAll(MAGNITUDES.keySet());
        for (Element e : Element.values()) vocab.add(e.name().toLowerCase());
        for (Motion m : Motion.values()) vocab.add(m.name().toLowerCase());
        for (ConstructShape sh : ConstructShape.values()) vocab.add(sh.word());
        for (Weather w : Weather.values()) vocab.add(w.word());
        String best = null;
        int bestD = 3;
        for (String v : vocab) {
            int d = distance(word, v);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best == null ? "" : "。你是否想写 '" + best + "'？";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
