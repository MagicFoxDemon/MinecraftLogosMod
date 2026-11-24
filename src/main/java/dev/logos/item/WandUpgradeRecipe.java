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
 * A shapeless wand upgrade that preserves pages, charms, enchantments and (staff to staff) stored mana.
 * Matching, recipe-book display and bottle remainders use the normal crafting path; only the result copies
 * the old wand's component patch, as vanilla smithing does. Making a blade staff from a staff loses a
 * slot: the page that no longer fits comes back in the staff's place in the grid.
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

    /** Also refuses when the old wand holds more than one page beyond what the new one has slots for. */
    @Override
    public boolean matches(CraftingInput input, Level level) {
        return shape.matches(input, level) && overflow(input).size() <= 1;
    }

    /**
     * Keep only the old wand's changed components, allowing the new item to supply its own base attributes.
     * Its pages are packed into the new item's slots (a blade staff has one fewer than the staff it is made
     * from; the page that does not fit is handed back, see {@link #getRemainingItems}), and a staff's stored
     * mana does not carry over to anything but a staff.
     */
    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack old = oldWand(input);
        if (old.isEmpty()) return result.create();
        ItemStack made = TransmuteRecipe.createWithOriginalComponents(result, old);
        if (!(made.getItem() instanceof StaffItem)) made.remove(dev.logos.LogosMod.STORED_MANA.get());
        if (made.getItem() instanceof WandItem wand && PageContainer.size(old) > wand.slots()) {
            List<ItemStack> pages = PageContainer.pages(old);
            made.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(pages.subList(0, Math.min(pages.size(), wand.slots()))));
        }
        return made;
    }

    /** Bottles back as usual, and in the old wand's place the page its successor has no slot for. */
    @Override
    public net.minecraft.core.NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        net.minecraft.core.NonNullList<ItemStack> left = super.getRemainingItems(input);
        List<ItemStack> over = overflow(input);
        if (!over.isEmpty()) {
            for (int i = 0; i < input.size(); i++) {
                if (input.getItem(i).getItem() instanceof WandItem) left.set(i, over.getFirst());
            }
        }
        return left;
    }

    private static ItemStack oldWand(CraftingInput input) {
        for (ItemStack stack : input.items()) {
            if (stack.getItem() instanceof WandItem) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** The old wand's pages that the result has no slot for, the last ones. */
    private List<ItemStack> overflow(CraftingInput input) {
        ItemStack old = oldWand(input);
        if (old.isEmpty() || !(result.item().value() instanceof WandItem wand)) return List.of();
        List<ItemStack> pages = PageContainer.pages(old);
        return pages.size() <= wand.slots() ? List.of() : pages.subList(wand.slots(), pages.size());
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
