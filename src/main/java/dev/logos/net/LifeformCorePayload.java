package dev.logos.net;

import dev.logos.LogosMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client, just before a lifeform's core panel opens: which lifeform it belongs to. */
public record LifeformCorePayload(int entityId) implements CustomPacketPayload {
    public static final Type<LifeformCorePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "lifeform_core"));
    public static final StreamCodec<ByteBuf, LifeformCorePayload> CODEC =
            ByteBufCodecs.VAR_INT.map(LifeformCorePayload::new, LifeformCorePayload::entityId);

    @Override
    public Type<LifeformCorePayload> type() {
        return TYPE;
    }
}
