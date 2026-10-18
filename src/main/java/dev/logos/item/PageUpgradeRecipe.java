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
 * A shapeless recipe that raises a 术式载页 to a higher grade and keeps what is written on it: the
 * result takes the page's components, as a transmute recipe does, but any number of different
 * materials may go in beside the page (vanilla's transmute allows only one kind). Written like
 * {@code minecraft:crafting_shapeless}, with type {@code logos:page_upgrade}.
 */
public class PageUpgradeRecipe extends NormalCraftingRecipe {
    public static final MapCodec<PageUpgradeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.result),
                            Codec.lazyInitialized(() -> Ingredient.CODEC.listOf(2, 9)).fieldOf("ingredients").forGetter(o -> o.ingredients))
                    .apply(i, PageUpgradeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, PageUpgradeRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
            ItemStackTemplate.STREAM_CODEC, o -> o.result,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), o -> o.ingredients,
            PageUpgradeRecipe::new);
    public static final RecipeSerializer<PageUpgradeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final ItemStackTemplate result;
    private final List<Ingredient> ingredients;
    /** Matching and display are a plain shapeless recipe's. */
    private final ShapelessRecipe shape;

    public PageUpgradeRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo, ItemStackTemplate result, List<Ingredient> ingredients) {
        super(commonInfo, bookInfo);
        this.result = result;
        this.ingredients = ingredients;
        this.shape = new ShapelessRecipe(commonInfo, bookInfo, result, ingredients);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return shape.matches(input, level);
    }

    /** The higher page, carrying the old page's spell and name. */
    @Override
    public ItemStack assemble(CraftingInput input) {
        for (ItemStack stack : input.items()) {
            if (stack.getItem() instanceof SpellPageItem) return TransmuteRecipe.createWithOriginalComponents(result, stack);
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
    public RecipeSerializer<PageUpgradeRecipe> getSerializer() {
        return SERIALIZER;
    }
}
