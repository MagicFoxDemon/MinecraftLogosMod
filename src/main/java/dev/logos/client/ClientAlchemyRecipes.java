package dev.logos.client;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.alchemy.AlchemyRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.RecipeHolder;

/** The alchemy recipes this client knows, for recipe viewers: on 1.21.1 the server sends every recipe. */
public final class ClientAlchemyRecipes {
    private ClientAlchemyRecipes() {}

    public static List<RecipeHolder<AlchemyRecipe>> all() {
        var level = Minecraft.getInstance().level;
        return level == null ? List.of() : level.getRecipeManager().getAllRecipesFor(LogosMod.ALCHEMY_RECIPE.get());
    }
}
