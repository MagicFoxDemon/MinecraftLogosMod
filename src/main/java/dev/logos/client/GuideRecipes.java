package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import dev.logos.client.port.ContextMap;
import dev.logos.client.port.RecipeDisplay;
import dev.logos.client.port.SlotDisplayContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

/** The crafting and smithing recipes the client knows (1.21.1 sends them all), for 逻各斯手记 to draw. */
public final class GuideRecipes {
    private GuideRecipes() {}

    /** What the recipes are drawn with: the level's registries; null outside a world. */
    static @javax.annotation.Nullable ContextMap context() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : SlotDisplayContext.fromLevel(level);
    }

    /** How every crafting and smithing recipe that makes {@code item} is shown, in the order the server sent them. */
    static List<RecipeDisplay> making(Item item) {
        var level = Minecraft.getInstance().level;
        ContextMap context = context();
        List<RecipeDisplay> out = new ArrayList<>();
        if (level == null || context == null) return out;
        var recipes = level.getRecipeManager();
        for (var holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) keep(RecipeDisplay.of(holder.value(), context.registries()), item, context, out);
        for (var holder : recipes.getAllRecipesFor(RecipeType.SMITHING)) keep(RecipeDisplay.of(holder.value(), context.registries()), item, context, out);
        return out;
    }

    private static void keep(List<RecipeDisplay> displays, Item item, ContextMap context, List<RecipeDisplay> into) {
        for (RecipeDisplay display : displays) {
            if (display.result().resolveForFirstStack(context).is(item)) into.add(display);
        }
    }
}
