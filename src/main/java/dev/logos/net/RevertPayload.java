package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.Incarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: an incarnated player pressed the revert key (V by default) to take their own shape back, for free. */
public record RevertPayload() implements CustomPacketPayload {
    public static final RevertPayload INSTANCE = new RevertPayload();
    public static final Type<RevertPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "revert"));
    public static final StreamCodec<ByteBuf, RevertPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<RevertPayload> type() {
        return TYPE;
    }

    public static void handle(RevertPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) Incarnation.end(sp);
    }
}
