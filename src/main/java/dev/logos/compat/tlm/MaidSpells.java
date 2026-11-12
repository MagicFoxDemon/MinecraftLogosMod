package dev.logos.compat.tlm;

import java.util.ArrayList;
import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.CodexItem;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.WandItem;
import dev.logos.lang.Intent;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Limits;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

/**
 * What a maid in the Spellcasting mode can cast and how fast: the written pages on the wand in her main hand, then on a
 * codex in her off hand (as a player's codex counts as hung on their wand), the ones her owner's stability bears, and
 * the wand's channel flow and surge.
 */
final class MaidSpells {
    /** A castable page and what one cast of it injects: its capacity at most, as from a wand without Formula Immolation. */
    record Page(ItemStack page, Spell spell, PageTier tier, double injection) {}

    private MaidSpells() {}

    static boolean holdsWand(EntityMaid maid) {
        return maid.getMainHandItem().getItem() instanceof WandItem;
    }

    /** Her active (not passive) pages that her owner's stability bears; empty without a wand. */
    static List<Page> active(EntityMaid maid) {
        List<Page> out = new ArrayList<>();
        if (!holdsWand(maid)) return out;
        int stability = stability(maid);
        add(out, maid.getMainHandItem(), stability);
        ItemStack off = maid.getOffhandItem();
        if (off.getItem() instanceof CodexItem) add(out, off, stability);
        return out;
    }

    private static void add(List<Page> out, ItemStack holder, int stability) {
        for (int i = 0; i < PageContainer.size(holder); i++) {
            ItemStack page = PageContainer.get(holder, i);
            if (!SpellPages.isInscribed(page)) continue;
            Spell s = SpellPages.spell(page);
            if (s == null || s.isPassive() || s.stability() > stability) continue;
            PageTier tier = SpellPages.tier(page);
            if (tier == null) tier = PageTier.BASIC;
            out.add(new Page(page, s, tier, Casting.injection(s, tier, 0).mana()));
        }
    }

    /** The pages filed under one of {@code intents}, cheapest first (as a lifeform picks them). */
    static List<Page> with(List<Page> pages, Intent... intents) {
        List<Page> out = new ArrayList<>();
        for (Page p : pages) {
            for (Intent i : intents) {
                if (p.spell().profile().primary() == i) {
                    out.add(p);
                    break;
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.injection(), b.injection()));
        return out;
    }

    /** Her attack pages: meant for enemies, or of no particular intent. */
    static List<Page> attacks(List<Page> pages) {
        return with(pages, Intent.STRIKE, Intent.AREA, Intent.CONTROL, Intent.UTILITY);
    }

    /** Roughly how far her attacks reach: she keeps about this far from her foe. */
    static double attackRange(List<Page> attacks) {
        double r = 0;
        for (Page p : attacks) r = Math.max(r, p.spell().profile().range());
        return r <= 0 ? 8 : Mth.clamp(r, 4, 12);
    }

    /** Her owner's stability bounds what she can speak, as it bounds their lifeforms; the default while they are away. */
    static int stability(EntityMaid maid) {
        ServerPlayer owner = MaidMana.owner(maid);
        return owner == null ? Limits.DEFAULT_STABILITY : PlayerMagic.of(owner).stability;
    }

    /** The wand's channel flow, sped up by her channel speed (as a player's is). */
    static double flow(EntityMaid maid) {
        return channel(maid.getMainHandItem(), LogosMod.CHANNEL_FLOW) * SpellModifiers.channelSpeed(maid);
    }

    /** The wand's channel surge, grown by her pool's depth and sped up by her channel speed (as a player's is). */
    static double surge(EntityMaid maid, CreatureMana pool) {
        double speed = SpellModifiers.channelSpeed(maid);
        return channel(maid.getMainHandItem(), LogosMod.CHANNEL_SURGE) * (1 + pool.maxMana() / LogosConfig.chargeManaScale()) * speed * speed;
    }

    /** What {@code wand} adds to {@code attribute} in the main hand: a maid has no channel attributes of her own. */
    private static double channel(ItemStack wand, Holder<Attribute> attribute) {
        double[] sum = {0};
        wand.forEachModifier(EquipmentSlot.MAINHAND, (a, modifier) -> {
            if (a.equals(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) sum[0] += modifier.amount();
        });
        return sum[0];
    }
}
