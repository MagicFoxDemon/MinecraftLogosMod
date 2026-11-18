package dev.logos.client.port;

/** Stands in for 26.1's {@code SmithingRecipeDisplay}. */
public record SmithingRecipeDisplay(SlotDisplay template, SlotDisplay base, SlotDisplay addition, SlotDisplay result) implements RecipeDisplay {}
