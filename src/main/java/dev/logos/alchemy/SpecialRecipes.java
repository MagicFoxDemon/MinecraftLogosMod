package dev.logos.alchemy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.item.PotionPowderItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Alchemy recipes that cannot be written out as data because they follow whatever potion is at
 * hand: they are made up for the things a card can reach, and then made like any other recipe.
 * <ul>
 * <li>Settling any potion (drunk, splash or lingering, other mods' too; not tipped arrows) gives a
 * potion powder holding it, and its glass bottle back.</li>
 * <li>Dissolving a potion powder with a bottle's worth of water fills a glass bottle with its potion again.</li>
 * </ul>
 */
public final class SpecialRecipes {
    private SpecialRecipes() {}

    /** The special recipes of {@code process} for the distinct things in {@code available}. */
    static List<AlchemyRecipe> forThings(Process process, List<ItemResource> available) {
        List<AlchemyRecipe> out = new ArrayList<>();
        List<ItemResource> seen = new ArrayList<>();
        for (ItemResource r : available) {
            if (r.isEmpty() || seen.contains(r)) continue;
            seen.add(r);
            PotionContents contents = r.getComponents().get(DataComponents.POTION_CONTENTS);
            if (contents == null) continue;
            ItemStack one = r.toStack(1);
            if (process == Process.SETTLE && isPotion(r, contents)) {
                out.add(new AlchemyRecipe(process, List.of(new SizedIngredient(DataComponentIngredient.of(true, one), 1)), Optional.empty(),
                        List.of(ItemStackTemplate.fromNonEmptyStack(PotionPowderItem.of(LogosMod.POTION_POWDER.get(), contents, 1)),
                                new ItemStackTemplate(Items.GLASS_BOTTLE))));
            } else if (process == Process.DISSOLVE && r.is(LogosMod.POTION_POWDER.get())) {
                ItemStack potion = new ItemStack(Items.POTION);
                potion.set(DataComponents.POTION_CONTENTS, contents);
                out.add(new AlchemyRecipe(process,
                        List.of(new SizedIngredient(DataComponentIngredient.of(true, one), 1), SizedIngredient.of(Items.GLASS_BOTTLE, 1)),
                        Optional.of(SizedFluidIngredient.of(Fluids.WATER, AlchemyTableBlock.BOTTLE)),
                        List.of(ItemStackTemplate.fromNonEmptyStack(potion))));
            }
        }
        return out;
    }

    /** A potion one could settle: something with effects in it that is not a powder already, nor an arrow. */
    private static boolean isPotion(ItemResource r, PotionContents contents) {
        return contents.hasEffects() && !r.is(LogosMod.POTION_POWDER.get()) && !r.toStack().is(ItemTags.ARROWS);
    }
}
