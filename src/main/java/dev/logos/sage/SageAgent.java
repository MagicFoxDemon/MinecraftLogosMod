package dev.logos.sage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;

/**
 * The sage's tool loop: the model reads rune docs and examples, tries its spell on the compiler,
 * fixes what the compiler rejects, and hands the result in with {@code submit_spell}. The compiler
 * runs right here in a few milliseconds, so a correction costs one round instead of a whole new
 * request. The same loop runs on the client (the player's own key) and on the server (the server's).
 */
public final class SageAgent {
    /** Most model rounds one question may take. */
    static final int MAX_ROUNDS = 8;
    /** Most rejected submissions before the sage gives up and shows its last try. */
    static final int MAX_SUBMITS = 3;
    public static final int MAX_SOURCE = 8_000;
    public static final int MAX_MESSAGE = 2_000;

    /** One line of a conversation as the player sees it: who spoke, what was said, and the spell handed over (or empty). */
    public record Turn(boolean player, String text, String source) {}

    /**
     * What to answer: the history, the new message, the spell now on the canvas (empty when it does not
     * hold), and the page it is for: how much mana it holds ({@code capacity}, 0 when unknown) and
     * whether it lies in a spell emitter (which cannot immolate a page to go past that).
     */
    public record Request(String lang, Set<String> available, List<Turn> history, String message, String currentSource,
                          int capacity, boolean emitter) {}

    /**
     * The answer: the sage's words and the spell it handed in (empty when it only talked). When it
     * failed, {@code error} is a {@code logos.sage.error.*} key and {@code detail} what went wrong.
     */
    public record Outcome(String text, String source, String error, String detail) {
        static Outcome reply(String text, String source) {
            return new Outcome(text == null ? "" : text.strip(), source == null ? "" : source, "", "");
        }

        public static Outcome failed(String error, String detail, String lastSource) {
            return new Outcome("", lastSource == null ? "" : lastSource, error, detail == null ? "" : detail);
        }

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /** Progress worth showing while the sage works: a {@code logos.sage.status.*} key and its argument. */
    public interface Listener {
        void status(String key, String arg);
    }

    static final List<SageModel.Tool> TOOLS = List.of(
            new SageModel.Tool("lookup_rune", "查一个符文的完整说明：签名、每个插槽、事件和魔力价格。",
                    "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"符文名，如 projectile\"}},\"required\":[\"name\"]}"),
            new SageModel.Tool("get_example", "取一条示例术式的全文。",
                    "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"示例 id，如 fireball\"}},\"required\":[\"id\"]}"),
            new SageModel.Tool("compile", "试演一段术式：成立时给出注入魔力、估算消耗和意图，不成立时给出编译器的错误。",
                    "{\"type\":\"object\",\"properties\":{\"source\":{\"type\":\"string\",\"description\":\"完整的 (spell …) 源码\"}},\"required\":[\"source\"]}"),
            new SageModel.Tool("get_current_spell", "读出搭档编辑器里现在的术式源码。",
                    "{\"type\":\"object\",\"properties\":{}}"),
            new SageModel.Tool("submit_spell", "把写好的术式交给搭档。会先编译检查，不成立时退回错误。",
                    "{\"type\":\"object\",\"properties\":{\"source\":{\"type\":\"string\",\"description\":\"完整的 (spell …) 源码\"},"
                            + "\"explanation\":{\"type\":\"string\",\"description\":\"用你的口吻说写好了，并用一两句话点出核心思路，不逐个解释节点\"}},\"required\":[\"source\",\"explanation\"]}"));

    private SageAgent() {}

    /** Runs the loop on the calling thread until the sage answers, gives up, or the thread is interrupted. */
    public static Outcome run(SageModel model, Request req, Listener listener) throws InterruptedException {
        List<SageModel.Message> messages = new ArrayList<>();
        messages.add(SageModel.Message.system(SagePrompt.system(req.lang(), req.available())));
        for (Turn t : req.history()) {
            if (t.player()) messages.add(SageModel.Message.user(t.text()));
            else messages.add(SageModel.Message.assistant(t.source().isEmpty() ? t.text() : t.text() + "\n\n" + t.source(), List.of()));
        }
        String message = req.message();
        if (!req.currentSource().isEmpty()) message += "\n\n（编辑器里已有一道成立的术式，需要时用 get_current_spell 查看）";
        // The page goes with the question, not into the system prompt, so the prompt's prefix stays cacheable.
        message += page(req);
        messages.add(SageModel.Message.user(message));

        String lastSource = "";
        int rejected = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (Thread.interrupted()) throw new InterruptedException();
            listener.status(round == 0 ? "thinking" : "rethinking", "");
            SageModel.Reply reply;
            try {
                reply = model.complete(messages, TOOLS);
            } catch (IOException ex) {
                return Outcome.failed("logos.sage.error.network", ex.getMessage(), lastSource);
            }
            if (reply.toolCalls().isEmpty()) return Outcome.reply(reply.content(), "");
            messages.add(SageModel.Message.assistant(reply.content(), reply.toolCalls()));
            for (SageModel.ToolCall call : reply.toolCalls()) {
                JsonObject args = args(call.arguments());
                String result;
                switch (call.name()) {
                    case "lookup_rune" -> {
                        String name = str(args, "name");
                        listener.status("lookup", name);
                        result = lookup(name, req.available());
                    }
                    case "get_example" -> {
                        String id = str(args, "id");
                        listener.status("example", id);
                        String src = Examples.ALL.get(id);
                        result = src == null ? "没有这个示例。可用: " + String.join(", ", Examples.ALL.keySet()) : src;
                    }
                    case "get_current_spell" -> {
                        listener.status("current", "");
                        result = req.currentSource().isEmpty() ? "（编辑器是空的，或者现在的术式不成立）" : req.currentSource();
                    }
                    case "compile" -> {
                        listener.status("compile", "");
                        result = check(str(args, "source"), req.available(), req.capacity()).report();
                    }
                    case "submit_spell" -> {
                        String src = str(args, "source");
                        lastSource = src;
                        Check c = check(src, req.available(), req.capacity());
                        if (c.ok()) {
                            listener.status("submitted", c.spell().name());
                            String explanation = str(args, "explanation");
                            return Outcome.reply(explanation.isBlank() ? reply.content() : explanation, src.strip() + "\n");
                        }
                        rejected++;
                        listener.status("rejected", String.valueOf(rejected));
                        if (rejected >= MAX_SUBMITS) return Outcome.failed("logos.sage.error.unfixed", c.report(), src);
                        result = c.report() + "\n请修改后重新提交。";
                    }
                    default -> result = "没有叫 " + call.name() + " 的工具。";
                }
                messages.add(SageModel.Message.tool(call.id(), result));
            }
        }
        return Outcome.failed("logos.sage.error.rounds", "", lastSource);
    }

    /** A compile result in the words the model reads: the spell's numbers, or the compiler's complaint. */
    record Check(Spell spell, String error, int capacity) {
        boolean ok() {
            return spell != null;
        }

        /** Whether the spell injects more than the page holds; it still compiles, so this is advice, not a rejection. */
        boolean overflows() {
            return spell != null && capacity > 0 && spell.mana() > capacity;
        }

        String report() {
            if (spell == null) return "✗ 不成立：" + error;
            var p = spell.profile();
            StringBuilder sb = new StringBuilder("✓ 成立：《").append(spell.name()).append("》 注入 ").append(Node.fmt(spell.mana()))
                    .append("，估计一次消耗约 ").append(Math.round(p.estimate())).append("，意图 ").append(p.intentsZh())
                    .append("，稳定度 ").append(Node.fmt(spell.stability()));
            if (p.estimate() > spell.mana()) sb.append("\n⚠ 估计消耗比注入多，施放到一半可能熄灭：调高 :mana。");
            if (overflows()) {
                sb.append("\n⚠ 注入 ").append(Node.fmt(spell.mana())).append(" 超出了载页容量 ").append(capacity)
                        .append("：写入后每次只能注入 ").append(capacity).append("。除非搭档明确要求更大的法术，请把它缩到容量以内。");
            }
            for (String n : spell.notes()) sb.append("\n∴ ").append(n);
            return sb.toString();
        }
    }

    /** The page the spell is for, in the words the model reads; nothing when the page is unknown. */
    static String page(Request req) {
        if (req.capacity() <= 0) return "";
        return "\n\n（当前" + (req.emitter() ? "术式发射器里" : "编纂台上") + "的载页容量是 " + req.capacity() + " 魔力。"
                + "优先写能装下的术式，:mana 不超过 " + req.capacity() + "。"
                + "只有搭档明确要求更大的法术、不在乎载页容量时，才可以超出，并且要提醒：超出的部分写入后注入不了"
                + (req.emitter() ? "（发射器不能焚式）。）" : "，除非用持焚式的法杖施放、焚毁载页来突破。）");
    }

    /** Compiles {@code source} and turns away runes the player has not learned yet, as the compiler would turn away a typo. */
    static Check check(String source, Set<String> available, int capacity) {
        if (source == null || source.isBlank()) return new Check(null, "源码是空的", capacity);
        if (source.length() > MAX_SOURCE) return new Check(null, "术式太长（上限 " + MAX_SOURCE + " 字）", capacity);
        Spell spell;
        try {
            spell = Compiler.compile(source);
        } catch (LogosError e) {
            return new Check(null, e.describe(), capacity);
        } catch (RuntimeException e) {
            return new Check(null, "编译器无法读懂：" + e.getClass().getSimpleName(), capacity);
        }
        Set<String> locked = new LinkedHashSet<>();
        for (Node n : spell.body()) lockedRunes(n, available, locked);
        if (!locked.isEmpty()) {
            return new Check(null, "搭档还没有掌握这些符文：" + String.join(", ", locked) + "。只能使用「可用符文」里的符文。", capacity);
        }
        return new Check(spell, "", capacity);
    }

    static void lockedRunes(Node n, Set<String> available, Set<String> into) {
        if (n == null) return;
        if (n.kind == Node.Kind.CALL && n.rune != null && !available.contains(n.rune.name)) into.add(n.rune.name);
        for (Node a : n.args) lockedRunes(a, available, into);
        for (Node a : n.kw.values()) lockedRunes(a, available, into);
        for (Node a : n.body) lockedRunes(a, available, into);
        for (List<Node> ev : n.events.values()) for (Node a : ev) lockedRunes(a, available, into);
    }

    /** Everything the language says about one rune. */
    static String lookup(String name, Set<String> available) {
        Rune r = Runes.get(name);
        if (r == null) return "没有叫 " + name + " 的符文。";
        if (!available.contains(r.name)) return "搭档还没有掌握 " + name + "，不能使用。";
        StringBuilder sb = new StringBuilder(r.signature()).append('\n').append(r.doc);
        for (Rune.Param p : r.params) {
            sb.append("\n· ").append(p.name()).append(' ').append(p.type().label());
            if (p.defaultSrc() != null) sb.append("（默认 ").append(p.defaultSrc()).append('）');
        }
        for (Rune.Kw k : r.keywords.values()) {
            sb.append("\n· :").append(k.name()).append(' ');
            if (k.flag()) sb.append("开关");
            else if (k.choice()) sb.append(String.join("|", k.choices()));
            else sb.append(k.type().label());
            if (k.defaultSrc() != null && !k.flag()) sb.append("（默认 ").append(k.defaultSrc()).append('）');
        }
        for (Map.Entry<String, Rune.EventSpec> e : r.events.entrySet()) {
            sb.append("\n· (").append(e.getKey()).append(" …)").append(e.getValue().bindsTarget() ? " target=被选中者" : "")
                    .append(e.getValue().doc() == null || e.getValue().doc().isEmpty() ? "" : "：" + e.getValue().doc());
        }
        return sb.toString();
    }

    private static JsonObject args(String json) {
        try {
            JsonElement e = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
