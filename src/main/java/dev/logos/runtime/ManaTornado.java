package dev.logos.runtime;

import dev.logos.net.ManaTornadoPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Mana spiralling up round a caster into a twisting tornado, with a turning swirl underfoot, motes splashing up,
 * dust raised and debris swept up (FORM_VFX.md). It follows the caster, and its colour may turn slowly in hue. Only
 * a picture: it neither harms nor breaks blocks.
 */
public final class ManaTornado {
    /** The tornado is a dozen blocks tall; everyone round about should see it. */
    private static final double RANGE = 128;
    /** By default its colour turns a full circle in 12 seconds. */
    public static final int CYCLE = 240;

    private ManaTornado() {}

    /** Winds one up round {@code caster} for {@code ticks} (0 or less: until {@link #stop}); {@code cycle} is the ticks its colour takes to turn a full circle, 0 to keep it. */
    public static void play(ServerLevel level, Entity caster, int ticks, float scale, int cycle) {
        Vec3 at = caster.position();
        send(level, at, new ManaTornadoPayload(caster.getId(), at.x, at.y, at.z, ticks == 0 ? -1 : ticks, scale, cycle));
    }

    /** One that follows nothing and stays at {@code at}. */
    public static void play(ServerLevel level, Vec3 at, int ticks, float scale, int cycle) {
        send(level, at, new ManaTornadoPayload(-1, at.x, at.y, at.z, ticks == 0 ? -1 : ticks, scale, cycle));
    }

    /** Releases the tornado round {@code caster}: it fades out from the bottom up rather than vanishing at once. */
    public static void stop(ServerLevel level, Entity caster) {
        Vec3 at = caster.position();
        send(level, at, new ManaTornadoPayload(caster.getId(), at.x, at.y, at.z, 0, 1, 0));
    }

    private static void send(ServerLevel level, Vec3 at, ManaTornadoPayload payload) {
        for (ServerPlayer player : level.players()) {
            // Only connections that negotiated the channel (fake players have not).
            if (player.distanceToSqr(at) <= RANGE * RANGE && player.connection.hasChannel(ManaTornadoPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
