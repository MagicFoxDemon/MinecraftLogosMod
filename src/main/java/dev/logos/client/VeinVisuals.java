package dev.logos.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps one {@link VeinVisual} for each head of each vein, fed by {@link LogosFx} from the server's {@code VEIN_*}
 * packets: told every tick where the head stands while it crawls, and when it stops. A head not heard of for a while
 * is taken as stopped. A stopped head is kept while its visual still plays out.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class VeinVisuals {
    /** No head lingers longer than this after it stops, whatever its element asks for. */
    public static final int LINGER_CAP = 100;
    /** The server tells clients every tick; a head not heard of for this long has gone. */
    private static final int STALE_TICKS = 10;

    private static final Map<Long, LiveVein> LIVE = new HashMap<>();
    private static final List<LiveVein> ENDED = new ArrayList<>();

    private VeinVisuals() {}

    private static long key(int id, int head) {
        return (long) id << 8 | head & 0xFF;
    }

    /** Head {@code head} of vein {@code id} stands at {@code at} (see {@code FxPayload.Kind.VEIN_HOLD}). */
    static void hold(int id, int head, Element element, Vec3 at, Vec3 dir, float width, float height) {
        long now = ClientCharges.ticks();
        LiveVein v = LIVE.get(key(id, head));
        if (v == null) {
            v = new LiveVein(id, head, element, VeinVisual.create(element), at, now);
            LIVE.put(key(id, head), v);
        }
        v.pos = at;
        if (dir.lengthSqr() > 1e-6) v.dir = dir.normalize();
        v.width = width;
        v.height = height;
        v.heard = now;
    }

    /** That head crawls no further, at {@code at}. */
    static void ended(int id, int head, Vec3 at, boolean struck) {
        LiveVein v = LIVE.remove(key(id, head));
        if (v == null) return;
        v.pos = at;
        v.struck = struck;
        stop(v);
    }

    private static void stop(LiveVein v) {
        v.ended = true;
        ENDED.add(v);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        if (mc.isPaused() || ClientCharges.frozen()) return;
        long now = ClientCharges.ticks();
        for (Iterator<LiveVein> it = LIVE.values().iterator(); it.hasNext(); ) {
            LiveVein v = it.next();
            if (now - v.heard > STALE_TICKS) {
                it.remove();
                stop(v);
                continue;
            }
            step(level, v);
        }
        ENDED.removeIf(v -> {
            v.sinceEnd++;
            if (v.sinceEnd <= LINGER_CAP && step(level, v)) return false;
            v.visual.end();
            return true;
        });
    }

    private static boolean step(ClientLevel level, LiveVein v) {
        boolean more = v.visual.tick(level, v);
        v.prev = v.pos;
        return more;
    }

    static boolean any() {
        return !LIVE.isEmpty() || !ENDED.isEmpty();
    }

    static void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        for (LiveVein v : LIVE.values()) v.visual.submit(level, v, camera, partialTick, pose, out);
        for (LiveVein v : ENDED) v.visual.submit(level, v, camera, partialTick, pose, out);
    }

    /** Every head kept, still crawling or playing out (the client scenes check it). */
    public static List<LiveVein> all() {
        List<LiveVein> all = new ArrayList<>(LIVE.values());
        all.addAll(ENDED);
        return all;
    }

    /** Ends every head kept, so a client scene can start from nothing. */
    public static void clear() {
        LIVE.values().forEach(v -> v.visual.end());
        LIVE.clear();
        ENDED.forEach(v -> v.visual.end());
        ENDED.clear();
    }
}
