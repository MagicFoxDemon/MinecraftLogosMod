package dev.logos.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

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
 * Keeps one {@link FieldVisual} for each field whose element draws its own (see FIELD_VFX.md), fed by
 * {@link LogosFx} from the server's {@code FIELD_*} packets: reminded every few ticks while it lasts, told
 * of each pulse and each body that enters, and of its end. A field not heard of for a while is taken as
 * ended. An ended field is kept while its visual still plays out. Elements still on the legacy particle
 * ring are not kept here; {@link LogosFx} plays their old ring and pulse.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class FieldVisuals {
    /** No field lingers longer than this after it ends, whatever its element asks for. */
    public static final int LINGER_CAP = 100;
    /** The server reminds clients every 4 ticks; one not heard of for this long has gone. */
    private static final int STALE_TICKS = 20;

    private static final Map<Integer, LiveField> LIVE = new HashMap<>();
    private static final List<LiveField> ENDED = new ArrayList<>();
    private static final Map<Element, Boolean> OWN = new EnumMap<>(Element.class);
    /** Client scenes only: makes the visuals in place of {@link FieldVisual#create}. */
    private static @Nullable Function<Element, FieldVisual> substitute;
    /** The last field event heard, for the client scenes. */
    private static @Nullable String last;

    private FieldVisuals() {}

    /** Whether {@code element} draws its own field (so the legacy particles are not played). */
    public static boolean drawsOwn(Element element) {
        if (substitute != null) return !substitute.apply(element).legacy();
        return OWN.computeIfAbsent(element, e -> !FieldVisual.create(e).legacy());
    }

    private static FieldVisual create(Element element) {
        return substitute != null ? substitute.apply(element) : FieldVisual.create(element);
    }

    /** A reminder that field {@code id} is there (see {@code FxPayload.Kind.FIELD_HOLD}). */
    static void hold(int id, Element element, Vec3 centre, float radius, int age, int life, int every, int follow) {
        last = "hold " + id;
        if (!drawsOwn(element)) return;
        LiveField f = LIVE.get(id);
        if (f != null && f.element != element) {
            LIVE.remove(id);
            f.visual.end();
            f = null;
        }
        if (f == null) {
            f = new LiveField(id, element, create(element), centre);
            LIVE.put(id, f);
        }
        f.hold(centre, radius, age, life, every, follow);
    }

    static void pulse(int id) {
        last = "pulse " + id;
        LiveField f = LIVE.get(id);
        if (f != null) f.pulse();
    }

    static void enter(int id, Vec3 at, int entityId) {
        last = "enter " + id;
        LiveField f = LIVE.get(id);
        if (f != null) f.enter(at, entityId);
    }

    static void ended(int id) {
        last = "end " + id;
        LiveField f = LIVE.remove(id);
        if (f == null) return;
        f.end();
        ENDED.add(f);
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
        for (Iterator<LiveField> it = LIVE.values().iterator(); it.hasNext(); ) {
            LiveField f = it.next();
            if (now - f.heard > STALE_TICKS) {
                it.remove();
                f.end();
                ENDED.add(f);
                continue;
            }
            step(level, f);
        }
        ENDED.removeIf(f -> {
            if (f.sinceEnd(0) <= LINGER_CAP && step(level, f)) return false;
            f.visual.end();
            return true;
        });
    }

    private static boolean step(ClientLevel level, LiveField f) {
        f.settle(level);
        boolean more = f.visual.tick(level, f);
        f.entered = false;
        f.newPulses.clear();
        f.newEntries.clear();
        return more;
    }

    static boolean any() {
        return !LIVE.isEmpty() || !ENDED.isEmpty();
    }

    static void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        for (LiveField f : LIVE.values()) f.visual.submit(level, f, camera, partialTick, pose, out);
        for (LiveField f : ENDED) f.visual.submit(level, f, camera, partialTick, pose, out);
    }

    /** The field kept under {@code id}: still there, or else the latest one playing out (the client scenes check it). */
    public static @Nullable LiveField of(int id) {
        LiveField f = LIVE.get(id);
        if (f != null) return f;
        for (int i = ENDED.size() - 1; i >= 0; i--) if (ENDED.get(i).id == id) return ENDED.get(i);
        return null;
    }

    /** Every field kept, still there or playing out (the client scenes check it). */
    public static List<LiveField> all() {
        List<LiveField> all = new ArrayList<>(LIVE.values());
        all.addAll(ENDED);
        return all;
    }

    /** The last field event heard ("hold 12", "pulse 12", "enter 12", "end 12"), or null (the client scenes check it). */
    public static @Nullable String last() {
        return last;
    }

    /** Ends every field kept, so a client scene can start from nothing. */
    public static void clear() {
        LIVE.values().forEach(f -> f.visual.end());
        LIVE.clear();
        ENDED.forEach(f -> f.visual.end());
        ENDED.clear();
    }

    /** Client scenes only: draw fields with {@code visuals} instead of the elements' own (null restores them). */
    public static void substitute(@Nullable Function<Element, FieldVisual> visuals) {
        clear();
        substitute = visuals;
    }
}
