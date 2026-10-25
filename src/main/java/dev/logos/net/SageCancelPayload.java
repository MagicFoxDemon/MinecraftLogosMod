package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.sage.SageServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: the player stopped waiting for the question {@code id}. */
public record SageCancelPayload(int id) implements CustomPacketPayload {
    public static final Type<SageCancelPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "sage_cancel"));
    public static final StreamCodec<FriendlyByteBuf, SageCancelPayload> CODEC = CustomPacketPayload.codec(
            (p, b) -> b.writeVarInt(p.id), b -> new SageCancelPayload(b.readVarInt()));

    @Override
    public Type<SageCancelPayload> type() {
        return TYPE;
    }

    public static void handle(SageCancelPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) SageServer.cancel(sp, p.id);
    }
}
