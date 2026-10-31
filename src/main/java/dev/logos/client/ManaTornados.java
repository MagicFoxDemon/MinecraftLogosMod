package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.net.ManaTornadoPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * The mana tornadoes whirling on this client ({@link dev.logos.runtime.ManaTornado}): made when the packet comes,
 * one per entity (a later packet replaces it), stepped each tick, drawn each frame, dropped once faded or when the
 * level changes.
 */
public final class ManaTornados {
    private static final int CAP = 8;
    private static final List<ManaTornadoVisual> LIVE = new ArrayList<>();
    private static Level shown;
    private static long seed;

    private ManaTornados() {}

    static void receive(ManaTornadoPayload p) {
        spawn(p.entity(), new Vec3(p.x(), p.y(), p.z()), p.ticks(), p.scale(), p.cycle());
    }

    /**
     * Winds one up on this client alone, without the server (the client scenes use it too). With {@code entity}
     * -1 it stays at {@code at}; negative {@code ticks} whirl on, 0 releases the one on that entity.
     */
    public static void spawn(int entity, Vec3 at, int ticks, float scale, int cycle) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != shown) LIVE.clear();
        shown = level;
        if (ticks == 0) {
            for (ManaTornadoVisual v : LIVE) if (entity >= 0 && v.entity() == entity) v.release();
            return;
        }
        if (entity >= 0) LIVE.removeIf(v -> v.entity() == entity);
        if (LIVE.size() >= CAP) LIVE.removeFirst();
        LIVE.add(new ManaTornadoVisual(level, entity, at, ticks, scale, cycle, ++seed * 0x9E3779B97F4A7C15L));
    }

    /** How many are whirling (the client scenes check it). */
    public static int count() { return LIVE.size(); }

    public static void clear() { LIVE.clear(); }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) { LIVE.clear(); shown = mc.level; }
        if (mc.isPaused()) return;
        LIVE.removeIf(v -> !v.tick());
    }

    /** Called after {@link LogosFx#submit}, which begins the ShaderPackOverlay frame. */
    static void submit(SubmitCustomGeometryEvent event) {
        if (LIVE.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (ManaTornadoVisual v : LIVE) v.submit(view, pt, pose, out, VfxParts.layered(raw, out));
        pose.popPose();
    }
}
