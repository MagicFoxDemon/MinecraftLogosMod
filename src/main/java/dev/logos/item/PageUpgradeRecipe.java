package dev.logos.item;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * A shapeless recipe that raises a 术式载页 to a higher grade and keeps what is written on it: the
 * result takes the page's components, as a transmute recipe does, but any number of different
 * materials may go in beside the page (vanilla's transmute allows only one kind). Written like
 * {@code minecraft:crafting_shapeless}, with type {@code logos:page_upgrade}.
 */
public class PageUpgradeRecipe extends ShapelessRecipe {
    public static final MapCodec<PageUpgradeRecipe> MAP_CODEC = RecipeSerializer.SHAPELESS_RECIPE.codec().xmap(PageUpgradeRecipe::new, r -> r);
    public static final StreamCodec<RegistryFriendlyByteBuf, PageUpgradeRecipe> STREAM_CODEC =
            RecipeSerializer.SHAPELESS_RECIPE.streamCodec().map(PageUpgradeRecipe::new, r -> r);
    public static final RecipeSerializer<PageUpgradeRecipe> SERIALIZER = new dev.logos.port.SimpleRecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** Matching and display are the plain shapeless recipe's. */
    private PageUpgradeRecipe(ShapelessRecipe shape) {
        super(shape.getGroup(), shape.category(), shape.getResultItem(RegistryAccess.EMPTY), shape.getIngredients());
    }

    /** The higher page, carrying the old page's spell and name. */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = getResultItem(registries).copy();
        for (ItemStack stack : input.items()) {
            if (stack.getItem() instanceof SpellPageItem) {
                result.applyComponents(stack.getComponentsPatch());
                return result;
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<PageUpgradeRecipe> getSerializer() {
        return SERIALIZER;
    }
}
