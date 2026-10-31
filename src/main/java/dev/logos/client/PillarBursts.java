package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.net.PillarBurstPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** The golden pillars playing on this client ({@link dev.logos.runtime.PillarBurst}): made when the packet comes, stepped each tick, drawn each frame, dropped once played out or when the level changes. */
public final class PillarBursts {
    private static final int CAP = 8;
    private static final List<PillarBurstVisual> LIVE = new ArrayList<>();
    private static Level shown;
    private static long seed;

    private PillarBursts() {}

    static void receive(PillarBurstPayload p) {
        spawn(new Vec3(p.x(), p.y(), p.z()), p.scale());
    }

    /** Plays one on this client alone, without the server (the client scenes replay it with this). */
    public static void spawn(Vec3 at, float scale) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != shown) LIVE.clear();
        shown = level;
        if (LIVE.size() >= CAP) LIVE.removeFirst();
        LIVE.add(new PillarBurstVisual(level, at, scale, ++seed * 0x9E3779B97F4A7C15L));
    }

    /** How many are playing (the client scenes check it). */
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
        for (PillarBurstVisual v : LIVE) v.submit(view, pt, pose, out, VfxParts.layered(raw, out));
        pose.popPose();
    }
}
