package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a void beam leaves when it ends: the void ray's narrowing line ({@link VoidRayVisual#wake}), without
 * the ray's burst. It opens as wide as the beam's void core was drawn and narrows away over about five seconds.
 */
final class VoidBeamWake implements RayVisual {
    /** The radius a {@code beam} spell asks for by default, used if this line was never drawn as a void beam. */
    private static final float DEFAULT_RADIUS = .35f;
    private VoidRayVisual line;

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        float radius = VoidBeamVisual.drawnRadius(from, to, DEFAULT_RADIUS);
        // Just inside the beam's solid tube, so the beam seems to settle into the line rather than jump.
        line = VoidRayVisual.wake(radius * VoidBeamVisual.BODY * .8f);
        line.start(level, from, to, end);
    }

    @Override
    public boolean tick(Level level) { return line != null && line.tick(level); }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (line != null) line.submit(level, camera, partialTick, pose, out);
    }

    @Override
    public void end() { if (line != null) line.end(); }
}
