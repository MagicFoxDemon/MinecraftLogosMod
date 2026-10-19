package dev.logos.alchemy;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * One alchemical process step ({@code data/<ns>/recipe/…json}, type {@code logos:alchemy}): what
 * a process card of {@link #process} turns its ingredients (and optionally some fluid, in mB) into.
 * Containers are written out: a bottled product lists the glass bottle it takes, and a bottled
 * ingredient that is used up lists the glass bottle it gives back among the results. {@code mana}
 * is what one craft takes from the table (1 if left out): more for the steps that make stronger things.
 * <pre>{@code
 * {"type": "logos:alchemy", "process": "dissolve",
 *  "ingredients": [{"ingredient": "logos:starter_culture"}, {"ingredient": "minecraft:glass_bottle", "count": 3}],
 *  "fluid": {"ingredient": "minecraft:water", "amount": 750},
 *  "results": [{"id": "logos:raw_mash", "count": 3}], "mana": 1}
 * }</pre>
 */
public record AlchemyRecipe(Process process, List<SizedIngredient> ingredients, Optional<SizedFluidIngredient> fluid, List<ItemStackTemplate> results, int mana)
        implements Recipe<AlchemyRecipe.Input> {
    /** Mana a craft takes when the recipe does not say. */
    public static final int DEFAULT_MANA = 1;
    public static final MapCodec<AlchemyRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Process.CODEC.fieldOf("process").forGetter(AlchemyRecipe::process),
            SizedIngredient.NESTED_CODEC.listOf(1, 9).fieldOf("ingredients").forGetter(AlchemyRecipe::ingredients),
            SizedFluidIngredient.CODEC.optionalFieldOf("fluid").forGetter(AlchemyRecipe::fluid),
            ItemStackTemplate.CODEC.listOf(1, 9).fieldOf("results").forGetter(AlchemyRecipe::results),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("mana", DEFAULT_MANA).forGetter(AlchemyRecipe::mana)).apply(i, AlchemyRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlchemyRecipe> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(Process.CODEC), AlchemyRecipe::process,
            SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), AlchemyRecipe::ingredients,
            ByteBufCodecs.optional(SizedFluidIngredient.STREAM_CODEC), AlchemyRecipe::fluid,
            ItemStackTemplate.STREAM_CODEC.apply(ByteBufCodecs.list()), AlchemyRecipe::results,
            ByteBufCodecs.VAR_INT, AlchemyRecipe::mana,
            AlchemyRecipe::new);
    public static final RecipeSerializer<AlchemyRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** A recipe taking the default mana a craft. */
    public AlchemyRecipe(Process process, List<SizedIngredient> ingredients, Optional<SizedFluidIngredient> fluid, List<ItemStackTemplate> results) {
        this(process, ingredients, fluid, results, DEFAULT_MANA);
    }

    /** The items and the fluid on hand, for {@link #matches}; the table itself plans with {@link ProcessCard}. */
    public record Input(List<ItemStack> items, FluidStack fluid) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return items.get(index);
        }

        @Override
        public int size() {
            return items.size();
        }
    }

    /** Whether there is enough of every ingredient and of the fluid (each stack counted once). */
    @Override
    public boolean matches(Input input, Level level) {
        if (fluid.isPresent() && (!fluid.get().ingredient().test(input.fluid()) || input.fluid().getAmount() < fluid.get().amount())) return false;
        int[] left = input.items().stream().mapToInt(ItemStack::getCount).toArray();
        for (SizedIngredient need : ingredients) {
            int n = need.count();
            for (int i = 0; i < left.length && n > 0; i++) {
                if (left[i] == 0 || !need.ingredient().test(input.items().get(i))) continue;
                int take = Math.min(n, left[i]);
                left[i] -= take;
                n -= take;
            }
            if (n > 0) return false;
        }
        return true;
    }

    @Override
    public ItemStack assemble(Input input) {
        return results.getFirst().create();
    }

    @Override
    public boolean showNotification() {
        return false;
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public RecipeSerializer<AlchemyRecipe> getSerializer() {
        return LogosMod.ALCHEMY_SERIALIZER.get();
    }

    @Override
    public RecipeType<AlchemyRecipe> getType() {
        return LogosMod.ALCHEMY_RECIPE.get();
    }

    /** Not made in a crafting grid, so not placeable by the recipe book. */
    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return LogosMod.ALCHEMY_BOOK_CATEGORY.get();
    }

    /** How many kinds of thing it takes; when several recipes could run, the richest one wins. */
    public int kinds() {
        return ingredients.size() + (fluid.isPresent() ? 1 : 0);
    }
}
