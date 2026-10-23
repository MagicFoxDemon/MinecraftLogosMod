package dev.logos.lang;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Renders a compiled spell as the AST the world "sees", with budgets and proofs. */
public final class Report {
    private Report() {}

    public static void analyze(Spell s, int capacity, Consumer<Component> out) {
        out.accept(Component.literal("═══ 《" + s.name() + "》 ═══").withStyle(ChatFormatting.GOLD));
        ChatFormatting stab = s.stability() > capacity ? ChatFormatting.RED : ChatFormatting.GREEN;
        out.accept(Component.literal("启式 " + Spell.triggerZh(s.trigger()) + " · 注入魔力 " + Node.fmt(s.mana()) + " · ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("稳定度 " + Node.fmt(s.stability()) + " / " + capacity).withStyle(stab)));
        for (Node n : s.body()) tree(n, "", out);
        for (String note : s.notes()) out.accept(Component.literal("∴ " + note).withStyle(ChatFormatting.DARK_PURPLE));
    }

    public static void summary(Spell s, int capacity, Consumer<Component> out) {
        ChatFormatting stab = s.stability() > capacity ? ChatFormatting.RED : ChatFormatting.GREEN;
        out.accept(Component.literal("✓ 《" + s.name() + "》 成立").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" · " + Spell.triggerZh(s.trigger()) + " · 注入 " + Node.fmt(s.mana()) + " · ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("稳定度 " + Node.fmt(s.stability()) + "/" + capacity).withStyle(stab)));
        for (String note : s.notes()) out.accept(Component.literal("∴ " + note).withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static MutableComponent tag(Node n) {
        Category c = n.rune.category;
        return Component.literal("[" + c.label() + "] ").withStyle(c.color);
    }

    private static void tree(Node n, String indent, Consumer<Component> out) {
        if (n.kind != Node.Kind.CALL) {
            out.accept(Component.literal(indent + n.show()));
            return;
        }
        String next = indent + "   ";
        switch (n.rune.name) {
            case "if" -> {
                out.accept(Component.literal(indent).append(tag(n)).append(Component.literal("if " + n.arg(0).show())));
                out.accept(Component.literal(next + "✔ 成立:").withStyle(ChatFormatting.DARK_GREEN));
                tree(n.arg(1), next + "   ", out);
                if (n.arg(2) != null) {
                    out.accept(Component.literal(next + "✘ 否则:").withStyle(ChatFormatting.DARK_RED));
                    tree(n.arg(2), next + "   ", out);
                }
                return;
            }
            case "switch" -> {
                out.accept(Component.literal(indent).append(tag(n)).append(Component.literal("switch " + n.arg(0).show())));
                for (int i = 1; i < n.args.size(); i++) {
                    out.accept(Component.literal(next + "⤷ " + (i - 1) + ":").withStyle(ChatFormatting.LIGHT_PURPLE));
                    tree(n.arg(i), next + "   ", out);
                }
            }
            case "let" -> out.accept(Component.literal(indent).append(tag(n))
                    .append(Component.literal("let " + n.arg(0).value + " = " + n.arg(1).show() + "  ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(n.arg(1).type.label()).withStyle(ChatFormatting.DARK_GRAY)));
            default -> {
                MutableComponent line = Component.literal(indent).append(tag(n)).append(Component.literal(n.show()));
                if (n.rune.category == Category.FORM) line.append(Component.literal("  ✦" + n.element.zh).withStyle(ChatFormatting.DARK_GRAY));
                out.accept(line);
            }
        }
        for (var e : n.events.entrySet()) {
            out.accept(Component.literal(next + "⤷ " + e.getKey()).withStyle(ChatFormatting.AQUA));
            for (Node c : e.getValue()) tree(c, next + "   ", out);
        }
        List<Node> body = n.body;
        for (Node c : body) tree(c, next, out);
    }
}
