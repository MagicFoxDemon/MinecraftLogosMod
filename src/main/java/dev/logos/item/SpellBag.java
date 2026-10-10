package dev.logos.item;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Spell;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The spells a player can reach with a wand: the pages hung on the wand, followed by the pages of
 * a codex held in the off hand (a codex in the off hand counts as hung on the wand).
 */
public final class SpellBag {
    /**
     * One castable page. {@code fromCodex} tells the wheel where it came from; {@code holder} (the
     * held wand or codex itself) and {@code slot} say where it lies, so a burning cast can leave its ember there.
     */
    public record Entry(ItemStack page, Spell spell, boolean fromCodex, ItemStack holder, int slot) {
        public PageTier tier() {
            PageTier t = SpellPages.tier(page);
            return t == null ? PageTier.BASIC : t;
        }

        /** Replaces the page where it lies with what Formula Immolation leaves of it. */
        public void burn() {
            if (ItemStack.isSameItemSameComponents(PageContainer.get(holder, slot), page)) PageContainer.set(holder, slot, SpellPages.burn(page));
        }
    }

    private SpellBag() {}

    /** The wand the player is holding, main hand first. */
    public static @Nullable ItemStack heldWand(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = player.getItemInHand(hand);
            if (s.getItem() instanceof WandItem) return s;
        }
        return null;
    }

    public static List<Entry> entries(Player player, ItemStack wand) {
        List<Entry> out = new ArrayList<>();
        add(out, wand, false);
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof CodexItem) add(out, off, true);
        return out;
    }

    /** Written pages only: embers keep their spell but cannot be cast. */
    private static void add(List<Entry> out, ItemStack holder, boolean fromCodex) {
        for (int i = 0; i < PageContainer.size(holder); i++) {
            ItemStack page = PageContainer.get(holder, i);
            if (!SpellPages.isInscribed(page)) continue;
            Spell s = SpellPages.spell(page);
            if (s != null) out.add(new Entry(page, s, fromCodex, holder, i));
        }
    }

    /** The entry the wand has selected (clamped into range), or null if there are no spells. */
    public static @Nullable Entry selected(Player player, ItemStack wand) {
        List<Entry> all = entries(player, wand);
        if (all.isEmpty()) return null;
        int i = wand.getOrDefault(LogosMod.SELECTED.get(), 0);
        return all.get(Math.floorMod(i, all.size()));
    }

    /**
     * Passive spells (hurt / attack / kill) on any wand or codex held in either hand. They need not
     * be selected, and a codex works on its own, without a wand.
     */
    public static List<Entry> passives(Player player) {
        List<Entry> out = new ArrayList<>();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack holder = player.getItemInHand(hand);
            if (holder.getItem() instanceof WandItem || holder.getItem() instanceof CodexItem) add(out, holder, holder.getItem() instanceof CodexItem);
        }
        out.removeIf(e -> !e.spell().isPassive());
        return out;
    }
}
