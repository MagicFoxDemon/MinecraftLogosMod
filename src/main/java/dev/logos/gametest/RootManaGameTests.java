package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** 根源权限 raises the mana cap by 64000 and 根源剥离 lowers it, never below zero, cutting the pool down with it. */
final class RootManaGameTests {
    private RootManaGameTests() {}

    static void cap(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 200;

        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.ROOT_AUTHORITY.get(), 2));
        p.getMainHandItem().use(p.level(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(m.baseMaxMana == 64200 && m.mana == 200, "root authority must raise the cap to 64200, got " + m.mana + "/" + m.baseMaxMana);
        h.assertTrue(p.getMainHandItem().getCount() == 1, "one root authority must be spent in survival");

        m.mana = 64200;
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.ROOT_STRIP.get(), 2));
        p.getMainHandItem().use(p.level(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(m.baseMaxMana == 200 && m.mana == 200, "root strip must lower the cap to 200 and cut the pool, got " + m.mana + "/" + m.baseMaxMana);
        p.getMainHandItem().use(p.level(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(m.baseMaxMana == 0 && m.mana == 0, "the cap must stop at zero, got " + m.mana + "/" + m.baseMaxMana);
        h.succeed();
    }
}
