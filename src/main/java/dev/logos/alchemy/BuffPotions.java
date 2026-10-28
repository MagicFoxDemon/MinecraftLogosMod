package dev.logos.alchemy;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
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
 * its ingredient (a jar herb's harvest, a mandrake root for life, starlight moss for arcane); from vital mash, or a level I potion
 * raised with vital mash, +50% for 5 minutes.
 * </ul>
 * The alchemy table's dissolving mirrors the brewing stand, so it makes all of them too.
 */
public final class BuffPotions {
    public static final DeferredRegister<Potion> POTIONS = DeferredRegister.create(Registries.POTION, LogosMod.MODID);

    /** Mana a second for each level of 流魔: what a dose restores over its length. */
    private static final double[] MANA_PER_SECOND = {400 / 180.0, 1000 / 180.0, 5000 / 300.0};

    /** 魔力涌流: mana flows back into the drinker's pool. */
    public static final DeferredHolder<MobEffect, MobEffect> MANA_FLOW = LogosMod.MOB_EFFECTS.register("mana_flow", () -> new Buff(MobEffectCategory.BENEFICIAL, 0x63CCF2)
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

    /** 火焰增幅…: more spell damage dealt in an element, +20% at level I and +50% at level II. Every element has one, potion or not. */
    public static final Map<Element, DeferredHolder<MobEffect, MobEffect>> POWER = new EnumMap<>(Element.class);
    /** 火焰易伤…: more spell damage taken in an element, +20% at level I and +50% at level II. */
    public static final Map<Element, DeferredHolder<MobEffect, MobEffect>> VULNERABILITY = new EnumMap<>(Element.class);

    static {
        for (Element element : Element.values()) {
            String name = element.name().toLowerCase(Locale.ROOT);
            int color = color(element);
            POWER.put(element, LogosMod.MOB_EFFECTS.register(name + "_power", () -> new Buff(MobEffectCategory.BENEFICIAL, color)
                    .addAttributeModifier(SpellModifiers.powerAttribute(element), id(name + "_power"),
                            AttributeModifier.Operation.ADD_MULTIPLIED_BASE, BuffPotions::byLevel)));
            VULNERABILITY.put(element, LogosMod.MOB_EFFECTS.register(name + "_vulnerability", () -> new Buff(MobEffectCategory.HARMFUL, color)
                    .addAttributeModifier(SpellModifiers.vulnerabilityAttribute(element), id(name + "_vulnerability"),
                            AttributeModifier.Operation.ADD_VALUE, BuffPotions::byLevel)));
        }
    }

    /** An element potion: its effect, its two potions, and what brews it from mash. */
    public enum ElementBuff {
        PHYSICAL(Element.PHYSICAL, LogosMod.JarHerb.REDVEIN.harvest),
        FIRE(Element.FIRE, LogosMod.JarHerb.CANDLEFLAME.harvest),
        FROST(Element.FROST, LogosMod.JarHerb.FROSTBELL.harvest),
        LIGHTNING(Element.LIGHTNING, LogosMod.JarHerb.THUNDER_THISTLE.harvest),
        VOID(Element.VOID, LogosMod.JarHerb.RIFTSHADE.harvest),
        ARCANE(Element.ARCANE, LogosMod.STARLIGHT_MOSS),
        LIFE(Element.LIFE, LogosMod.MANDRAKE_ROOT);

        public final Element element;
        /** What brews it, one to three bottles of mash at a time. */
        public final Supplier<? extends Item> ingredient;
        /** Its element's empowerment ({@link #POWER}). */
        public final DeferredHolder<MobEffect, MobEffect> effect;
        public final DeferredHolder<Potion, Potion> potion;
        public final DeferredHolder<Potion, Potion> strong;

        ElementBuff(Element element, Supplier<? extends Item> ingredient) {
            this.element = element;
            this.ingredient = ingredient;
            String id = element.name().toLowerCase(Locale.ROOT) + "_power";
            effect = POWER.get(element);
            potion = POTIONS.register(id, () -> new Potion("logos." + id, new MobEffectInstance(effect, 120 * 20)));
            strong = POTIONS.register("strong_" + id, () -> new Potion("logos." + id, new MobEffectInstance(effect, 300 * 20, 1)));
        }
    }

    static {
        // Registers the element effects and potions with the rest.
        ElementBuff.values();
    }

    private BuffPotions() {}

    /** How much an element effect adds at {@code level} (0 is level I): 0.2, then 0.5, then 0.3 more a level. */
    private static double byLevel(int level) {
        return level <= 0 ? 0.2 : 0.5 + 0.3 * (level - 1);
    }

    /** The colour of an element's effects, as their potions are tinted. */
    private static int color(Element element) {
        return switch (element) {
            case PHYSICAL -> 0xC0473A;
            case FIRE -> 0xFF7B1F;
            case FROST -> 0x9BE6FF;
            case LIGHTNING -> 0xF3DF4E;
            case VOID -> 0x7438C2;
            case ARCANE -> 0xD45CF0;
            case LIFE -> 0x5FCB4E;
        };
    }

    /** Called while the mod is constructed, before its registers are handed to the bus. */
    public static void init() {}

    /** Whether {@code stack} holds a Logos potion (drunk, splash, lingering or on an arrow). */
    public static boolean isLogosPotion(ItemStack stack) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion()
                .flatMap(Holder::unwrapKey).filter(k -> k.identifier().getNamespace().equals(LogosMod.MODID)).isPresent();
    }

    /** The brewing stand's recipes for these potions; the alchemy table dissolves by the same ones. */
    public static void brewing(PotionBrewing.Builder builder) {
        builder.addRecipe(new FromMash(LogosMod.RAW_MASH, LogosMod.MANA_SHARD, MANA_FLOW_POTION));
        builder.addRecipe(new FromMash(LogosMod.RAW_MASH, LogosMod.MANA_CRYSTAL, STRONG_MANA_FLOW_POTION));
        builder.addRecipe(new FromMash(LogosMod.VITAL_MASH, LogosMod.MANA_GEMSTONE, GREATER_MANA_FLOW_POTION));
        for (ElementBuff e : ElementBuff.values()) {
            builder.addRecipe(new FromMash(LogosMod.RAW_MASH, e.ingredient, e.potion));
            builder.addRecipe(new FromMash(LogosMod.VITAL_MASH, e.ingredient, e.strong));
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

    /** A plain effect; what it does is in its attribute modifiers. */
    private static final class Buff extends MobEffect {
        Buff(MobEffectCategory category, int color) {
            super(category, color);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, path);
    }
}
