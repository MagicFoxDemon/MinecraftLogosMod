package dev.logos.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.TieredCharmItem;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Wands and staffs add to the mana refill in the main hand, and to the mana cap while carried anywhere, the best one
 * only; graded charms count their best grade per kind, a reservoir's on its wand's cap.
 */
final class WandManaGameTests {
    private WandManaGameTests() {}

    private static ServerPlayer player(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 200;
        m.trace = false;
        return p;
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, what + ": " + actual + ", expected " + expected);
    }

    private static double regen(ServerPlayer p) {
        return p.getAttributeValue(SpellModifiers.MANA_REGENERATION);
    }

    /** A slot in the inventory away from the hotbar, where a wand is only carried. */
    private static final int PACK = 20;

    /**
     * The sage wand adds 800 to the cap in the main hand, the off hand or the pack alike, and 8 a second only in the
     * main hand; the sage staff 1200 and 12. Carrying both counts the staff alone.
     */
    static void wandBonus(GameTestHelper h) {
        ServerPlayer p = player(h);
        double flat = regen(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SAGE_WAND.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 1000, "cap with the sage wand in hand");
        near(h, regen(p), flat + 8, "regeneration with the sage wand in hand");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.getInventory().setItem(PACK, new ItemStack(LogosMod.SAGE_WAND.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 1000, "cap with the wand in the pack");
        near(h, regen(p), flat, "regeneration with the wand in the pack");
        p.getInventory().setItem(PACK, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(LogosMod.SAGE_WAND.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 1000, "cap with the wand in the off hand");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SAGE_STAFF.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 1400, "cap with the sage staff in hand and the sage wand in the off hand: the staff alone");
        near(h, regen(p), flat + 12, "regeneration with the sage staff in hand");
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200, "a wood wand adds nothing");
        h.succeed();
    }

    /** Taking up something else keeps the cap and so the pool above the base; losing the wand drains it as before. */
    static void switchingKeepsThePool(GameTestHelper h) {
        ServerPlayer p = player(h);
        PlayerMagic m = PlayerMagic.of(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SAGE_WAND.get()));
        p.doTick();
        m.mana = PlayerMagic.maxMana(p);
        ItemStack wand = p.getMainHandItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.getInventory().setItem(PACK, wand);
        for (int i = 0; i < 20; i++) p.doTick();
        near(h, m.mana, 1000, "the pool a second after putting the wand away");
        p.getInventory().setItem(PACK, ItemStack.EMPTY);
        for (int i = 0; i < 20; i++) p.doTick();
        h.assertTrue(m.mana < 1000, "with no wand carried the surplus should drain, the pool holds " + m.mana);
        h.succeed();
    }

    private static ItemStack wand(ItemStack... charms) {
        ItemStack wand = new ItemStack(LogosMod.SAGE_WAND.get());
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(charms)));
        return wand;
    }

    private static ItemStack charm(List<net.neoforged.neoforge.registries.DeferredItem<TieredCharmItem>> family, int grade) {
        return new ItemStack(family.get(grade - 1).get());
    }

    /**
     * Reservoir I, III and II on one wand count as III alone (+800 on the wand's own 800), in the hand or the pack; a
     * wellspring II beside them adds 3 a second in the hand only. Taking III off leaves II. A bare sage staff carried
     * beside it (1200) beats the wand's 800 + 100 with a reservoir I; putting both away takes it all.
     */
    static void charmsBestGrade(GameTestHelper h) {
        ServerPlayer p = player(h);
        double flat = regen(p);
        ItemStack wand = wand(charm(LogosMod.RESERVOIR_CHARMS, 1), charm(LogosMod.RESERVOIR_CHARMS, 3), charm(LogosMod.RESERVOIR_CHARMS, 2),
                charm(LogosMod.WELLSPRING_CHARMS, 2));
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200 + 800 + 800, "wand + reservoir III");
        near(h, regen(p), flat + 8 + 3, "wand + wellspring II");
        List<Component> lines = new ArrayList<>();
        wand.getItem().appendHoverText(wand, Item.TooltipContext.of(h.getLevel()), TooltipDisplay.DEFAULT, lines::add, TooltipFlag.NORMAL);
        h.assertTrue(lines.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals("logos.wand.carried_max_mana")
                && List.of(t.getArgs()).contains("+1600")), "the wand's tooltip should read its carried cap +1600: " + lines);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.getInventory().setItem(PACK, wand);
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200 + 800 + 800, "the same wand in the pack");
        near(h, regen(p), flat, "regeneration with the wand in the pack");
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(charm(LogosMod.RESERVOIR_CHARMS, 1),
                charm(LogosMod.RESERVOIR_CHARMS, 2))));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200 + 800 + 300, "wand + reservoir II");
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(charm(LogosMod.RESERVOIR_CHARMS, 1))));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SAGE_STAFF.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200 + 1200, "a bare sage staff beside a wand with reservoir I: the staff alone");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.getInventory().setItem(PACK, ItemStack.EMPTY);
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200, "with no wand carried");
        near(h, regen(p), flat, "regeneration with no wand carried");
        h.succeed();
    }
}
