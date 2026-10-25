package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.sage.SageAgent;
import dev.logos.sage.SageServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: ask the server's sage. The whole conversation travels with the question, so the
 * server keeps no chat of its own; only the answer comes back ({@link SageReplyPayload}).
 * {@code history} is the conversation as JSON ({@link SageServer#readHistory}); {@code capacity} and
 * {@code emitter} describe the page the spell is for.
 */
public record SageAskPayload(int id, String lang, String history, String message, String current, int capacity, boolean emitter) implements CustomPacketPayload {
    public static final int MAX_HISTORY = 48_000;

    public static final Type<SageAskPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "sage_ask"));
    public static final StreamCodec<FriendlyByteBuf, SageAskPayload> CODEC = CustomPacketPayload.codec(SageAskPayload::write,
            b -> new SageAskPayload(b.readVarInt(), b.readUtf(16), b.readUtf(MAX_HISTORY), b.readUtf(SageAgent.MAX_MESSAGE), b.readUtf(SageAgent.MAX_SOURCE), b.readVarInt(), b.readBoolean()));

    private void write(FriendlyByteBuf b) {
        b.writeVarInt(id);
        b.writeUtf(lang, 16);
        b.writeUtf(history, MAX_HISTORY);
        b.writeUtf(message, SageAgent.MAX_MESSAGE);
        b.writeUtf(current, SageAgent.MAX_SOURCE);
        b.writeVarInt(capacity);
        b.writeBoolean(emitter);
    }

    @Override
    public Type<SageAskPayload> type() {
        return TYPE;
    }

    public static void handle(SageAskPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) SageServer.ask(sp, p);
    }
}
