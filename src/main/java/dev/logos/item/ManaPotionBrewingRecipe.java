package dev.logos.item;

import java.math.BigInteger;

import dev.logos.LogosMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

/** The vanilla stand consumes one shard per round, regardless of the ingredient stack size. */
public final class ManaPotionBrewingRecipe implements IBrewingRecipe {
    @Override
    public boolean isInput(ItemStack input) {
        return input.getCount() == 1 && (input.is(LogosMod.MANA_POTION.get())
                || input.is(Items.POTION) && input.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.AWKWARD));
    }

    @Override
    public boolean isIngredient(ItemStack ingredient) {
        return ingredient.is(LogosMod.TAINTED_MANA_SHARD.get());
    }

    @Override
    public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
        if (!isInput(input) || !isIngredient(ingredient)) return ItemStack.EMPTY;
        boolean upgrade = input.is(LogosMod.MANA_POTION.get());
        ItemStack output = upgrade ? input.copyWithCount(1) : new ItemStack(LogosMod.MANA_POTION.get());
        output.set(LogosMod.MANA_POTION_SHARDS.get(), upgrade ? ManaPotionItem.shards(input).add(BigInteger.ONE) : BigInteger.ONE);
        return output;
    }
}
