package dev.logos.item;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Examples;
import dev.logos.lang.LogosError;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 术式载页: an empty page becomes an inscribed page carrying one spell. Pages come in grades
 * ({@link PageTier}); writing keeps the grade. A page burnt by Formula Immolation becomes a
 * 术式余烬 that still carries the spell.
 */
public final class SpellPages {
    private SpellPages() {}

    /** Any grade of page, empty or written. */
    public static boolean isPage(ItemStack stack) {
        return stack.getItem() instanceof SpellPageItem;
    }

    public static boolean isEmber(ItemStack stack) {
        return stack.is(LogosMod.SPELL_EMBER.get());
    }

    /** What wands and codices hold: pages, and the embers of burnt ones. */
    public static boolean isHoldable(ItemStack stack) {
        return isPage(stack) || isEmber(stack);
    }

    public static boolean isInscribed(ItemStack stack) {
        return stack.getItem() instanceof SpellPageItem p && p.inscribed() && stack.has(LogosMod.SPELL_SOURCE.get());
    }

    public static boolean isBlank(ItemStack stack) {
        return stack.getItem() instanceof SpellPageItem p && !p.inscribed();
    }

    /** The page's grade, or null for anything that is not a page. */
    public static @Nullable PageTier tier(ItemStack stack) {
        return stack.getItem() instanceof SpellPageItem p ? p.tier() : null;
    }

    /** A new basic inscribed page holding {@code source} (and the graph it was drawn with, if any). */
    public static ItemStack inscribed(String source, @Nullable CompoundTag graph) {
        return inscribed(PageTier.BASIC, source, graph);
    }

    public static ItemStack inscribed(PageTier tier, String source, @Nullable CompoundTag graph) {
        ItemStack page = new ItemStack(tier.inscribed());
        page.set(LogosMod.SPELL_SOURCE.get(), source);
        if (graph != null) page.set(LogosMod.SPELL_GRAPH.get(), graph);
        return page;
    }

    /** {@code page} (empty or written, any grade) rewritten with a spell; the grade and any custom name stay. */
    public static ItemStack write(ItemStack page, String source, @Nullable CompoundTag graph) {
        PageTier tier = tier(page);
        ItemStack out = inscribed(tier == null ? PageTier.BASIC : tier, source, graph);
        Component custom = page.get(DataComponents.CUSTOM_NAME);
        if (custom != null) out.set(DataComponents.CUSTOM_NAME, custom);
        return out;
    }

    /** An example spell on the lowest grade of page that holds its whole injection. */
    public static ItemStack fitting(String source) {
        Spell s = spell(source);
        return inscribed(s == null ? PageTier.BASIC : PageTier.fitting(s.mana()), source, null);
    }

    /** What a page leaves when Formula Immolation burns it: an ember with the same spell and drawing. */
    public static ItemStack burn(ItemStack page) {
        ItemStack ember = new ItemStack(LogosMod.SPELL_EMBER.get());
        ember.set(LogosMod.SPELL_SOURCE.get(), page.get(LogosMod.SPELL_SOURCE.get()));
        CompoundTag graph = page.get(LogosMod.SPELL_GRAPH.get());
        if (graph != null) ember.set(LogosMod.SPELL_GRAPH.get(), graph);
        return ember;
    }

    /** A spell's display name: an unchanged example follows the game language, anything else shows its own :name. */
    public static Component name(ItemStack page, Spell spell) {
        String id = Examples.idOf(page.get(LogosMod.SPELL_SOURCE.get()));
        return id == null ? Component.literal(spell.name()) : Component.translatableWithFallback("logos.example." + id, spell.name());
    }

    /** The compiled spell on a page or ember, or null for an empty or unreadable one. */
    public static @Nullable Spell spell(ItemStack stack) {
        return spell(stack.get(LogosMod.SPELL_SOURCE.get()));
    }

    private static @Nullable Spell spell(@Nullable String src) {
        if (src == null) return null;
        try {
            return Casting.compile(src);
        } catch (LogosError e) {
            return null;
        }
    }
}
