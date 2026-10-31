package dev.logos.net;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: a golden pillar burst at (x, y, z) at this scale; the client works out the whole picture. */
public record PillarBurstPayload(double x, double y, double z, float scale) implements CustomPacketPayload {
    public static final Type<PillarBurstPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "pillar_burst"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PillarBurstPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeFloat(p.scale);
    }, b -> new PillarBurstPayload(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()));

    @Override
    public Type<PillarBurstPayload> type() {
        return TYPE;
    }
}
