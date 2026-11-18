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
 * A shapeless wand upgrade that preserves pages, charms, enchantments and stored mana. Matching,
 * recipe-book display and bottle remainders use the normal crafting path; only the result copies
 * the old wand's component patch, as vanilla smithing does.
 */
public class WandUpgradeRecipe extends ShapelessRecipe {
    public static final MapCodec<WandUpgradeRecipe> MAP_CODEC = RecipeSerializer.SHAPELESS_RECIPE.codec().xmap(WandUpgradeRecipe::new, r -> r);
    public static final StreamCodec<RegistryFriendlyByteBuf, WandUpgradeRecipe> STREAM_CODEC =
            RecipeSerializer.SHAPELESS_RECIPE.streamCodec().map(WandUpgradeRecipe::new, r -> r);
    public static final RecipeSerializer<WandUpgradeRecipe> SERIALIZER = new dev.logos.port.SimpleRecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** Matching and display are the plain shapeless recipe's. */
    private WandUpgradeRecipe(ShapelessRecipe shape) {
        super(shape.getGroup(), shape.category(), shape.getResultItem(RegistryAccess.EMPTY), shape.getIngredients());
    }

    /** Keep only the old wand's changed components, allowing the new item to supply its own base attributes. */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = getResultItem(registries).copy();
        for (ItemStack stack : input.items()) {
            if (stack.getItem() instanceof WandItem) {
                result.applyComponents(stack.getComponentsPatch());
                return result;
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<WandUpgradeRecipe> getSerializer() {
        return SERIALIZER;
    }
}
