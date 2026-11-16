package dev.logos.block;

import java.util.Locale;

import dev.logos.LogosMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * The metals that grow in a jar: each has an embryo (settled from its ingot), a seed, a plant and the pod it bears.
 * <p>
 * They go into {@link LogosMod}'s registers, whose static initialiser loads this enum at their place among the rest.
 */
public enum JarMetal {
    COPPER(Rarity.COMMON), IRON(Rarity.COMMON),
    GOLD(Rarity.UNCOMMON), NETHERITE(Rarity.RARE);

    public final String id;
    /** 铜之胚…: the metal settled into a germ that will take root. */
    public final DeferredItem<Item> embryo;
    /** 铜之种…: the germ quickened; planted in a jar it bears pods slowly, on mana, harvest after harvest. */
    public final DeferredItem<Item> seed;
    /** 铜荚…: the living metal a plant bears; calcined, it becomes the ingot. */
    public final DeferredItem<Item> pod;
    public final DeferredBlock<JarCropBlock> crop;

    JarMetal(Rarity rarity) {
        id = name().toLowerCase(Locale.ROOT);
        embryo = LogosMod.ITEMS.registerSimpleItem(id + "_embryo", p -> p.rarity(rarity));
        seed = LogosMod.ITEMS.registerSimpleItem(id + "_seed", p -> p.rarity(rarity));
        pod = LogosMod.ITEMS.registerSimpleItem(id + "_pod", p -> p.rarity(rarity));
        crop = LogosMod.jarCrop(id + "_pod_crop");
    }
}
