package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.lang.Status;
import dev.logos.runtime.Charging;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** In-world tests for wand charging: mana flows in while right-click is held, and casts only once full. */
final class ChargingGameTests {
    /** 100 asked of a crystal page (holds 150); marking the caster costs 1 of it. */
    private static final String MARK = "(spell :name \"蓄\" :mana 100 (mark caster #charged))";

    private ChargingGameTests() {}

    /** A player with 1000 of 1000 mana and an oak wand (flow 40, surge 20) holding {@code src} on a crystal page. */
    private static ServerPlayer caster(GameTestHelper h, String src) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1000;
        m.trace = false;
        ItemStack wand = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, src, null))));
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        return p;
    }

    private static ItemStack wand(ServerPlayer p) {
        return p.getMainHandItem();
    }

    /** Presses right-click; true if charging started. */
    private static boolean press(GameTestHelper h, ServerPlayer p) {
        return wand(p).getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME && p.isUsingItem();
    }

    private static void hold(GameTestHelper h, ServerPlayer p, int ticks) {
        for (int i = 0; i < ticks; i++) wand(p).getItem().onUseTick(h.getLevel(), p, wand(p), p.getUseItemRemainingTicks());
    }

    private static double freeManaNear(GameTestHelper h, ServerPlayer p) {
        return h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(p.position(), p.position()).inflate(8))
                .stream().mapToDouble(FreeManaEntity::amount).sum();
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, what + ": expected " + expected + ", got " + actual);
    }

    /**
     * 100 mana on an oak wand with a full 1000 pool (surge 20 × 2): √(40² + 2·40·100) − 40 over 40 = 1.45 s, 29 ticks.
     * The pool drains as it charges, letting go before full casts nothing, and once full it casts on release.
     */
    static void chargesThenCasts(GameTestHelper h) {
        ServerPlayer p = caster(h, MARK);
        int ticks = Charging.ticks(100, 40, 40);
        h.assertTrue(ticks == 29, "100 mana should take 29 ticks, not " + ticks);
        h.assertTrue(press(h, p), "charging did not start");
        hold(h, p, ticks - 1);
        var c = Charging.of(p);
        h.assertTrue(c != null && !c.full() && c.ticks == ticks, "the charge should be one tick short of full");
        near(h, PlayerMagic.of(p).mana, 1000 - c.injected, "the pool pays what was injected");
        hold(h, p, 1);
        h.assertTrue(c.full(), "the charge should be full");
        near(h, PlayerMagic.of(p).mana, 900, "a full charge paid all 100");
        hold(h, p, 40);
        near(h, PlayerMagic.of(p).mana, 900, "holding a full charge costs nothing more");
        h.assertTrue(StatusData.stacks(p, new Status("#charged")) == 0, "nothing is cast before release");
        p.releaseUsingItem();
        h.assertTrue(StatusData.stacks(p, new Status("#charged")) == 1, "releasing a full charge casts");
        h.assertTrue(Charging.of(p) == null, "a cast charge is spent");
        h.succeed();
    }

    /** Letting go early casts nothing and sets every injected point free beside the player. */
    static void earlyReleaseRefunds(GameTestHelper h) {
        ServerPlayer p = caster(h, MARK);
        h.assertTrue(press(h, p), "charging did not start");
        hold(h, p, 10);
        double injected = Charging.of(p).injected;
        h.assertTrue(injected > 0 && injected < 100, "ten ticks should inject part of it, got " + injected);
        p.releaseUsingItem();
        h.assertTrue(StatusData.stacks(p, new Status("#charged")) == 0, "an unfinished charge must not cast");
        h.assertTrue(Charging.of(p) == null, "the charge should be gone");
        near(h, freeManaNear(h, p), injected, "free mana set loose");
        near(h, PlayerMagic.of(p).mana, 1000 - injected, "the pool is not refunded directly");
        h.succeed();
    }

    /** Without the mana to fill it, a charge does not start; neither does a wand in the off hand. */
    static void refusesWhatItCannotFill(GameTestHelper h) {
        ServerPlayer p = caster(h, MARK);
        PlayerMagic.of(p).mana = 99;
        h.assertFalse(press(h, p), "a charge started with 99 of 100 mana");
        near(h, PlayerMagic.of(p).mana, 99, "a refused charge takes nothing");
        PlayerMagic.of(p).mana = 1000;
        ItemStack w = wand(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, w);
        h.assertTrue(w.getItem().use(h.getLevel(), p, InteractionHand.OFF_HAND) == InteractionResult.FAIL, "an off-hand wand charged");
        h.succeed();
    }

    /** Choosing another spell on the wheel mid-charge stops the charge and frees its mana. */
    static void switchingSpellCancels(GameTestHelper h) {
        ServerPlayer p = caster(h, MARK);
        ItemStack w = wand(p);
        w.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, MARK, null),
                SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, "(spell :name \"另\" :mana 10 (mark caster #other))", null))));
        h.assertTrue(press(h, p), "charging did not start");
        hold(h, p, 10);
        double injected = Charging.of(p).injected;
        w.set(LogosMod.SELECTED.get(), 1);
        hold(h, p, 1);
        h.assertTrue(Charging.of(p) == null && !p.isUsingItem(), "switching spell should stop the charge");
        near(h, freeManaNear(h, p), injected, "free mana set loose");
        h.succeed();
    }

    /** Pages still cap the injection: a basic page charges only its 50, however much the spell asks. */
    static void pageCapsCharge(GameTestHelper h) {
        ServerPlayer p = caster(h, MARK);
        wand(p).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(SpellPages.inscribed(MARK, null))));
        h.assertTrue(press(h, p), "charging did not start");
        hold(h, p, 200);
        var c = Charging.of(p);
        near(h, c.in.mana(), 50, "the basic page's capacity");
        near(h, PlayerMagic.of(p).mana, 950, "only the page's 50 was drawn");
        h.succeed();
    }

    /** A charge takes the colour of the element its spell names most, life for a heal naming none, arcane otherwise; tiers go by injection. */
    static void chargeLooks(GameTestHelper h) {
        h.assertTrue(Charging.element(dev.logos.runtime.Casting.compile(
                "(spell :mana 50 (damage (enemies :radius 4) standard fire) (damage (enemies :radius 4) minor fire) (damage (enemies :radius 4) minor frost))"))
                == dev.logos.lang.Element.FIRE, "two fires and a frost should look fiery");
        h.assertTrue(Charging.element(dev.logos.runtime.Casting.compile("(spell :mana 50 :players true (heal (players :radius 8) 6))")) == dev.logos.lang.Element.LIFE,
                "a heal should look like life");
        h.assertTrue(Charging.element(dev.logos.runtime.Casting.compile(MARK)) == dev.logos.lang.Element.ARCANE, "a mark should look arcane");
        h.assertTrue(Charging.tier(99) == 0 && Charging.tier(100) == 1 && Charging.tier(799) == 1 && Charging.tier(800) == 2
                && Charging.tier(3199) == 2 && Charging.tier(3200) == 3, "tiers break at 100, 800 and 3200");
        h.succeed();
    }
}
