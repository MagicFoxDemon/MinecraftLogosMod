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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps one {@link ChargeVisual} for each charge whose element draws its own (see CHARGE_VFX.md), fed by
 * {@link ClientCharges} as the server's updates arrive: charging, held once full, then released into its
 * spell or fizzled. A released or fizzled charge is kept while its visual still plays out. Elements still
 * on the legacy particle charge are not kept here; {@link ChargeEffects} draws them.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ChargeVisuals {
    /** No charge lingers longer than this after it ends, whatever its element asks for. */
    public static final int LINGER_CAP = 200;

    /** Charges still charging or held, by caster. */
    private static final Map<Integer, LiveCharge> LIVE = new HashMap<>();
    /** Charges that ended and are playing out. */
    private static final List<LiveCharge> ENDED = new ArrayList<>();
    private static final Map<Element, Boolean> OWN = new EnumMap<>(Element.class);
    /** Client scenes only: makes the visuals in place of {@link ChargeVisual#create}. */
    private static @Nullable Function<Element, ChargeVisual> substitute;

    private ChargeVisuals() {}

    /** Whether {@code element} draws its own charge (so the legacy particles are not drawn). */
    public static boolean drawsOwn(Element element) {
        if (substitute != null) return !substitute.apply(element).legacy();
        return OWN.computeIfAbsent(element, e -> !ChargeVisual.create(e).legacy());
    }

    private static ChargeVisual create(Element element) {
        return substitute != null ? substitute.apply(element) : ChargeVisual.create(element);
    }

    /** An update for a charge still under way; {@code s} is its synced state. */
    static void charging(int entityId, ClientCharges.State s) {
        if (!drawsOwn(s.element)) return;
        LiveCharge c = LIVE.get(entityId);
        if (c != null && (c.element != s.element || c.tier != s.tier)) {
            LIVE.remove(entityId);
            c.visual.end();
            c = null;
        }
        if (c == null) {
            c = new LiveCharge(entityId, s.element, s.tier, create(s.element), LiveCharge.Phase.CHARGING);
            LIVE.put(entityId, c);
        }
        c.state = s;
        if (s.full) c.enter(LiveCharge.Phase.HELD);
    }

    /** The charge was let go into its spell; one never seen charging (out of range until now) still plays its release. */
    static void released(int entityId, Element element, int tier) {
        LiveCharge c = LIVE.remove(entityId);
        if (c != null && (c.element != element || c.tier != tier)) {
            c.visual.end();
            c = null;
        }
        if (c == null) {
            if (!drawsOwn(element)) return;
            c = new LiveCharge(entityId, element, tier, create(element), LiveCharge.Phase.RELEASED);
        } else {
            c.enter(LiveCharge.Phase.RELEASED);
        }
        ENDED.add(c);
    }

    /** The charge ended without casting. */
    static void fizzled(int entityId) {
        LiveCharge c = LIVE.remove(entityId);
        if (c == null) return;
        c.enter(LiveCharge.Phase.FIZZLED);
        ENDED.add(c);
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
        for (Iterator<LiveCharge> it = LIVE.values().iterator(); it.hasNext(); ) {
            LiveCharge c = it.next();
            // Not heard of for a while, or its caster is gone: ClientCharges has dropped it.
            if (ClientCharges.all().get(c.entityId) != c.state) {
                c.visual.end();
                it.remove();
                continue;
            }
            c.visual.tick(level, c);
            c.entered = false;
        }
        ENDED.removeIf(c -> {
            if (c.phaseAge(0) <= LINGER_CAP && c.visual.tick(level, c)) {
                c.entered = false;
                return false;
            }
            c.visual.end();
            return true;
        });
    }

    static boolean any() {
        return !LIVE.isEmpty() || !ENDED.isEmpty();
    }

    static void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        for (LiveCharge c : LIVE.values()) c.visual.submit(level, c, camera, partialTick, pose, out);
        for (LiveCharge c : ENDED) c.visual.submit(level, c, camera, partialTick, pose, out);
    }

    /** The charge kept for {@code entityId}: still under way, or else the latest one playing out (the client scenes check it). */
    public static @Nullable LiveCharge of(int entityId) {
        LiveCharge c = LIVE.get(entityId);
        if (c != null) return c;
        for (int i = ENDED.size() - 1; i >= 0; i--) if (ENDED.get(i).entityId == entityId) return ENDED.get(i);
        return null;
    }

    /** How many charges are kept, under way or playing out (the client scenes check it). */
    public static int count() {
        return LIVE.size() + ENDED.size();
    }

    /** Ends every charge kept, so a client scene can start from nothing. */
    public static void clear() {
        LIVE.values().forEach(c -> c.visual.end());
        LIVE.clear();
        ENDED.forEach(c -> c.visual.end());
        ENDED.clear();
    }

    /** Client scenes only: draw charges with {@code visuals} instead of the elements' own (null restores them). */
    public static void substitute(@Nullable Function<Element, ChargeVisual> visuals) {
        clear();
        substitute = visuals;
    }
}
