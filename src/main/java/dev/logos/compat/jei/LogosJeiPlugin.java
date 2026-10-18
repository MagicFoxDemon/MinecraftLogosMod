package dev.logos.compat.jei;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.client.ClientAlchemyRecipes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** JEI integration, loaded only when JEI is installed: lists what the alchemy table's process cards make. */
@JeiPlugin
public final class LogosJeiPlugin implements IModPlugin {
    private static @Nullable IJeiRuntime runtime;

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new AlchemyRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(AlchemyRecipeCategory.TYPE, ClientAlchemyRecipes.all());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addCraftingStation(AlchemyRecipeCategory.TYPE, LogosMod.ALCHEMY_TABLE_ITEM.get());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** Opens JEI on the alchemy table's recipes (client scenarios). */
    public static void showAlchemyRecipes() {
        live().getRecipesGui().showTypes(List.of(AlchemyRecipeCategory.TYPE));
    }

    /** Opens JEI on the recipes that make {@code item} (client scenarios). */
    public static void showRecipesFor(ItemLike item) {
        IJeiRuntime jei = live();
        jei.getRecipesGui().show(jei.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, new ItemStack(item)));
    }

    private static IJeiRuntime live() {
        if (runtime == null) throw new IllegalStateException("JEI has not started");
        return runtime;
    }
}
