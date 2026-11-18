package dev.logos.alchemy;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import net.minecraft.world.item.ItemStack;

/**
 * A bottled liquid poured into a cultivation jar as its medium (data map {@code logos:cultivation_medium}
 * on items). It lasts {@code uses} harvests, multiplies the chance to grow by {@code growth} (a chance past 1
 * grows several stages a random tick), and shows as a layer of {@code color} on the soil. A medium that neither speeds the crop nor is named by its
 * {@link CultivationRecipe} is not used up.
 */
public record CultivationMedium(int uses, float growth, int color) {
    public static final Codec<CultivationMedium> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, 64).fieldOf("uses").forGetter(CultivationMedium::uses),
            Codec.floatRange(0, 16).optionalFieldOf("growth", 1f).forGetter(CultivationMedium::growth),
            net.minecraft.network.chat.TextColor.CODEC.xmap(net.minecraft.network.chat.TextColor::getValue, net.minecraft.network.chat.TextColor::fromRgb).fieldOf("color").forGetter(CultivationMedium::color)).apply(i, CultivationMedium::new));

    public static @Nullable CultivationMedium of(ItemStack stack) {
        return stack.isEmpty() ? null : stack.getItemHolder().getData(LogosMod.CULTIVATION_MEDIUM);
    }
}
