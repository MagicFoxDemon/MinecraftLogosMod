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
import dev.logos.item.StaffItem;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * What a maid in the Spellcasting mode can cast and how fast: the written pages on her wand, then on a codex in her off
 * hand (as a player's codex counts as hung on their wand), the ones her owner's stability bears, and the wand's channel
 * flow and surge. Her wand is the one in her main hand, or, as a player may hold one, a wand (not a staff) in her off
 * hand; then her main hand is free for a melee weapon ({@link #melee}).
 */
final class MaidSpells {
    /** A castable page and what one cast of it injects: its capacity at most, as from a wand without Formula Immolation. */
    record Page(ItemStack page, Spell spell, PageTier tier, double injection) {}

    private MaidSpells() {}

    /** The wand she casts with: the one in her main hand, else a wand (a staff only from the main hand) in her off hand; empty if none. */
    static ItemStack wand(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem();
        if (main.getItem() instanceof WandItem) return main;
        ItemStack off = maid.getOffhandItem();
        return off.getItem() instanceof WandItem && !(off.getItem() instanceof StaffItem) ? off : ItemStack.EMPTY;
    }

    static boolean holdsWand(EntityMaid maid) {
        return !wand(maid).isEmpty();
    }

    /** The hand her wand is in (the main hand if she holds none). */
    static InteractionHand wandHand(EntityMaid maid) {
        return maid.getMainHandItem().getItem() instanceof WandItem ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    /**
     * Whether she fights in close as well: a wand in her off hand and a melee weapon in her main hand (anything that adds
     * to attack damage there, as a sword or an axe does).
     */
    static boolean melee(EntityMaid maid) {
        if (wandHand(maid) != InteractionHand.OFF_HAND || !holdsWand(maid)) return false;
        double[] damage = {0};
        maid.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND, (a, modifier) -> {
            if (a.equals(Attributes.ATTACK_DAMAGE) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) damage[0] += modifier.amount();
        });
        return damage[0] > 0;
    }

    /** Her active (not passive) pages that her owner's stability bears; empty without a wand. */
    static List<Page> active(EntityMaid maid) {
        List<Page> out = new ArrayList<>();
        if (!holdsWand(maid)) return out;
        int stability = stability(maid);
        add(out, wand(maid), stability);
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

    /** Of {@code pages}, those that can reach {@code r} (see {@link dev.logos.lang.Reach}), in the same order. */
    static List<Page> reaching(List<Page> pages, dev.logos.lang.Reach r) {
        return pages.stream().filter(p -> p.spell().profile().reaches(r)).toList();
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
        return channel(wand(maid), LogosMod.CHANNEL_FLOW) * SpellModifiers.channelSpeed(maid);
    }

    /** The wand's channel surge, grown by her pool's depth and sped up by her channel speed (as a player's is). */
    static double surge(EntityMaid maid, CreatureMana pool) {
        double speed = SpellModifiers.channelSpeed(maid);
        return channel(wand(maid), LogosMod.CHANNEL_SURGE) * (1 + pool.maxMana() / LogosConfig.chargeManaScale()) * speed * speed;
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
