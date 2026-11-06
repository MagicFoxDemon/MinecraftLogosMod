package dev.logos.net;

import java.util.Optional;

import dev.logos.LogosMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Server → one player: what their mana HUD shows, sent by {@link dev.logos.runtime.ManaHud} when it changes.
 *
 * @param shown    false while they hold no wand
 * @param filled   how many of the bar's ten segments are lit
 * @param capacity what the held staff can hold, 0 if it is not a staff
 * @param spell    the text after the numbers; left out while it stays as last sent, since the numbers change far more often
 */
public record ManaHudPayload(boolean shown, int mana, int maxMana, int filled, int stored, int capacity, Optional<Component> spell) implements CustomPacketPayload {
    public static final ManaHudPayload HIDDEN = new ManaHudPayload(false, 0, 0, 0, 0, 0, Optional.empty());
    public static final Type<ManaHudPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_hud"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaHudPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ManaHudPayload::shown, ByteBufCodecs.VAR_INT, ManaHudPayload::mana, ByteBufCodecs.VAR_INT, ManaHudPayload::maxMana,
            ByteBufCodecs.VAR_INT, ManaHudPayload::filled, ByteBufCodecs.VAR_INT, ManaHudPayload::stored, ByteBufCodecs.VAR_INT, ManaHudPayload::capacity,
            ComponentSerialization.OPTIONAL_STREAM_CODEC, ManaHudPayload::spell, ManaHudPayload::new);

    public ManaHudPayload withoutSpell() {
        return new ManaHudPayload(shown, mana, maxMana, filled, stored, capacity, Optional.empty());
    }

    /** The line up to the spell: the mana bar and numbers, then what a staff holds. */
    public MutableComponent head() {
        int lit = Mth.clamp(filled, 0, 10);
        MutableComponent hud = Component.literal("☿ ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("█".repeat(lit)).withStyle(ChatFormatting.BLUE))
                .append(Component.literal("█".repeat(10 - lit)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" " + mana + "/" + maxMana).withStyle(ChatFormatting.AQUA));
        if (capacity > 0) hud.append(Component.literal("  杖内 " + stored + "/" + capacity).withStyle(ChatFormatting.DARK_AQUA));
        return hud;
    }

    @Override
    public Type<ManaHudPayload> type() {
        return TYPE;
    }
}
