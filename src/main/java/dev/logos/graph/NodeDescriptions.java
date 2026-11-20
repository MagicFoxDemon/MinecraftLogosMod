package dev.logos.graph;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Type;
import net.minecraft.locale.Language;

/** Contextual help for the graph editor and 逻各斯手记, read from the active language pack via {@code logos.desc.*}. */
public final class NodeDescriptions {
    private static final String PREFIX = "logos.desc.";

    private NodeDescriptions() {}

    /**
     * A node's help in parts, for the editor and for 逻各斯手记: what it is, a line for each port, option and event
     * ({@code label}, {@code text}), what it gives, and anything else to know. Null for a node with no help written.
     */
    public record Help(String intro, List<Line> lines, @Nullable String result, @Nullable String note) {}

    public record Line(String label, String text) {}

    public static @Nullable Help help(LogosNode node) {
        String word = node.word();
        String introKey = PREFIX + word + ".intro";
        Language lang = Language.getInstance();
        if (!lang.has(introKey)) return null;
        List<Line> lines = new ArrayList<>();
        if (node instanceof RuneNode runeNode) {
            var rune = runeNode.rune();
            if (rune.returns == Type.ANY) line(lines, word, RuneNode.KIND, "");
            for (var param : rune.params) {
                if (param.rest()) {
                    line(lines, word, "argument-count", "");
                    Integer count = node.option(RuneNode.COUNT, Integer.class);
                    // Branches and options picked by an index are numbered from 0, like the index.
                    int first = param.type() == Type.ACTION || param.type() == Type.ANY ? 0 : 1;
                    for (int i = first; i < first + Math.max(1, count == null ? 2 : count); i++) {
                        line(lines, word, param.name(), " " + i);
                    }
                } else line(lines, word, param.name(), "");
            }
            for (var keyword : rune.keywords.values()) line(lines, word, keyword.name(), "");
            if (rune.body) line(lines, word, RuneNode.BODY, "");
            if (rune.yields != null) line(lines, word, RuneNode.YIELD, "");
            for (var event : rune.events.values()) {
                lines.add(new Line(NodeTerms.event(word, event.name()).getString(), field(word, event.name())));
            }
            if (rune.returns == Type.ACTION) {
                lines.add(new Line(t("logos.desc.flow", "流程"), t("logos.desc.flow.action", "上方箭头接入行动；下方箭头连接后续行动。")));
            }
        } else if (node instanceof SpecialNodes.EmitterRoot) {
            for (String id : new String[] {"name", "mana", "players", "mode", "interval", "start"}) line(lines, word, id, "");
        } else if (node instanceof SpecialNodes.SpellRoot) {
            for (String id : new String[] {"name", "mana", "trigger", "players", "immolate", "intent", "reach_self", "reach_one", "reach_group", "start"}) line(lines, word, id, "");
        } else if (node instanceof SpecialNodes.Remember) {
            for (String id : new String[] {"kind", "value", "remembered"}) line(lines, word, id, "");
            lines.add(new Line(t("logos.desc.flow", "流程"), t("logos.desc.flow.remember", "上方箭头接入行动；下方箭头连接使用铭记结果的后续行动。")));
        } else if (node instanceof SpecialNodes.AnchorValue) {
            line(lines, word, "anchor", "");
        }
        return new Help(lang.getOrDefault(introKey), List.copyOf(lines), optional(PREFIX + word + ".result"), optional(PREFIX + word + ".note"));
    }

    public static String describe(LogosNode node) {
        Help help = help(node);
        if (help == null) return "";
        String sep = t("logos.desc.sep", "：");
        StringBuilder text = new StringBuilder(help.intro());
        for (Line line : help.lines()) text.append("\n").append(line.label()).append(sep).append(line.text());
        if (help.result() != null) text.append("\n\n").append(t("logos.desc.result", "结果")).append(sep).append(help.result());
        if (help.note() != null) text.append("\n\n").append(t("logos.desc.note", "说明")).append(sep).append(help.note());
        return text.toString();
    }

    private static void line(List<Line> lines, String word, String id, String suffix) {
        lines.add(new Line(NodeTerms.parameter(word, id).getString() + suffix, field(word, id)));
    }

    private static String field(String word, String id) {
        Language lang = Language.getInstance();
        String key = PREFIX + word + ".param." + id;
        return lang.has(key) ? lang.getOrDefault(key) : t("logos.desc.missing", "此参数的介绍尚待补充。");
    }

    private static @Nullable String optional(String key) {
        Language lang = Language.getInstance();
        return lang.has(key) ? lang.getOrDefault(key) : null;
    }

    private static String t(String key, String fallback) {
        return Language.getInstance().getOrDefault(key, fallback);
    }
}
