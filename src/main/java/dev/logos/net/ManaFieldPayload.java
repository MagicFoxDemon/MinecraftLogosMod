package dev.logos.net;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: how much spread mana one chunk holds, roughly (0 to {@link dev.logos.runtime.ManaField#LEVELS}), for the motes in its air. */
public record ManaFieldPayload(long chunk, int level) implements CustomPacketPayload {
    public static final Type<ManaFieldPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_field"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaFieldPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ManaFieldPayload::chunk, ByteBufCodecs.VAR_INT, ManaFieldPayload::level, ManaFieldPayload::new);

    @Override
    public Type<ManaFieldPayload> type() {
        return TYPE;
    }
}
