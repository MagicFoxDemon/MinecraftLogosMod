package dev.logos.net;

import dev.logos.LogosMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → the charging player and everyone tracking them: how a wand charge stands, for the
 * crosshair ring and the charging effects. {@code tier} is -1 once the charge has ended without
 * casting; {@code released} marks the moment it was let go into its spell.
 *
 * @param element  ordinal of the spell's main {@link dev.logos.lang.Element}
 * @param tier     0 ordinary, 1 elaborate, 2 grand, 3 colossal (by how much the charge injects)
 * @param progress injected share, 0 to 1
 */
public record ChargePayload(int entityId, int element, int tier, float progress, boolean full, boolean released) implements CustomPacketPayload {
    public static final Type<ChargePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "charge"));
    public static final StreamCodec<ByteBuf, ChargePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChargePayload::entityId, ByteBufCodecs.VAR_INT, ChargePayload::element,
            ByteBufCodecs.INT, ChargePayload::tier, ByteBufCodecs.FLOAT, ChargePayload::progress,
            ByteBufCodecs.BOOL, ChargePayload::full, ByteBufCodecs.BOOL, ChargePayload::released, ChargePayload::new);

    public ChargePayload(int entityId, int element, int tier, float progress, boolean full) {
        this(entityId, element, tier, progress, full, false);
    }

    public static ChargePayload ended(int entityId) {
        return new ChargePayload(entityId, 0, -1, 0, false);
    }

    /** The charge was let go into its spell: clients play its release at the cast point. */
    public static ChargePayload released(int entityId, int element, int tier) {
        return new ChargePayload(entityId, element, tier, 1, true, true);
    }

    public boolean ended() {
        return tier < 0;
    }

    @Override
    public Type<ChargePayload> type() {
        return TYPE;
    }
}
