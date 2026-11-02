package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.WrapObj;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: a wrapped hand swung, hit or miss, charged far enough to count (the server never hears of a miss otherwise); see {@link WrapObj#swingOf}. */
public record WrapSwingPayload() implements CustomPacketPayload {
    public static final Type<WrapSwingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "wrap_swing"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WrapSwingPayload> CODEC = StreamCodec.unit(new WrapSwingPayload());

    @Override
    public Type<WrapSwingPayload> type() {
        return TYPE;
    }

    public static void handle(WrapSwingPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        WrapObj w = WrapObj.on(sp);
        if (w != null) w.swingOf(sp);
    }
}
