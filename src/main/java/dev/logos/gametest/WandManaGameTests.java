package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.TieredCharmItem;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Wands and staffs add to the mana cap and its refill in the main hand; graded charms on that wand count their best grade per kind. */
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

    /** The sage wand adds 800 and 8 a second in the main hand and nothing in the off hand; the sage staff 1200 and 12. */
    static void wandBonus(GameTestHelper h) {
        ServerPlayer p = player(h);
        double flat = regen(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SAGE_WAND.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 1000, "cap with the sage wand in hand");
        near(h, regen(p), flat + 8, "regeneration with the sage wand in hand");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.doTick();
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(LogosMod.SAGE_WAND.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200, "cap with the wand in the off hand");
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        p.doTick();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SAGE_STAFF.get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 1400, "cap with the sage staff in hand");
        near(h, regen(p), flat + 12, "regeneration with the sage staff in hand");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200, "a wood wand adds nothing");
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
     * Reservoir I, III and II on one wand count as III alone (+800 on the wand's own 800); a wellspring II beside
     * them adds 3 a second. Taking III off leaves II; putting the wand away takes the charms' share away too.
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
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(charm(LogosMod.RESERVOIR_CHARMS, 1),
                charm(LogosMod.RESERVOIR_CHARMS, 2))));
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200 + 800 + 300, "wand + reservoir II");
        near(h, regen(p), flat + 8, "the wellspring is gone");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.doTick();
        near(h, PlayerMagic.maxMana(p), 200, "with no wand in hand");
        near(h, regen(p), flat, "regeneration with no wand in hand");
        h.succeed();
    }
}
