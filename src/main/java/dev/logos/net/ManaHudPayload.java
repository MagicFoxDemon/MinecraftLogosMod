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

/**
 * Server → one player: what their mana HUD shows, sent by {@link dev.logos.runtime.ManaHud} when it changes. The pool
 * goes out whether or not they hold a wand, so the client can show it filling up; the rest only with a wand.
 *
 * @param wand     whether they hold a wand or staff
 * @param cost     what the selected spell injects (0 for none): the mark on the bar, from the pool or, for a staff, what it holds
 * @param drawing  what the charge under way has drawn out of the pool so far
 * @param capacity what the held staff can hold, 0 if it is not a staff
 * @param spell    the line under the bar; left out while it stays as last sent, since the numbers change far more often
 */
public record ManaHudPayload(boolean wand, int mana, int maxMana, int cost, int drawing, int stored, int capacity, Optional<Component> spell)
        implements CustomPacketPayload {
    public static final Type<ManaHudPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_hud"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaHudPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ManaHudPayload::wand, ByteBufCodecs.VAR_INT, ManaHudPayload::mana, ByteBufCodecs.VAR_INT, ManaHudPayload::maxMana,
            ByteBufCodecs.VAR_INT, ManaHudPayload::cost, ByteBufCodecs.VAR_INT, ManaHudPayload::drawing,
            ByteBufCodecs.VAR_INT, ManaHudPayload::stored, ByteBufCodecs.VAR_INT, ManaHudPayload::capacity,
            ComponentSerialization.OPTIONAL_STREAM_CODEC, ManaHudPayload::spell, ManaHudPayload::new);

    /** The pool alone: no wand in hand. */
    public static ManaHudPayload pool(int mana, int maxMana) {
        return new ManaHudPayload(false, mana, maxMana, 0, 0, 0, 0, Optional.of(Component.empty()));
    }

    public ManaHudPayload withoutSpell() {
        return new ManaHudPayload(wand, mana, maxMana, cost, drawing, stored, capacity, Optional.empty());
    }

    /** The numbers as one line of text, then what a staff holds: for whoever reads the HUD out rather than looks at it. */
    public MutableComponent head() {
        MutableComponent hud = Component.literal("☿ " + mana + "/" + maxMana).withStyle(ChatFormatting.AQUA);
        if (capacity > 0) hud.append(" ").append(Component.translatable("logos.hud.staff", stored, capacity).withStyle(ChatFormatting.DARK_AQUA));
        return hud;
    }

    @Override
    public Type<ManaHudPayload> type() {
        return TYPE;
    }
}
