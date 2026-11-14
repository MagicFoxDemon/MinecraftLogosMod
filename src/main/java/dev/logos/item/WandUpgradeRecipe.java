package dev.logos.item;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;

/**
 * A shapeless wand upgrade that preserves pages, charms, enchantments and stored mana. Matching,
 * recipe-book display and bottle remainders use the normal crafting path; only the result copies
 * the old wand's component patch, as vanilla smithing does.
 */
public class WandUpgradeRecipe extends NormalCraftingRecipe {
    public static final MapCodec<WandUpgradeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.result),
                            Codec.lazyInitialized(() -> Ingredient.CODEC.listOf(2, 9)).fieldOf("ingredients").forGetter(o -> o.ingredients))
                    .apply(i, WandUpgradeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, WandUpgradeRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
            ItemStackTemplate.STREAM_CODEC, o -> o.result,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), o -> o.ingredients,
            WandUpgradeRecipe::new);
    public static final RecipeSerializer<WandUpgradeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final ItemStackTemplate result;
    private final List<Ingredient> ingredients;
    /** Matching and display are a plain shapeless recipe's. */
    private final ShapelessRecipe shape;

    public WandUpgradeRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo, ItemStackTemplate result, List<Ingredient> ingredients) {
        super(commonInfo, bookInfo);
        this.result = result;
        this.ingredients = ingredients;
        this.shape = new ShapelessRecipe(commonInfo, bookInfo, result, ingredients);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return shape.matches(input, level);
    }

    /** Keep only the old wand's changed components, allowing the new item to supply its own base attributes. */
    @Override
    public ItemStack assemble(CraftingInput input) {
        for (ItemStack stack : input.items()) {
            if (stack.getItem() instanceof WandItem) return TransmuteRecipe.createWithOriginalComponents(result, stack);
        }
        return result.create();
    }

    @Override
    public List<RecipeDisplay> display() {
        return shape.display();
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.create(ingredients);
    }

    @Override
    public RecipeSerializer<WandUpgradeRecipe> getSerializer() {
        return SERIALIZER;
    }
}
