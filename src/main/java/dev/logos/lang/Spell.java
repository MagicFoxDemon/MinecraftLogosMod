package dev.logos.lang;

import java.util.List;

/**
 * A compiled spell: a typed AST plus its static budgets.
 *
 * @param trigger   cast / hurt / attack / kill
 * @param affectsPlayers whether effects may touch players other than the caster (off by default)
 * @param immolate  whether a wand with Formula Immolation may burn the page to inject past its capacity (on by default)
 * @param mana      mana injected per invocation; the whole spell lifecycle draws from this pool
 * @param stability static complexity cost, must fit the caster's stability capacity
 * @param profile   what it is for and roughly what it costs (see {@link SpellProfile})
 * @param element   how a cast of it looks, worked out once when compiled (see {@link Compiler#inferElement(List)})
 */
public record Spell(String name, String trigger, boolean affectsPlayers, boolean immolate, double mana, double stability, List<Node> body, List<String> notes, String source, SpellProfile profile, Element element) {
    public static final List<String> TRIGGERS = List.of("cast", "hurt", "attack", "kill");

    public boolean isPassive() {
        return !trigger.equals("cast");
    }

    public static String triggerZh(String trigger) {
        return switch (trigger) {
            case "hurt" -> "受伤时";
            case "attack" -> "攻击时";
            case "kill" -> "击杀时";
            default -> "主动施放";
        };
    }

    /** When a spell with {@code trigger} goes off, for players to read in their own language ({@code logos.trigger.*}). */
    public static net.minecraft.network.chat.MutableComponent triggerName(String trigger) {
        return net.minecraft.network.chat.Component.translatable("logos.trigger." + (TRIGGERS.contains(trigger) ? trigger : "cast"));
    }
}
