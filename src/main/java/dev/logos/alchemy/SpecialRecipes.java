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
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
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
 * <li>Dissolving does whatever a brewing stand does (vanilla's mixes and other mods'): a brewing
 * ingredient and three bottles of the potion it brews from make three of what it brews into. A
 * water base can be water from the tank (or water bottles) filling three empty bottles.</li>
 * </ul>
 */
public final class SpecialRecipes {
    /** Bottles brewed at once, as in a brewing stand. */
    private static final int BREW = 3;

    private SpecialRecipes() {}

    /** The special recipes of {@code process} for the distinct things in {@code available}. */
    static List<AlchemyRecipe> forThings(Process process, List<ItemResource> available, PotionBrewing brewing) {
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
        if (process == Process.DISSOLVE) out.addAll(brewed(seen, brewing));
        return out;
    }

    /** For each brewing ingredient at hand, what it brews from each base at hand, and from water. */
    private static List<AlchemyRecipe> brewed(List<ItemResource> things, PotionBrewing brewing) {
        List<AlchemyRecipe> out = new ArrayList<>();
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        for (ItemResource ingredient : things) {
            ItemStack one = ingredient.toStack(1);
            if (!brewing.isIngredient(one)) continue;
            SizedIngredient takes = new SizedIngredient(DataComponentIngredient.of(true, one), 1);
            if (brewing.hasMix(water, one)) {
                out.add(new AlchemyRecipe(Process.DISSOLVE, List.of(takes, SizedIngredient.of(Items.GLASS_BOTTLE, BREW)),
                        Optional.of(SizedFluidIngredient.of(Fluids.WATER, BREW * AlchemyTableBlock.BOTTLE)), brew(brewing, one, water)));
            }
            for (ItemResource base : things) {
                ItemStack from = base.toStack(1);
                // Water bases are covered above, bottles and all.
                if (base == ingredient || ItemStack.isSameItemSameComponents(from, water) || !brewing.isInput(from) || !brewing.hasMix(from, one)) continue;
                out.add(new AlchemyRecipe(Process.DISSOLVE, List.of(takes, new SizedIngredient(DataComponentIngredient.of(true, from), BREW)),
                        Optional.empty(), brew(brewing, one, from)));
            }
        }
        return out;
    }

    /** Three of what {@code ingredient} brews {@code from} into, one result each (potions do not stack). */
    private static List<ItemStackTemplate> brew(PotionBrewing brewing, ItemStack ingredient, ItemStack from) {
        ItemStackTemplate one = ItemStackTemplate.fromNonEmptyStack(brewing.mix(ingredient, from.copy()).copyWithCount(1));
        return java.util.Collections.nCopies(BREW, one);
    }

    /** A potion one could settle: something with effects in it that is not a powder already, nor an arrow. */
    private static boolean isPotion(ItemResource r, PotionContents contents) {
        return contents.hasEffects() && !r.is(LogosMod.POTION_POWDER.get()) && !r.toStack().is(ItemTags.ARROWS);
    }
}
