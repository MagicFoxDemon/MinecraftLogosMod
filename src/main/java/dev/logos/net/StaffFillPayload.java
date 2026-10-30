package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.StaffCharging;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: left-click went down ({@code filling}) or up with a staff in the main hand; see {@link StaffCharging}. */
public record StaffFillPayload(boolean filling) implements CustomPacketPayload {
    public static final Type<StaffFillPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "staff_fill"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StaffFillPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StaffFillPayload::filling, StaffFillPayload::new);

    @Override
    public Type<StaffFillPayload> type() {
        return TYPE;
    }

    public static void handle(StaffFillPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        if (p.filling) StaffCharging.start(sp);
        else StaffCharging.stop(sp);
    }
}
