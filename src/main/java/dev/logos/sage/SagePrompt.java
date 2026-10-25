package dev.logos.sage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.logos.lang.Compiler;
import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import dev.logos.lang.Examples;
import dev.logos.lang.Intent;
import dev.logos.lang.Motion;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;
import dev.logos.lang.Type;
import dev.logos.lang.Weather;

/**
 * The sage's system prompt, written from the language itself rather than by hand: the grammar, the
 * slot types, the words the compiler knows and one line per rune the player may use. A new rune
 * shows up here on its own. Full rune docs and example spells are fetched with tools, so the
 * prompt stays short and its prefix stays the same between requests (providers cache it).
 */
public final class SagePrompt {
    /** Longest part of a rune's doc listed in the prompt; {@code lookup_rune} gives the rest. */
    static final int DOC_PREVIEW = 90;

    private SagePrompt() {}

    /** {@code lang} is the player's game language ({@code zh_cn}, {@code en_us} …): the persona and the reply follow it. */
    public static String system(String lang, Set<String> available) {
        StringBuilder sb = new StringBuilder();
        sb.append(persona(lang)).append("\n\n");
        sb.append("""
                # 任务
                搭档会用自然语言描述想要的法术。你用 Logos 术式语言写出它，交给编纂台。
                - 只能使用下面「可用符文」里列出的符文，其他符文搭档还没有掌握。
                - 不确定某个符文怎么用时，先调用 lookup_rune 查它的完整说明；需要参照时用 get_example 看示例术式。
                - 搭档要你修改「现在的术式」时，先用 get_current_spell 读出来，在它的基础上改。
                - 写好后先用 compile 试演；编译器报错就照着错误修改，直到成立。
                - 最后必须调用 submit_spell 交出术式，并附上一句简短的交代：说写好了，再用一两句话点出最核心的思路，不要逐个解释节点和参数（搭档会自己问）。不要把术式源码写在普通回复里。
                - 搭档只是闲聊或提问、并不需要新术式时，直接回答，不调用 submit_spell。需求不清楚时可以先问一句。
                - 魔力 :mana 是一次施放注入的总量。compile 会给出估算的消耗，注入量应略多于估算。
                - 问题后面会注明当前载页的容量。优先写装得下的术式；搭档明确要求更大的法术时才超出，并提醒超出的部分注入不了。
                - 游戏里的文字框只显示纯文本：不要用 Markdown（不要 **、`、#、表格），提到符文或写法时用「」括起来。
                - 交术式时的话控制在三句以内；搭档追问细节时再展开讲。

                # 语法
                术式是 S 表达式。最外层写一个 (spell …)，关键字 :name 名字、:mana 注入魔力、:trigger 启式（cast 手动施放，hurt 受伤，attack 攻击，kill 击杀）、
                :intent 意图、:players 是否作用于其他玩家、:immolate 是否允许焚式，之后依次写行动。
                - 符文调用：(符文名 位置参数… :关键字 值 … (on-事件 行动…) 行动…)。签名里 [ ] 表示可省略，… 表示可以写多个。
                - 事件 (on-hit …)、(on-expire …) 等只能写在会产生它的符文里面；在事件和 each 里，target 指被选中的对象。
                - 变量：(let $名字 值 行动…)；条件：(if 命题 行动 [否则的行动])，多个行动用 (do …) 包起来。
                - 注释以 ; 开头。
                """);
        sb.append("\n## 插槽类型\n");
        for (Type t : Type.values()) sb.append(t.glyph).append(' ').append(t.zh).append("  ");
        sb.append("\n## 词\n");
        sb.append("上下文：").append(String.join(" ", Compiler.CONTEXT.keySet())).append('\n');
        sb.append("量级（□数）：");
        for (Map.Entry<String, Double> m : Compiler.MAGNITUDES.entrySet()) sb.append(m.getKey()).append('=').append(fmt(m.getValue())).append(' ');
        sb.append("\n元素 ✦：");
        for (Element e : Element.values()) if (Element.parse(e.name().toLowerCase(Locale.ROOT)) == e) sb.append(e.name().toLowerCase(Locale.ROOT)).append(' ');
        sb.append("\n运动 ∿：");
        for (Motion m : Motion.values()) sb.append(m.name().toLowerCase(Locale.ROOT)).append(' ');
        sb.append("\n形状 ⬡：");
        for (ConstructShape s : ConstructShape.values()) sb.append(s.word()).append(' ');
        sb.append("\n天气 ☁：");
        for (Weather w : Weather.values()) sb.append(w.word()).append(' ');
        sb.append("\n状态 ※：burning frozen，或自定义印记 #名字");
        sb.append("\n意图：").append(Intent.words());
        sb.append("\n启式：").append(String.join(" ", Spell.TRIGGERS));
        sb.append("\n其他字面量：&物品id 外观，%粒子id 粒子，^名字 锚点，@维度，\"文字\"");
        sb.append("\n\n## 可用符文（签名 — 说明摘要）\n");
        for (Rune r : Runes.all()) {
            if (!available.contains(r.name)) continue;
            sb.append(r.signature()).append(" — ").append(preview(r.doc)).append('\n');
        }
        sb.append("\n## 示例术式（用 get_example 取全文）\n");
        for (Map.Entry<String, String> e : Examples.ALL.entrySet()) {
            String first = e.getValue().lines().findFirst().orElse("");
            sb.append(e.getKey()).append(first.startsWith(";") ? " " + first : "").append('\n');
        }
        return sb.toString();
    }

    static String persona(String lang) {
        String file = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh") ? "persona_zh.txt" : "persona_en.txt";
        try (InputStream in = SagePrompt.class.getResourceAsStream("/logos/sage/" + file)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ignored) {
            // fall through to the plain role
        }
        return "You are the Book Sprite, a friendly guide to the Logos spell language.";
    }

    static String preview(String doc) {
        String d = doc == null ? "" : doc.replace('\n', ' ').strip();
        return d.length() <= DOC_PREVIEW ? d : d.substring(0, DOC_PREVIEW) + "…";
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
