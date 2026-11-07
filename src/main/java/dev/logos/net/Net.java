package dev.logos.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sending payloads to clients, in one place so that the channel check is never left out. */
public final class Net {
    private Net() {}

    /** To the players of {@code level} within {@code radius} of {@code at} whose connection knows the channel (fake players and modless clients do not). */
    public static void sendNear(ServerLevel level, Vec3 at, double radius, CustomPacketPayload payload) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at) <= radius * radius && p.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
