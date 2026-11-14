package dev.logos.gametest;

import dev.logos.runtime.ManaGrowth;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 魔力成长: the cap's growth terms, lifetime experience, the surplus drain and the {@code max_mana} attribute.
 * The tests call {@link ManaGrowth#tick} themselves, so they do not wait on the mock player being ticked.
 */
final class ManaGrowthGameTests {
    private ManaGrowthGameTests() {}

    private static ServerPlayer player(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 200;
        m.trace = false;
        ManaGrowth.tick(p);
        return p;
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        near(h, actual, expected, what, 1e-6);
    }

    /** Experience points pass through a float, so they match only to {@code tolerance}. */
    private static void near(GameTestHelper h, double actual, double expected, String what, double tolerance) {
        h.assertTrue(Math.abs(actual - expected) < tolerance, what + ": " + actual + ", expected " + expected);
    }

    private static void add(ServerPlayer p, Holder<Attribute> attribute, String id, double amount) {
        p.getAttribute(attribute).addOrUpdateTransientModifier(new AttributeModifier(
                Identifier.fromNamespaceAndPath("logos_test", id), amount, AttributeModifier.Operation.ADD_VALUE));
    }

    private static double term(ServerPlayer p, ManaGrowth.Term t) {
        AttributeModifier mod = p.getAttribute(SpellModifiers.MAX_MANA).getModifier(t.id);
        return mod == null ? 0 : mod.amount();
    }

    /**
     * Each term at its default weight: a fresh player has only the base; full diamond armor (20) gives 80, 30 armor
     * 80 × (1 + ln 1.5) (vanilla's top); toughness 12 gives 40, health 40 gives 100, 1395 points (level 30) 150, 20000 mana spent 100.
     */
    static void terms(GameTestHelper h) {
        ServerPlayer p = player(h);
        PlayerMagic m = PlayerMagic.of(p);
        near(h, PlayerMagic.maxMana(p), 200, "fresh cap");
        add(p, Attributes.ARMOR, "armor", 20);
        ManaGrowth.tick(p);
        near(h, term(p, ManaGrowth.Term.ARMOR), 80, "armor 20");
        add(p, Attributes.ARMOR, "armor", 30);
        ManaGrowth.tick(p);
        near(h, term(p, ManaGrowth.Term.ARMOR), Math.round(80 * (1 + Math.log(1.5))), "armor 30, vanilla's highest");
        add(p, Attributes.ARMOR_TOUGHNESS, "toughness", 12);
        add(p, Attributes.MAX_HEALTH, "health", 20);
        p.giveExperiencePoints(1395);
        m.manaSpent = 20000;
        ManaGrowth.tick(p);
        near(h, term(p, ManaGrowth.Term.TOUGHNESS), 40, "toughness 12");
        near(h, term(p, ManaGrowth.Term.HEALTH), 100, "health 40");
        near(h, term(p, ManaGrowth.Term.XP), 150, "1395 points");
        near(h, term(p, ManaGrowth.Term.PRACTICE), 100, "20000 mana spent");
        near(h, PlayerMagic.maxMana(p), 200 + 112 + 40 + 100 + 150 + 100, "whole cap");
        p.getAttribute(Attributes.ARMOR).removeModifier(Identifier.fromNamespaceAndPath("logos_test", "armor"));
        ManaGrowth.tick(p);
        h.assertTrue(p.getAttribute(SpellModifiers.MAX_MANA).getModifier(ManaGrowth.Term.ARMOR.id) == null, "armor term left behind");
        h.succeed();
    }

    /**
     * Experience counts as gained, never taken back: the first look counts what is held (level 10 = 160 points),
     * spending levels or losing them in death leaves the total, and new points add to it. Mana drawn counts as practice.
     */
    static void lifetimeXp(GameTestHelper h) {
        ServerPlayer p = player(h);
        PlayerMagic m = PlayerMagic.of(p);
        m.xpSeen = -1;
        m.lifetimeXp = 0;
        p.giveExperienceLevels(10);
        ManaGrowth.tick(p);
        near(h, m.lifetimeXp, 160, "first look at level 10", 0.01);
        p.giveExperienceLevels(-10);
        ManaGrowth.tick(p);
        near(h, m.lifetimeXp, 160, "after spending ten levels", 0.01);
        p.giveExperiencePoints(100);
        ManaGrowth.tick(p);
        near(h, m.lifetimeXp, 260, "after 100 more points", 0.01);
        p.experienceLevel = 0;
        p.experienceProgress = 0;
        ManaGrowth.tick(p);
        near(h, m.lifetimeXp, 260, "after losing it all", 0.01);
        near(h, ManaGrowth.levelPoints(30), 1395, "level 30");
        near(h, ManaGrowth.levelPoints(40), 2920, "level 40");
        double spent = m.manaSpent;
        m.mana -= 50;
        ManaGrowth.tick(p);
        near(h, m.manaSpent - spent, 50, "practice from 50 mana drawn");
        h.succeed();
    }

    /** A cap that falls under the pool drains 5% of the surplus a second instead of cutting it, and refills nothing meanwhile. */
    static void overflowDrains(GameTestHelper h) {
        ServerPlayer p = player(h);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 1000;
        ManaGrowth.tick(p);
        m.baseMaxMana = 200;
        double spent = m.manaSpent;
        ManaGrowth.tick(p);
        near(h, m.mana, 1000 - 800 * 0.05 / 20, "one tick of draining");
        near(h, m.manaSpent, spent, "draining is not practice");
        near(h, PlayerMagic.refill(p, 50), 0, "refill above the cap");
        m.mana = m.manaSeen = 200.01;
        ManaGrowth.tick(p);
        near(h, m.mana, 200, "drains down to the cap and no further");
        h.succeed();
    }

    /** Other mods add to the cap through the attribute; the base follows the saved permanent part; regeneration takes its share of the whole cap. */
    static void attribute(GameTestHelper h) {
        ServerPlayer p = player(h);
        PlayerMagic m = PlayerMagic.of(p);
        add(p, SpellModifiers.MAX_MANA, "charm", 300);
        near(h, PlayerMagic.maxMana(p), 500, "base + modifier");
        m.baseMaxMana = 250;
        near(h, PlayerMagic.maxMana(p), 550, "base follows the permanent part");
        near(h, p.getAttribute(SpellModifiers.MAX_MANA).getBaseValue(), 250, "attribute base");
        near(h, SpellModifiers.regenerationPerTick(p), (6 + 0.005 * 550) / 20, "regeneration per tick");
        m.mana = 0;
        near(h, PlayerMagic.refill(p, 1e9), 550, "refill up to the whole cap");
        h.succeed();
    }
}
