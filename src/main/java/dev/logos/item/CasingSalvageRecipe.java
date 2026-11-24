package dev.logos.item;

import java.util.List;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
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
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;

/**
 * Takes an empty 魔力外壳 apart for some of its material back: a new casing gives the result's full count
 * (already less than it took to make), a worn one only its remaining share of that, rounded up, so never
 * nothing. Type {@code logos:casing_salvage}: {@code casing} is the ingredient, {@code result} the most it gives.
 */
public class CasingSalvageRecipe extends NormalCraftingRecipe {
    public static final MapCodec<CasingSalvageRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                            Ingredient.CODEC.fieldOf("casing").forGetter(o -> o.casing),
                            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.result))
                    .apply(i, CasingSalvageRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CasingSalvageRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
            Ingredient.CONTENTS_STREAM_CODEC, o -> o.casing,
            ItemStackTemplate.STREAM_CODEC, o -> o.result,
            CasingSalvageRecipe::new);
    public static final RecipeSerializer<CasingSalvageRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final Ingredient casing;
    private final ItemStackTemplate result;
    /** Matching and display are a plain one-ingredient shapeless recipe's. */
    private final ShapelessRecipe shape;

    public CasingSalvageRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo, Ingredient casing, ItemStackTemplate result) {
        super(commonInfo, bookInfo);
        this.casing = casing;
        this.result = result;
        this.shape = new ShapelessRecipe(commonInfo, bookInfo, result, List.of(casing));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return shape.matches(input, level);
    }

    /** The result's count scaled by how much of the casing's durability is left. */
    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack out = result.create();
        for (ItemStack stack : input.items()) {
            if (stack.isEmpty() || !stack.isDamageableItem()) continue;
            out.setCount(salvaged(out.getCount(), stack));
        }
        return out;
    }

    /** What a casing gives back of {@code full}: its remaining share, rounded up. */
    public static int salvaged(int full, ItemStack casing) {
        int max = casing.getMaxDamage();
        return Math.max(1, Mth.ceil(full * (double) (max - casing.getDamageValue()) / max));
    }

    @Override
    public List<RecipeDisplay> display() {
        return shape.display();
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.create(casing);
    }

    @Override
    public RecipeSerializer<CasingSalvageRecipe> getSerializer() {
        return SERIALIZER;
    }
}
