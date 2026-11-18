package dev.logos.client.port;

import java.util.List;

/** Stands in for 26.1's {@code ShapelessCraftingRecipeDisplay}. */
public record ShapelessCraftingRecipeDisplay(List<SlotDisplay> ingredients, SlotDisplay result) implements RecipeDisplay {}
