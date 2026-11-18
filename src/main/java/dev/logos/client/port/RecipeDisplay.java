package dev.logos.client.port;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

/**
 * Stands in for 26.1's {@code RecipeDisplay}: how a recipe is shown. 1.21.1 recipes have no displays of their own;
 * {@link #of} makes one for a shaped, shapeless or smithing-transform recipe.
 */
public interface RecipeDisplay {
    SlotDisplay result();

    static List<RecipeDisplay> of(Recipe<?> recipe, HolderLookup.Provider registries) {
        SlotDisplay result = SlotDisplay.of(recipe.getResultItem(registries));
        return switch (recipe) {
            case ShapedRecipe shaped -> List.of(new ShapedCraftingRecipeDisplay(shaped.getWidth(), shaped.getHeight(), slots(shaped.getIngredients()), result));
            case ShapelessRecipe shapeless -> List.of(new ShapelessCraftingRecipeDisplay(slots(shapeless.getIngredients()), result));
            case SmithingTransformRecipe smithing -> List.of(new SmithingRecipeDisplay(SlotDisplay.of(smithing.template), SlotDisplay.of(smithing.base),
                    SlotDisplay.of(smithing.addition), result));
            default -> List.of();
        };
    }

    private static List<SlotDisplay> slots(List<Ingredient> ingredients) {
        List<SlotDisplay> out = new ArrayList<>();
        for (Ingredient i : ingredients) out.add(SlotDisplay.of(i));
        return out;
    }
}
