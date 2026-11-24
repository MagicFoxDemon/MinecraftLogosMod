package dev.logos.item;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
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
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;

/**
 * A shapeless recipe that seals materials into a 魔力外壳 to make a 魔力电池: the battery remembers how worn
 * the casing was ({@code logos:casing_wear}), so the casing it leaves when spent keeps wearing out. Written
 * like {@code minecraft:crafting_shapeless}, with type {@code logos:mana_battery}; one ingredient is the
 * battery's own casing.
 */
public class ManaBatteryRecipe extends NormalCraftingRecipe {
    public static final MapCodec<ManaBatteryRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                            Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                            CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                            ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.result),
                            Codec.lazyInitialized(() -> Ingredient.CODEC.listOf(2, 9)).fieldOf("ingredients").forGetter(o -> o.ingredients))
                    .apply(i, ManaBatteryRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaBatteryRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
            ItemStackTemplate.STREAM_CODEC, o -> o.result,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), o -> o.ingredients,
            ManaBatteryRecipe::new);
    public static final RecipeSerializer<ManaBatteryRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final ItemStackTemplate result;
    private final List<Ingredient> ingredients;
    /** Matching and display are a plain shapeless recipe's. */
    private final ShapelessRecipe shape;

    public ManaBatteryRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo, ItemStackTemplate result, List<Ingredient> ingredients) {
        super(commonInfo, bookInfo);
        this.result = result;
        this.ingredients = ingredients;
        this.shape = new ShapelessRecipe(commonInfo, bookInfo, result, ingredients);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return shape.matches(input, level);
    }

    /** The battery, carrying the wear of the casing it was sealed in. */
    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack out = result.create();
        if (!(out.getItem() instanceof ManaBatteryItem battery)) return out;
        for (ItemStack stack : input.items()) {
            if (stack.is(battery.casing()) && stack.getDamageValue() > 0) out.set(LogosMod.CASING_WEAR.get(), stack.getDamageValue());
        }
        return out;
    }

    /** What goes in, the casing among it; the tests check no ingredient gives mana back. */
    public List<Ingredient> ingredients() {
        return ingredients;
    }

    public ItemStackTemplate result() {
        return result;
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
    public RecipeSerializer<ManaBatteryRecipe> getSerializer() {
        return SERIALIZER;
    }
}
