package dev.logos.alchemy;

import java.util.Locale;
import java.util.function.Supplier;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Logos potions, vanilla potions in every way (bottles, splash, lingering, arrows) whose effects work
 * through the spell attributes of {@link SpellModifiers}:
 * <ul>
 * <li>流魔: raises mana regeneration, so a dose restores a set amount over its length. Raw mash brews it
 * with a mana shard (400 over 3 minutes) or a mana crystal (1000 over 3 minutes), vital mash with a mana
 * gemstone (5000 over 5 minutes).
 * <li>The element potions: +20% spell damage in their element for 2 minutes, brewed from raw mash and
 * the harvest of that element's herb ({@link LogosMod.JarHerb}); from vital mash, or a level I potion
 * raised with vital mash, +50% for 5 minutes.
 * </ul>
 * The alchemy table's dissolving mirrors the brewing stand, so it makes all of them too.
 */
public final class BuffPotions {
    public static final DeferredRegister<Potion> POTIONS = DeferredRegister.create(Registries.POTION, LogosMod.MODID);

    /** Mana a second for each level of 流魔: what a dose restores over its length. */
    private static final double[] MANA_PER_SECOND = {400 / 180.0, 1000 / 180.0, 5000 / 300.0};

    /** 魔力涌流: mana flows back into the drinker's pool. */
    public static final DeferredHolder<MobEffect, MobEffect> MANA_FLOW = LogosMod.MOB_EFFECTS.register("mana_flow", () -> new Buff(0x63CCF2)
            .addAttributeModifier(SpellModifiers.MANA_REGENERATION, id("mana_flow"), AttributeModifier.Operation.ADD_VALUE,
                    level -> MANA_PER_SECOND[Math.clamp(level, 0, MANA_PER_SECOND.length - 1)]));
    /** 瓶装流魔, from a mana shard. */
    public static final DeferredHolder<Potion, Potion> MANA_FLOW_POTION = POTIONS.register("mana_flow",
            () -> new Potion("logos.mana_flow", new MobEffectInstance(MANA_FLOW, 180 * 20)));
    /** From a mana crystal. */
    public static final DeferredHolder<Potion, Potion> STRONG_MANA_FLOW_POTION = POTIONS.register("strong_mana_flow",
            () -> new Potion("logos.mana_flow", new MobEffectInstance(MANA_FLOW, 180 * 20, 1)));
    /** From a mana gemstone, in vital mash. */
    public static final DeferredHolder<Potion, Potion> GREATER_MANA_FLOW_POTION = POTIONS.register("greater_mana_flow",
            () -> new Potion("logos.mana_flow", new MobEffectInstance(MANA_FLOW, 300 * 20, 2)));

    /** An element potion: its effect, its two potions, and the herb whose harvest brews it. */
    public enum ElementBuff {
        PHYSICAL(Element.PHYSICAL, 0xC0473A, LogosMod.JarHerb.REDVEIN),
        FIRE(Element.FIRE, 0xFF7B1F, LogosMod.JarHerb.CANDLEFLAME),
        FROST(Element.FROST, 0x9BE6FF, LogosMod.JarHerb.FROSTBELL),
        LIGHTNING(Element.LIGHTNING, 0xF3DF4E, LogosMod.JarHerb.THUNDER_THISTLE),
        VOID(Element.VOID, 0x7438C2, LogosMod.JarHerb.RIFTSHADE);

        public final Element element;
        public final LogosMod.JarHerb herb;
        /** 火焰增幅…: more spell damage in the element, +20% at level I and +50% at level II. */
        public final DeferredHolder<MobEffect, MobEffect> effect;
        public final DeferredHolder<Potion, Potion> potion;
        public final DeferredHolder<Potion, Potion> strong;

        ElementBuff(Element element, int color, LogosMod.JarHerb herb) {
            this.element = element;
            this.herb = herb;
            String id = element.name().toLowerCase(Locale.ROOT) + "_power";
            effect = LogosMod.MOB_EFFECTS.register(id, () -> new Buff(color).addAttributeModifier(SpellModifiers.powerAttribute(element), id(id),
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE, level -> level <= 0 ? 0.2 : 0.5 + 0.3 * (level - 1)));
            potion = POTIONS.register(id, () -> new Potion("logos." + id, new MobEffectInstance(effect, 120 * 20)));
            strong = POTIONS.register("strong_" + id, () -> new Potion("logos." + id, new MobEffectInstance(effect, 300 * 20, 1)));
        }
    }

    static {
        // Registers the element effects and potions with the rest.
        ElementBuff.values();
    }

    private BuffPotions() {}

    /** Called while the mod is constructed, before its registers are handed to the bus. */
    public static void init() {}

    /** The brewing stand's recipes for these potions; the alchemy table dissolves by the same ones. */
    public static void brewing(PotionBrewing.Builder builder) {
        builder.addRecipe(new FromMash(LogosMod.RAW_MASH, LogosMod.MANA_SHARD, MANA_FLOW_POTION));
        builder.addRecipe(new FromMash(LogosMod.RAW_MASH, LogosMod.MANA_CRYSTAL, STRONG_MANA_FLOW_POTION));
        builder.addRecipe(new FromMash(LogosMod.VITAL_MASH, LogosMod.MANA_GEMSTONE, GREATER_MANA_FLOW_POTION));
        for (ElementBuff e : ElementBuff.values()) {
            builder.addRecipe(new FromMash(LogosMod.RAW_MASH, e.herb.harvest, e.potion));
            builder.addRecipe(new FromMash(LogosMod.VITAL_MASH, e.herb.harvest, e.strong));
            builder.addMix(e.potion, LogosMod.VITAL_MASH.get(), e.strong);
        }
    }

    /** A bottle of {@code base} (raw or vital mash, items of their own rather than potions) and {@code ingredient} brew {@code result}. */
    private record FromMash(Supplier<? extends Item> base, Supplier<? extends Item> ingredient, Holder<Potion> result) implements IBrewingRecipe {
        @Override
        public boolean isInput(ItemStack input) {
            return input.is(base.get());
        }

        @Override
        public boolean isIngredient(ItemStack ingredient) {
            return ingredient.is(this.ingredient.get());
        }

        @Override
        public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
            return isInput(input) && isIngredient(ingredient) ? PotionContents.createItemStack(Items.POTION, result) : ItemStack.EMPTY;
        }
    }

    /** A plain beneficial effect; what it does is in its attribute modifiers. */
    private static final class Buff extends MobEffect {
        Buff(int color) {
            super(MobEffectCategory.BENEFICIAL, color);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, path);
    }
}
