package dev.logos.alchemy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.JsonOps;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.item.PotionPowderItem;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
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
 * water base can be water from the tank (or water bottles) filling three empty bottles. A brew takes two mana.</li>
 * <li>Calcining burns whatever a furnace smelts (vanilla's recipes and other mods'): one of the
 * thing makes what the furnace would, with no fuel.</li>
 * <li>Dissolving two or three different potion powders with philosophers' mercury gives one potion
 * (调和药剂) of all their effects, in the mercury's bottle. It takes five mana for each powder.</li>
 * <li>Purifying washes whatever a water cauldron washes ({@link #wash}), for a bottle's worth of water.</li>
 * </ul>
 */
public final class SpecialRecipes {
    /** Bottles brewed at once, as in a brewing stand. */
    private static final int BREW = 3;
    /** Mana a brew takes: two, as three bottles come of it. */
    private static final int BREW_MANA = 2;
    /** Mana a conjunction takes for each powder joined. */
    private static final int CONJUNCTION_MANA = 5;
    /** The potion name of a 调和药剂 (item.minecraft.potion.effect.logos_conjunction). */
    public static final String CONJUNCTION = "logos_conjunction";

    private SpecialRecipes() {}

    /** The special recipes of {@code process} for the distinct things in {@code available}. */
    static List<AlchemyRecipe> forThings(Process process, List<ItemResource> available, ServerLevel level) {
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
        // Ahead of the powder-back recipes: given philosophers' mercury and two powders, a card set up for it should not just dissolve one.
        if (process == Process.DISSOLVE) out.addAll(0, conjoined(seen));
        if (process == Process.DISSOLVE) out.addAll(brewed(seen, level.potionBrewing()));
        if (process == Process.CALCINE) out.addAll(calcined(seen, level));
        if (process == Process.PURIFY) out.addAll(washed(seen));
        return out;
    }

    /** For every two or three different potion powders at hand, with philosophers' mercury there too: one potion of all their effects. */
    private static List<AlchemyRecipe> conjoined(List<ItemResource> things) {
        if (things.stream().noneMatch(t -> t.is(LogosMod.PHILOSOPHERS_MERCURY.get()))) return List.of();
        List<ItemResource> powders = things.stream()
                .filter(t -> t.is(LogosMod.POTION_POWDER.get()) && t.getComponents().get(DataComponents.POTION_CONTENTS) != null).toList();
        List<AlchemyRecipe> out = new ArrayList<>();
        for (int a = 0; a < powders.size(); a++) {
            for (int b = a + 1; b < powders.size(); b++) {
                out.add(conjunction(List.of(powders.get(a), powders.get(b))));
                for (int c = b + 1; c < powders.size(); c++) out.add(conjunction(List.of(powders.get(a), powders.get(b), powders.get(c))));
            }
        }
        return out;
    }

    /** Philosophers' mercury and {@code powders} into a 调和药剂 holding each effect once, the stronger where two share one. */
    private static AlchemyRecipe conjunction(List<ItemResource> powders) {
        List<SizedIngredient> takes = new ArrayList<>(List.of(SizedIngredient.of(LogosMod.PHILOSOPHERS_MERCURY.get(), 1)));
        Map<Holder<MobEffect>, MobEffectInstance> effects = new LinkedHashMap<>();
        for (ItemResource powder : powders) {
            takes.add(new SizedIngredient(DataComponentIngredient.of(true, powder.toStack(1)), 1));
            for (MobEffectInstance e : powder.getComponents().get(DataComponents.POTION_CONTENTS).getAllEffects()) {
                effects.merge(e.getEffect(), new MobEffectInstance(e), (had, now) -> now.getAmplifier() > had.getAmplifier()
                        || now.getAmplifier() == had.getAmplifier() && now.getDuration() > had.getDuration() ? now : had);
            }
        }
        ItemStack potion = new ItemStack(Items.POTION);
        potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(), List.copyOf(effects.values()), Optional.of(CONJUNCTION)));
        return new AlchemyRecipe(Process.DISSOLVE, takes, Optional.empty(), List.of(ItemStackTemplate.fromNonEmptyStack(potion)), CONJUNCTION_MANA * powders.size());
    }

    /** For each thing at hand that a water cauldron washes, one of it washed, for a bottle's worth of water. */
    private static List<AlchemyRecipe> washed(List<ItemResource> things) {
        List<AlchemyRecipe> out = new ArrayList<>();
        for (ItemResource thing : things) {
            ItemStack clean = wash(thing.toStack(1));
            if (clean.isEmpty()) continue;
            out.add(new AlchemyRecipe(Process.PURIFY, List.of(new SizedIngredient(DataComponentIngredient.of(true, thing.toStack(1)), 1)),
                    Optional.of(SizedFluidIngredient.of(Fluids.WATER, AlchemyTableBlock.BOTTLE)), List.of(ItemStackTemplate.fromNonEmptyStack(clean))));
        }
        return out;
    }

    /**
     * What washing in a water cauldron makes of one {@code stack}, or empty if it does not wash. As
     * vanilla's cauldron: dyed things lose their dye (those tagged for it), a banner loses its top
     * pattern, a coloured shulker box turns plain; and concrete powder sets, as it does in water. It
     * goes by tags and kinds of block, so other mods' things that join them wash too.
     */
    static ItemStack wash(ItemStack stack) {
        if (stack.has(DataComponents.DYED_COLOR) && stack.is(ItemTags.CAULDRON_CAN_REMOVE_DYE)) {
            ItemStack clean = stack.copyWithCount(1);
            clean.remove(DataComponents.DYED_COLOR);
            return clean;
        }
        BannerPatternLayers patterns = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        if (stack.is(ItemTags.BANNERS) && !patterns.layers().isEmpty()) {
            ItemStack clean = stack.copyWithCount(1);
            clean.set(DataComponents.BANNER_PATTERNS, patterns.removeLast());
            return clean;
        }
        Block block = Block.byItem(stack.getItem());
        if (block instanceof ShulkerBoxBlock box && box.getColor() != null) return stack.transmuteCopy(Blocks.SHULKER_BOX, 1);
        if (block instanceof ConcretePowderBlock powder) {
            Block concrete = concreteOf(powder);
            if (concrete != null && concrete.asItem() != Items.AIR) return stack.transmuteCopy(concrete, 1);
        }
        return ItemStack.EMPTY;
    }

    /** The block a concrete powder sets into: kept private, so read through the block's own codec. */
    private static @Nullable Block concreteOf(ConcretePowderBlock powder) {
        return ConcretePowderBlock.CODEC.codec().encodeStart(JsonOps.INSTANCE, powder).result()
                .map(json -> json.getAsJsonObject().get("concrete").getAsString())
                .flatMap(id -> BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id))).orElse(null);
    }

    /** For each thing at hand that a furnace smelts, one of it into what the furnace makes of it. */
    private static List<AlchemyRecipe> calcined(List<ItemResource> things, ServerLevel level) {
        List<AlchemyRecipe> out = new ArrayList<>();
        for (ItemResource thing : things) {
            SingleRecipeInput input = new SingleRecipeInput(thing.toStack(1));
            level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level).ifPresent(recipe -> {
                ItemStack result = recipe.value().assemble(input);
                if (result.isEmpty()) return;
                out.add(new AlchemyRecipe(Process.CALCINE, List.of(new SizedIngredient(DataComponentIngredient.of(true, input.item()), 1)),
                        Optional.empty(), List.of(ItemStackTemplate.fromNonEmptyStack(result))));
            });
        }
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
                        Optional.of(SizedFluidIngredient.of(Fluids.WATER, BREW * AlchemyTableBlock.BOTTLE)), brew(brewing, one, water), BREW_MANA));
            }
            for (ItemResource base : things) {
                ItemStack from = base.toStack(1);
                // Water bases are covered above, bottles and all.
                if (base == ingredient || ItemStack.isSameItemSameComponents(from, water) || !brewing.isInput(from) || !brewing.hasMix(from, one)) continue;
                out.add(new AlchemyRecipe(Process.DISSOLVE, List.of(takes, new SizedIngredient(DataComponentIngredient.of(true, from), BREW)),
                        Optional.empty(), brew(brewing, one, from), BREW_MANA));
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
