package dev.logos.net;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client: a mana tornado wound up round someone. {@code entity} is who it follows (-1 to stay at
 * (x, y, z)); {@code ticks} how long it lasts (0 releases the one on that entity, negative whirls on until
 * released); then its scale and the ticks its colour takes to turn a full circle (0 keeps it). The client works out
 * the whole picture.
 */
public record ManaTornadoPayload(int entity, double x, double y, double z, int ticks, float scale, int cycle) implements CustomPacketPayload {
    public static final Type<ManaTornadoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_tornado"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaTornadoPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeVarInt(p.entity); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
        b.writeVarInt(p.ticks); b.writeFloat(p.scale); b.writeVarInt(p.cycle);
    }, b -> new ManaTornadoPayload(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readFloat(), b.readVarInt()));

    @Override
    public Type<ManaTornadoPayload> type() {
        return TYPE;
    }
}
