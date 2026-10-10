package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.block.CopyTableBlockEntity;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellBag;
import dev.logos.item.SpellPages;
import dev.logos.runtime.Casting;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** In-world tests for page grades, Formula Immolation, free mana and the copy table. */
final class PageGameTests {
    /** 120 asked, more than a basic page's 50; marking costs 1. */
    private static final String HUNGRY = "(spell :name \"饥\" :mana 120 (mark caster #fed))";

    private PageGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1000;
        m.trace = false;
        return p;
    }

    /** A wand in the main hand with {@code page} in its first slot, enchanted with Formula Immolation at {@code level} (0: none). */
    private static ItemStack wand(GameTestHelper h, ServerPlayer p, ItemStack page, int level) {
        ItemStack wand = new ItemStack(LogosMod.WAND.get());
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(page)));
        if (level > 0) {
            wand.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(LogosMod.FORMULA_IMMOLATION), level);
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        return wand;
    }

    private static SpellInstance castFromWand(GameTestHelper h, ServerPlayer p, ItemStack wand) {
        SpellBag.Entry entry = SpellBag.selected(p, wand);
        h.assertTrue(entry != null, "the wand has no castable page");
        SpellInstance inst = Casting.cast(p, entry, wand, null, null);
        h.assertTrue(inst != null, "cast was refused");
        return inst;
    }

    private static double freeManaNear(GameTestHelper h, ServerPlayer p) {
        return h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(p.position(), p.position()).inflate(14))
                .stream().mapToDouble(FreeManaEntity::amount).sum();
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-3, what + ": expected " + expected + ", got " + actual);
    }

    /** A page injects no more than it holds; the rest of the request is simply not paid. */
    static void capacityCapsInjection(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ItemStack wand = wand(h, p, SpellPages.inscribed(HUNGRY, null), 0);
        SpellInstance inst = castFromWand(h, p, wand);
        near(h, inst.injected, PageTier.BASIC.capacity(), "injected");
        near(h, PlayerMagic.of(p).mana, 1000 - PageTier.BASIC.capacity(), "player mana");
        h.assertTrue(SpellPages.isInscribed(PageContainer.get(wand, 0)), "the page should be intact without Formula Immolation");
        // A crystal page holds 150, enough for the whole 120.
        ItemStack crystal = wand(h, p, SpellPages.inscribed(PageTier.CRYSTAL, HUNGRY, null), 0);
        near(h, castFromWand(h, p, crystal).injected, 120, "injected from a crystal page");
        h.succeed();
    }

    /** Immolation I doubles the capacity (100 < 120); II triples it (150 ≥ 120, so exactly 120). Either way the page burns. */
    static void immolationBurnsPage(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ItemStack one = wand(h, p, SpellPages.inscribed(HUNGRY, null), 1);
        near(h, castFromWand(h, p, one).injected, 100, "Immolation I");
        ItemStack ember = PageContainer.get(one, 0);
        h.assertTrue(SpellPages.isEmber(ember), "the page should have become an ember, got " + ember);
        h.assertTrue(HUNGRY.equals(ember.get(LogosMod.SPELL_SOURCE.get())), "the ember lost its spell");
        h.assertTrue(SpellBag.selected(p, one) == null, "an ember must not be castable");

        ItemStack two = wand(h, p, SpellPages.inscribed(HUNGRY, null), 2);
        near(h, castFromWand(h, p, two).injected, 120, "Immolation II");
        h.assertTrue(SpellPages.isEmber(PageContainer.get(two, 0)), "Immolation II should burn the page too");

        // Within capacity nothing burns, enchanted or not.
        ItemStack small = wand(h, p, SpellPages.inscribed("(spell :mana 20 (mark caster #ok))", null), 3);
        near(h, castFromWand(h, p, small).injected, 20, "within capacity");
        h.assertTrue(SpellPages.isInscribed(PageContainer.get(small, 0)), "a spell within capacity must not burn its page");

        // :immolate false keeps the page and stays at capacity.
        ItemStack kept = wand(h, p, SpellPages.inscribed("(spell :mana 120 :immolate false (mark caster #ok))", null), 3);
        near(h, castFromWand(h, p, kept).injected, PageTier.BASIC.capacity(), ":immolate false");
        h.assertTrue(SpellPages.isInscribed(PageContainer.get(kept, 0)), ":immolate false must keep the page");
        h.succeed();
    }

    /** A natural end frees everything left; an extinguished spell frees only the page's recovery share. */
    static void freeManaFromLeftovers(GameTestHelper h) {
        ServerPlayer p = caster(h);
        SpellInstance calm = Casting.cast(p, Casting.compile("(spell :mana 30 (mark caster #a))"), null, null);
        h.assertTrue(calm != null && calm.finished() && calm.endedNaturally(), "expected a natural end");
        near(h, freeManaNear(h, p), 29, "free mana after a natural end");
        h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(p.position(), p.position()).inflate(14)).forEach(e -> e.discard());

        // Damage 100 costs 80 of 30: the spell goes out with all 30 left as entropic mana; a page-less cast recovers at the basic 10%.
        SpellInstance starved = Casting.cast(p, Casting.compile("(spell :mana 30 (damage caster 100))"), null, null);
        h.assertTrue(starved != null && starved.dead && !starved.endedNaturally(), "expected the spell to run dry");
        near(h, freeManaNear(h, p), 30 * PageTier.BASIC.recovery(), "free mana after running dry");
        h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(p.position(), p.position()).inflate(14)).forEach(e -> e.discard());

        // stop ends the spell on purpose: its leftover is free, not entropic.
        Casting.cast(p, Casting.compile("(spell :mana 30 (stop))"), null, null);
        near(h, freeManaNear(h, p), 30, "free mana after stop");
        h.succeed();
    }

    /** Gathering converts at the player's efficiency (0.5) and leaves what does not fit. */
    static void freeManaGathered(GameTestHelper h) {
        ServerPlayer p = caster(h);
        PlayerMagic m = PlayerMagic.of(p);
        Casting.cast(p, Casting.compile("(spell :mana 41 (mark caster #a))"), null, null);
        // Full for now, so the free mana neither follows nor is gathered before the checks below.
        m.mana = m.maxMana;
        h.runAfterDelay(20, () -> {
            m.mana = 990;
            var orbs = h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(p.position(), p.position()).inflate(14));
            h.assertTrue(orbs.size() == 1, "expected one free mana, found " + orbs.size());
            FreeManaEntity orb = orbs.getFirst();
            near(h, orb.amount(), 40, "released");
            // Room for 10 at 50%: uses 20 of the 40, the other 20 stays.
            orb.playerTouch(p);
            near(h, m.mana, 1000, "player mana");
            near(h, orb.amount(), 20, "left in the free mana");
            m.mana = 500;
            orb.playerTouch(p);
            near(h, m.mana, 510, "player mana after gathering the rest");
            h.assertTrue(orb.isRemoved(), "fully gathered free mana should disappear");
            h.succeed();
        });
    }

    /** The copy table writes the original's spell onto an empty page of any grade, keeps the original and uses one page. */
    static void copyTableCopies(GameTestHelper h) {
        BlockPos at = new BlockPos(1, 2, 1);
        h.setBlock(at, LogosMod.COPY_TABLE.get());
        CopyTableBlockEntity table = h.getBlockEntity(at, CopyTableBlockEntity.class);
        String src = "(spell :name \"抄\" :mana 10 (mark caster #c))";
        table.inputs().setItem(CopyTableBlockEntity.ORIGINAL, SpellPages.burn(SpellPages.inscribed(src, null)));
        table.inputs().setItem(CopyTableBlockEntity.BLANK, new ItemStack(PageTier.CRYSTAL.blank(), 3));
        ItemStack copy = table.result();
        h.assertTrue(copy.is(PageTier.CRYSTAL.inscribed()), "the copy should be a crystal page, got " + copy);
        h.assertTrue(src.equals(copy.get(LogosMod.SPELL_SOURCE.get())), "the copy lost the spell");
        table.onCopyTaken(null);
        h.assertTrue(table.inputs().getItem(CopyTableBlockEntity.BLANK).getCount() == 2, "one empty page should be used up");
        h.assertTrue(SpellPages.isEmber(table.inputs().getItem(CopyTableBlockEntity.ORIGINAL)), "the original should stay");
        // A written page cannot serve as the empty page.
        table.inputs().setItem(CopyTableBlockEntity.BLANK, SpellPages.inscribed(src, null));
        h.assertTrue(table.result().isEmpty(), "a written page is not an empty page");
        h.succeed();
    }
}
