package dev.logos.gametest;

import dev.logos.LogosMod;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Checks the basic wand through vanilla equipment updates and attack recovery. */
final class WandGameTests {
    private WandGameTests() {}

    static void swordHandling(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.connection.markClientLoaded();
        double originalDamage = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float emptyHandDelay = player.getCurrentItemAttackStrengthDelay();
        ItemStack sword = Items.WOODEN_SWORD.getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND, sword);
        player.doTick();
        float swordDelay = player.getCurrentItemAttackStrengthDelay();

        ItemStack wand = LogosMod.WAND.get().getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND, wand);
        player.doTick();
        h.assertTrue(wand.getSwingAnimation().equals(sword.getSwingAnimation()), "wand must use the vanilla sword swing");
        h.assertTrue(Math.abs(player.getCurrentItemAttackStrengthDelay() - swordDelay) < .001,
                "wand must recover at the same speed as a sword");
        h.assertTrue(player.getCurrentItemAttackStrengthDelay() > 5, "vanilla full-attack HUD indicator must be eligible");
        h.assertTrue(Math.abs(player.getAttributeValue(Attributes.ATTACK_DAMAGE) - originalDamage) < .001,
                "sword handling must not grant sword damage");
        player.resetAttackStrengthTicker();
        h.assertTrue(player.getAttackStrengthScale(0) == 0, "attack reset must empty the vanilla indicator");
        for (int i = 0; i < 6; i++) player.doTick();
        h.assertTrue(player.getAttackStrengthScale(0) > 0 && player.getAttackStrengthScale(0) < 1,
                "attack indicator must refill gradually after a swing");
        for (int i = 0; i < 7; i++) player.doTick();
        h.assertTrue(player.getAttackStrengthScale(0) == 1, "attack indicator must finish refilling after sword recovery");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, wand);
        player.doTick();
        h.assertTrue(Math.abs(player.getCurrentItemAttackStrengthDelay() - emptyHandDelay) < .001,
                "off-hand wand must follow vanilla sword equipment-slot behavior");
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND, LogosMod.ADEPT_WAND.get().getDefaultInstance());
        player.doTick();
        h.assertTrue(Math.abs(player.getCurrentItemAttackStrengthDelay() - emptyHandDelay) < .001,
                "basic-wand handling must not change the other wand tiers");
        h.succeed();
    }
}
