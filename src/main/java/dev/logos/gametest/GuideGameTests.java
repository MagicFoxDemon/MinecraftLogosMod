package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.GuideBookItem;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

/** 逻各斯手记: a player is handed one the first time they join, and only then; a server may turn that off. */
final class GuideGameTests {
    private GuideGameTests() {}

    private static int books(ServerPlayer p) {
        return p.getInventory().countItem(LogosMod.GUIDE_BOOK.get());
    }

    /** Joining hands the guide over, in the hotbar's last slot rather than the hand; joining again, even without it, does not. */
    static void givenOnce(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        h.assertTrue(books(p) == 1, "a player joining for the first time holds " + books(p) + " guides");
        h.assertTrue(p.getData(LogosMod.GUIDE_GIVEN.get()), "the player is not marked as handed the guide");
        h.assertTrue(p.getInventory().getItem(8).is(LogosMod.GUIDE_BOOK.get()), "the guide is not in the hotbar's last slot");
        h.assertTrue(!p.getMainHandItem().is(LogosMod.GUIDE_BOOK.get()), "the guide was put in the player's hand");
        GuideBookItem.welcome(p);
        h.assertTrue(books(p) == 1, "joining again handed another guide: " + books(p));
        p.getInventory().clearContent();
        GuideBookItem.welcome(p);
        h.assertTrue(books(p) == 0, "a player who threw the guide away was handed another on joining");
        h.succeed();
    }

    /** With the gift turned off, a new player gets nothing; once it is on again, they get theirs. */
    static void notGivenWhenOff(GameTestHelper h) {
        ServerPlayer p;
        LogosConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(false);
        try {
            @SuppressWarnings("removal")
            ServerPlayer joined = h.makeMockServerPlayerInLevel();
            p = joined;
            h.assertTrue(books(p) == 0 && !p.getData(LogosMod.GUIDE_GIVEN.get()), "the guide was handed over with the gift turned off");
        } finally {
            LogosConfig.GIVE_GUIDE_ON_FIRST_JOIN.set(true);
        }
        GuideBookItem.welcome(p);
        h.assertTrue(books(p) == 1, "turned on again, the player holds " + books(p) + " guides");
        h.succeed();
    }

    /** A book and a lapis lazuli make another. */
    static void recipe(GameTestHelper h) {
        CraftingInput input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.LAPIS_LAZULI)));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(LogosMod.GUIDE_BOOK.get()), "a book and lapis made " + out);
        h.succeed();
    }
}
