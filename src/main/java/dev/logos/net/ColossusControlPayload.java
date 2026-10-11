package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.Colossi;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: a colossus rider's control (toggle drive mode, strike, start or loose the cannon); see {@link Colossi}. */
public record ColossusControlPayload(byte action) implements CustomPacketPayload {
    public static final Type<ColossusControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "colossus_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ColossusControlPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, ColossusControlPayload::action, ColossusControlPayload::new);

    @Override
    public Type<ColossusControlPayload> type() {
        return TYPE;
    }

    public static void handle(ColossusControlPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) Colossi.control(sp, p.action);
    }
}
