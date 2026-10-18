package dev.logos.compat.jei;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.alchemy.AlchemyRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * One alchemy recipe in JEI: the process that makes it (its sign and name) above, the materials
 * and any tank fluid on the left, what comes out on the right. Materials are shown with their
 * counts, and a used-up bottle given back is listed among the results, as the recipe writes it.
 */
final class AlchemyRecipeCategory extends AbstractRecipeCategory<RecipeHolder<AlchemyRecipe>> {
    static final IRecipeHolderType<AlchemyRecipe> TYPE = IRecipeHolderType.create(Identifier.fromNamespaceAndPath(LogosMod.MODID, "alchemy"));
    private static final int SLOT = 18, TOP = 12, OUTPUTS_X = 100, WIDTH = OUTPUTS_X + 3 * SLOT, HEIGHT = TOP + 3 * SLOT;
    /** Process names in the alchemy table's ink. */
    private static final int INK = 0xFF4A3A28;

    AlchemyRecipeCategory(IGuiHelper gui) {
        super(TYPE, Component.translatable("block.logos.alchemy_table"), gui.createDrawableItemLike(LogosMod.ALCHEMY_TABLE_ITEM.get()), WIDTH, HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<AlchemyRecipe> holder, IFocusGroup focuses) {
        AlchemyRecipe recipe = holder.value();
        List<SizedIngredient> ingredients = recipe.ingredients();
        for (int i = 0; i < ingredients.size(); i++) {
            SizedIngredient need = ingredients.get(i);
            builder.addInputSlot(1 + i % 3 * SLOT, TOP + 1 + i / 3 * SLOT).setStandardSlotBackground()
                    .addItemStacks(need.ingredient().items().map(item -> new ItemStack(item, need.count())).toList());
        }
        if (recipe.fluid().isPresent()) {
            SizedFluidIngredient fluid = recipe.fluid().get();
            IRecipeSlotBuilder tank = builder.addInputSlot(70, TOP + 2 * SLOT + 1).setStandardSlotBackground()
                    .setFluidRenderer(fluid.amount(), false, 16, 16);
            fluid.ingredient().fluids().forEach(f -> tank.add(f.value(), fluid.amount()));
        }
        List<ItemStackTemplate> results = recipe.results();
        for (int i = 0; i < results.size(); i++) {
            builder.addOutputSlot(OUTPUTS_X + 1 + i % 3 * SLOT, TOP + 1 + i / 3 * SLOT).setOutputSlotBackground().add(results.get(i));
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RecipeHolder<AlchemyRecipe> holder, IFocusGroup focuses) {
        var process = holder.value().process();
        builder.addText(Component.literal(process.symbol + " ").append(process.displayName()), WIDTH, TOP - 2)
                .setPosition(0, 0).setColor(INK);
        builder.addRecipeArrowWidget().setPosition(64, TOP + SLOT);
    }
}
