package dev.logos.client.port;

import java.util.List;

/** Stands in for 26.1's {@code ShapedCraftingRecipeDisplay}. */
public record ShapedCraftingRecipeDisplay(int width, int height, List<SlotDisplay> ingredients, SlotDisplay result) implements RecipeDisplay {}
