package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;

/**
 * An effect along an item's surface (after KriptoFX Weapon Effects 2; FORM_VFX.md), kept on the item as the
 * {@code logos:surface_aura} component: it shows whoever holds the item, dropped on the ground or in an item frame,
 * to every player. {@code hue} turns the whole colour by that many turns (0 keeps it). This is only the data; the
 * client's {@code SurfaceAuras} decides the picture.
 */
public record SurfaceAura(Style style, float hue) {
    /** A sword on fire, and an item wound about with mana. */
    public enum Style implements StringRepresentable {
        FIRE("fire"), MANA("mana");

        public static final Codec<Style> CODEC = StringRepresentable.fromEnum(Style::values);
        public static final StreamCodec<ByteBuf, Style> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Style::ordinal);
        private final String name;

        Style(String name) { this.name = name; }

        @Override public String getSerializedName() { return name; }
    }

    public static final Codec<SurfaceAura> CODEC = RecordCodecBuilder.create(i -> i.group(
            Style.CODEC.fieldOf("style").forGetter(SurfaceAura::style),
            Codec.FLOAT.optionalFieldOf("hue", 0f).forGetter(SurfaceAura::hue)).apply(i, SurfaceAura::new));
    public static final StreamCodec<ByteBuf, SurfaceAura> STREAM_CODEC = StreamCodec.composite(
            Style.STREAM_CODEC, SurfaceAura::style, ByteBufCodecs.FLOAT, SurfaceAura::hue, SurfaceAura::new);

    public static @Nullable SurfaceAura of(ItemStack stack) { return stack.get(LogosMod.SURFACE_AURA.get()); }

    /** Gives the item this effect (replacing any it had); a null {@code style} takes it off. */
    public static void set(ItemStack stack, @Nullable Style style, float hue) {
        if (style == null) stack.remove(LogosMod.SURFACE_AURA.get());
        else stack.set(LogosMod.SURFACE_AURA.get(), new SurfaceAura(style, hue));
    }
}
