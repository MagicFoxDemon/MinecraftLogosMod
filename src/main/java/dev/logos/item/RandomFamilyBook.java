package dev.logos.item;

import java.util.List;

import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import dev.logos.item.RuneBookItem.Kind;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

/**
 * Loot function {@code logos:random_family_book}: turns the item into one of the family books (指向、变、律、数、动之书;
 * not 形之书 nor an element's), drawn in proportion to how many words each family has, as the chests draw them. The
 * warlock's trades sell one this way.
 */
public record RandomFamilyBook() implements LootItemFunction {
    public static final MapCodec<RandomFamilyBook> CODEC = MapCodec.unit(new RandomFamilyBook());
    /** The books it chooses among. */
    public static final List<Kind> FAMILIES = List.of(Kind.TARGETING, Kind.CHANGE, Kind.LAW, Kind.NUMBER, Kind.MOTION);

    @Override
    public MapCodec<RandomFamilyBook> codec() {
        return CODEC;
    }

    @Override
    public ItemStack apply(ItemStack stack, LootContext context) {
        int total = 0;
        for (Kind k : FAMILIES) total += k.pool().size();
        int roll = context.getRandom().nextInt(Math.max(1, total));
        for (Kind k : FAMILIES) {
            roll -= k.pool().size();
            if (roll < 0) return new ItemStack(LogosMod.RUNE_BOOKS.get(k).get(), stack.getCount());
        }
        return new ItemStack(LogosMod.RUNE_BOOKS.get(Kind.CHANGE).get(), stack.getCount());
    }
}
