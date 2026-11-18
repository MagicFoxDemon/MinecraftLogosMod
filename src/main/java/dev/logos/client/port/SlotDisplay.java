package dev.logos.client.port;

import java.util.Arrays;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** Stands in for 26.1's {@code SlotDisplay}: what one place of a recipe takes or gives, as stacks to show in turn. */
public record SlotDisplay(List<ItemStack> stacks) {
    public static final SlotDisplay EMPTY = new SlotDisplay(List.of());

    public static SlotDisplay of(Ingredient ingredient) {
        return ingredient.isEmpty() ? EMPTY : new SlotDisplay(Arrays.asList(ingredient.getItems()));
    }

    public static SlotDisplay of(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : new SlotDisplay(List.of(stack));
    }

    public List<ItemStack> resolveForStacks(ContextMap context) {
        return stacks;
    }

    public ItemStack resolveForFirstStack(ContextMap context) {
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.getFirst();
    }
}
