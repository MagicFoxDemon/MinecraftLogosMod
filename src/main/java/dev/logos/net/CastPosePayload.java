package dev.logos.net;

import dev.logos.LogosMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → everyone near the caster: a pose the caster holds ({@code on}) or has let go. Sent when a
 * hold starts and ends, and again every so often while it lasts so that players who come close later
 * see it too (see {@link dev.logos.runtime.CastPose}).
 *
 * @param hold which hold this is: one caster may hold several at once (two beams), each ends on its own
 * @param pose what the client draws, looked up in {@code dev.logos.client.CastPoses}
 */
public record CastPosePayload(int entityId, int hold, Identifier pose, boolean on) implements CustomPacketPayload {
    public static final Type<CastPosePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "cast_pose"));
    public static final StreamCodec<ByteBuf, CastPosePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CastPosePayload::entityId, ByteBufCodecs.VAR_INT, CastPosePayload::hold,
            Identifier.STREAM_CODEC, CastPosePayload::pose, ByteBufCodecs.BOOL, CastPosePayload::on, CastPosePayload::new);

    @Override
    public Type<CastPosePayload> type() {
        return TYPE;
    }
}
