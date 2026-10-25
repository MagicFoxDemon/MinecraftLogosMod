package dev.logos.alchemy;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * What a seed grows into in a cultivation jar ({@code data/<ns>/recipe/…json}, type {@code logos:cultivation}),
 * optionally only in a given medium. {@code plant} is the {@link JarPlant} drawn in the jar (what the seed
 * places if left out); {@code harvest}, if given, is what each harvest yields while the seed stays planted,
 * otherwise the plant's own loot. {@code growth} is the chance a random tick grows it a stage, and {@code mana} what each
 * stage takes from the jar.
 * <pre>{@code
 * {"type": "logos:cultivation", "seed": "minecraft:carrot", "medium": "logos:solar_amalgam",
 *  "harvest": [{"id": "minecraft:golden_carrot", "count": 3}]}
 * }</pre>
 * A seed with no recipe whose block is a {@link JarPlant} (a crop, a berry bush…) still grows, at
 * {@link #DEFAULT_GROWTH}, and yields its loot.
 */
public record CultivationRecipe(Ingredient seed, Optional<Ingredient> medium, Optional<Block> plant, List<ItemStackTemplate> harvest,
        float growth, int mana) implements Recipe<CultivationRecipe.Input> {
    /** Half a stage a random tick: a little faster than the best-kept field, three times a lone farmland block. */
    public static final float DEFAULT_GROWTH = 0.5f;
    public static final MapCodec<CultivationRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Ingredient.CODEC.fieldOf("seed").forGetter(CultivationRecipe::seed),
            Ingredient.CODEC.optionalFieldOf("medium").forGetter(CultivationRecipe::medium),
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("plant").forGetter(CultivationRecipe::plant),
            ItemStackTemplate.CODEC.listOf(0, 9).optionalFieldOf("harvest", List.of()).forGetter(CultivationRecipe::harvest),
            Codec.floatRange(0, 1).optionalFieldOf("growth", DEFAULT_GROWTH).forGetter(CultivationRecipe::growth),
            ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("mana", 0).forGetter(CultivationRecipe::mana)).apply(i, CultivationRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CultivationRecipe> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, CultivationRecipe::seed,
            Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC, CultivationRecipe::medium,
            ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.BLOCK)), CultivationRecipe::plant,
            ItemStackTemplate.STREAM_CODEC.apply(ByteBufCodecs.list()), CultivationRecipe::harvest,
            ByteBufCodecs.FLOAT, CultivationRecipe::growth,
            ByteBufCodecs.VAR_INT, CultivationRecipe::mana,
            CultivationRecipe::new);
    public static final RecipeSerializer<CultivationRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** The planted seed and the medium the jar is working with (empty if none). */
    public record Input(ItemStack seed, ItemStack medium) implements RecipeInput {
        @Override
        public ItemStack getItem(int index) {
            return index == 0 ? seed : medium;
        }

        @Override
        public int size() {
            return 2;
        }
    }

    /** What grows of {@code seed} in {@code medium}: the recipe that names the medium first, then one for any medium. Server side only. */
    public static @Nullable CultivationRecipe find(Level level, ItemStack seed, ItemStack medium) {
        if (seed.isEmpty() || !(level.recipeAccess() instanceof net.minecraft.world.item.crafting.RecipeManager recipes)) return null;
        CultivationRecipe any = null;
        for (RecipeHolder<CultivationRecipe> h : recipes.recipeMap().byType(LogosMod.CULTIVATION_RECIPE.get())) {
            CultivationRecipe r = h.value();
            if (!r.seed.test(seed)) continue;
            if (r.medium.isEmpty()) {
                if (any == null) any = r;
            } else if (!medium.isEmpty() && r.medium.get().test(medium)) return r;
        }
        if (any != null) return any;
        // Any plant that grows in one block on its age (JarPlant) grows in a jar, yielding its loot.
        return seed.getItem() instanceof BlockItem item && JarPlant.of(item.getBlock()) != null
                ? new CultivationRecipe(Ingredient.of(seed.getItem()), Optional.empty(), Optional.empty(), List.of(), DEFAULT_GROWTH, 0) : null;
    }

    /** The plant drawn in the jar: {@code plant}, or what the seed itself places. */
    public @Nullable JarPlant jarPlant(ItemStack seedStack) {
        return JarPlant.of(plant.orElse(seedStack.getItem() instanceof BlockItem item ? item.getBlock() : null));
    }

    @Override
    public boolean matches(Input input, Level level) {
        return seed.test(input.seed()) && medium.map(m -> m.test(input.medium())).orElse(true);
    }

    @Override
    public ItemStack assemble(Input input) {
        return harvest.isEmpty() ? ItemStack.EMPTY : harvest.getFirst().create();
    }

    /** Grown in a jar, never crafted: kept out of the recipe book. */
    @Override
    public boolean isSpecial() {
        return true;
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
    public RecipeSerializer<CultivationRecipe> getSerializer() {
        return LogosMod.CULTIVATION_SERIALIZER.get();
    }

    @Override
    public RecipeType<CultivationRecipe> getType() {
        return LogosMod.CULTIVATION_RECIPE.get();
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
}
