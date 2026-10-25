package dev.logos.net;

import dev.logos.LogosMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client: whether this server offers its sage, and whether this player may set it up.
 * Nothing else of the server's settings ever goes to an ordinary player.
 */
public record SageStatusPayload(boolean offered, boolean admin) implements CustomPacketPayload {
    public static final Type<SageStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "sage_status"));
    public static final StreamCodec<FriendlyByteBuf, SageStatusPayload> CODEC = CustomPacketPayload.codec(
            (p, b) -> {
                b.writeBoolean(p.offered);
                b.writeBoolean(p.admin);
            },
            b -> new SageStatusPayload(b.readBoolean(), b.readBoolean()));

    @Override
    public Type<SageStatusPayload> type() {
        return TYPE;
    }
}
