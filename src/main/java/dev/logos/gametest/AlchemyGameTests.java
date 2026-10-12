package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

/** In-world tests for alchemy materials and how they plug into vanilla brewing and crafting. */
final class AlchemyGameTests {
    private AlchemyGameTests() {}

    /** Starter culture brews a water bottle, and only a water bottle, into raw mash. */
    static void brewsRawMash(GameTestHelper h) {
        var brewing = h.getLevel().potionBrewing();
        ItemStack culture = new ItemStack(LogosMod.STARTER_CULTURE.get());
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        ItemStack awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        h.assertTrue(brewing.isIngredient(culture), "starter culture is not a brewing ingredient");
        h.assertTrue(brewing.hasMix(water, culture), "water and starter culture do not mix");
        h.assertTrue(brewing.mix(culture, water).is(LogosMod.RAW_MASH.get()), "water and starter culture did not brew raw mash");
        h.assertFalse(brewing.hasMix(awkward, culture), "starter culture brewed something other than water");
        h.succeed();
    }

    /** Sulfur, coal or charcoal and saltpeter craft three gunpowder. */
    static void craftsGunpowder(GameTestHelper h) {
        for (var fuel : List.of(Items.COAL, Items.CHARCOAL)) {
            CraftingInput input = CraftingInput.of(3, 1, List.of(new ItemStack(LogosMod.SALTPETER.get()), new ItemStack(fuel),
                    new ItemStack(LogosMod.SULFUR_POWDER.get())));
            ItemStack out = h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                    .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
            h.assertTrue(out.is(Items.GUNPOWDER) && out.getCount() == 3, "sulfur, " + fuel + " and saltpeter made " + out);
        }
        h.succeed();
    }
}
