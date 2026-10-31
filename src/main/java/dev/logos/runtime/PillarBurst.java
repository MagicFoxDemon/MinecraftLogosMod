package dev.logos.runtime;

import dev.logos.net.PillarBurstPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A golden pillar of light bursting from {@code at} (usually a point on the ground) up into the sky, with
 * shockwaves, dust and debris (FORM_VFX.md). Only a picture: it neither harms nor breaks blocks.
 */
public final class PillarBurst {
    /** The pillar stands 64 blocks tall; players far off should see it too. */
    private static final double RANGE = 160;

    private PillarBurst() {}

    public static void play(ServerLevel level, Vec3 at, float scale) {
        PillarBurstPayload payload = new PillarBurstPayload(at.x, at.y, at.z, scale);
        for (ServerPlayer player : level.players()) {
            // Only connections that negotiated the channel (fake players have not).
            if (player.distanceToSqr(at) <= RANGE * RANGE && player.connection.hasChannel(PillarBurstPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
