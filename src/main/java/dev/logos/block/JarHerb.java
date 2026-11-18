package dev.logos.block;

import javax.annotation.Nullable;

import dev.logos.LogosMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * The herbs of the element potions (dev.logos.alchemy.BuffPotions): seeds fermented with germinal
 * essence, grown in a jar and harvested again and again for what brews their potions.
 * <p>
 * They go into {@link LogosMod}'s registers, whose static initialiser loads this enum at their place among the rest.
 */
public enum JarHerb {
    /** 赤脉根: beetroot quickened on bone meal, red-veined; the root is itself the harvest. */
    REDVEIN("redvein_root", null, Rarity.COMMON),
    /** 烛焰花: a poppy that drank blaze powder; its stamens burn like wicks. */
    CANDLEFLAME("candleflame", "candleflame_stamen", Rarity.COMMON),
    /** 霜铃花: lily of the valley grown in powder snow; its petals ring with cold. */
    FROSTBELL("frostbell", "frostbell_petal", Rarity.COMMON),
    /** 鸣雷蓟: a dead bush on copper and echo; its down crackles. */
    THUNDER_THISTLE("thunder_thistle", "thunder_thistle_down", Rarity.UNCOMMON),
    /** 隙影花: chorus fruit on echo and dragon's breath, a flower half in the void; its pollen is shadow. */
    RIFTSHADE("riftshade", "riftshade_pollen", Rarity.UNCOMMON);

    /** The seed fermented on the alchemy table and planted in the jar. */
    public final DeferredItem<Item> seed;
    /** The herb itself, separate from its seed and any harvested brewing ingredient. */
    public final DeferredItem<Item> plant;
    /** What it gives, again and again: the brewing ingredient of its potion (the plant itself for redvein root). */
    public final DeferredItem<Item> harvest;
    public final DeferredBlock<JarCropBlock> crop;

    JarHerb(String plant, @Nullable String harvest, Rarity rarity) {
        this.seed = LogosMod.ITEMS.registerSimpleItem(plant + "_seed", p -> p.rarity(rarity));
        this.plant = LogosMod.ITEMS.registerSimpleItem(plant, p -> p.rarity(rarity));
        this.harvest = harvest == null ? this.plant : LogosMod.ITEMS.registerSimpleItem(harvest, p -> p.rarity(rarity));
        crop = LogosMod.jarCrop(plant + "_crop");
    }
}
