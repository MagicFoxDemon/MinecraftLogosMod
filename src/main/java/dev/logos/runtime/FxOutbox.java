package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.net.FxPayload;
import dev.logos.net.FxPayload.Kind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What one player is to be shown of spell FX ({@link Vfx}) this tick, sent as one packet as the tick ends. Updates of
 * something lasting ({@link Kind#ongoing}) are thinned on the way in: a newer one replaces an older one queued this tick,
 * and a far player is told less often. Everything else goes out always and in the order it happened, so what starts or
 * ends an effect is never lost; the first update of anything is always sent.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
final class FxOutbox {
    /** Far off, a player gets one update in this many: clients give up on a beam or a vein head after 10 ticks of silence. */
    private static final int FAR_EVERY = 4;
    /** The same for a field, which is only reminded every 4 ticks and kept for 20. */
    private static final int FAR_FIELD_EVERY = 8;
    /** Keeps a packet far below the 1 MiB a custom payload may hold. */
    private static final int MOST_IN_A_PACKET = 4096;

    private static final Map<ServerPlayer, FxOutbox> BOXES = new WeakHashMap<>();
    /** Between ticks (a packet handler, a command) nothing would come to send it: it goes at once, as it always did. */
    private static boolean inTick;

    private final List<FxPayload> queued = new ArrayList<>();
    /** Where each lasting thing's update of this tick is queued. */
    private final Map<Long, Integer> slots = new HashMap<>();
    /** Where the last other event queued this tick about an id is: an update never passes one of those. */
    private final Map<Integer, Integer> events = new HashMap<>();
    /** The tick each lasting thing was last told about. */
    private final Map<Long, Integer> told = new HashMap<>();

    private FxOutbox() {}

    static void queue(ServerPlayer player, FxPayload p) {
        FxOutbox box = BOXES.computeIfAbsent(player, k -> new FxOutbox());
        int now = player.level().getServer().getTickCount();
        box.add(p, p.kindEnum().ongoing() ? every(p, player.position()) : 1, now);
        if (!inTick) box.flush(player, now);
    }

    private void add(FxPayload p, int every, int now) {
        if (!p.kindEnum().ongoing()) {
            if (p.id() != 0) events.put(p.id(), queued.size());
            queued.add(p);
            return;
        }
        long key = key(p);
        Integer slot = slots.get(key), event = events.get(p.id());
        if (slot != null && (event == null || event < slot)) {
            queued.set(slot, p);
            return;
        }
        Integer last = told.get(key);
        if (every > 1 && last != null && now - last < every) return;
        told.put(key, now);
        slots.put(key, queued.size());
        queued.add(p);
    }

    /** One lasting thing, whatever its update says: a vein tells of each of its heads apart. */
    private static long key(FxPayload p) {
        return (long) p.id() << 16 | (p.kind() & 0xFF) << 8 | (p.kindEnum() == Kind.VEIN_HOLD ? p.variant() & 0xFF : 0);
    }

    /** How many ticks apart a player at {@code at} is told of {@code p}: every tick when near it, then half as often, then a quarter. */
    private static int every(FxPayload p, Vec3 at) {
        double near = LogosConfig.FX_NEAR_DISTANCE.get(), off = distance(p, at);
        if (off <= near) return 1;
        if (off <= near * 2) return p.kindEnum() == Kind.FIELD_HOLD ? FAR_EVERY : 2;
        return p.kindEnum() == Kind.FIELD_HOLD ? FAR_FIELD_EVERY : FAR_EVERY;
    }

    /** How far {@code at} is from the nearest of what {@code p} shows: anywhere along a beam, anywhere inside a field. */
    private static double distance(FxPayload p, Vec3 at) {
        Vec3 from = new Vec3(p.x(), p.y(), p.z());
        return switch (p.kindEnum()) {
            case BEAM_HOLD -> {
                Vec3 along = new Vec3(p.ax(), p.ay(), p.az()).subtract(from);
                double t = along.lengthSqr() < 1e-6 ? 0 : Math.clamp(at.subtract(from).dot(along) / along.lengthSqr(), 0, 1);
                yield at.distanceTo(from.add(along.scale(t)));
            }
            case FIELD_HOLD -> Math.max(0, at.distanceTo(from) - p.size());
            default -> at.distanceTo(from);
        };
    }

    private void flush(ServerPlayer player, int now) {
        if (now % 40 == 0) told.values().removeIf(t -> now - t > FAR_FIELD_EVERY);
        if (queued.isEmpty()) return;
        try {
            if (!player.hasDisconnected()) {
                for (int i = 0; i < queued.size(); i += MOST_IN_A_PACKET) {
                    PacketDistributor.sendToPlayer(player, new FxPayload.Batch(List.copyOf(queued.subList(i, Math.min(i + MOST_IN_A_PACKET, queued.size())))));
                }
            }
        } catch (RuntimeException e) {
            SpellRuntime.report("Spell FX", e);
        }
        queued.clear();
        slots.clear();
        events.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onTickStart(ServerTickEvent.Pre event) {
        inTick = true;
    }

    /** Last of the tick's end: the spells themselves run in this event ({@code LogosMod.onServerTick}). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onTickEnd(ServerTickEvent.Post event) {
        inTick = false;
        int now = event.getServer().getTickCount();
        BOXES.forEach((player, box) -> box.flush(player, now));
    }
}
