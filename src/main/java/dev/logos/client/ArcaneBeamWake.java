package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What an arcane beam leaves when it ends: the arcane ray's residue without its burst ({@link ArcaneRayVisual#wake()}).
 * The beam's violet body and starry band narrow into a thread that dissolves in patches, while glowing runes
 * and four-point glints written along the line drift up, twinkle and go out one by one, all gone in 6.8 s.
 */
final class ArcaneBeamWake implements RayVisual {
    private final ArcaneRayVisual wake = ArcaneRayVisual.wake();

    @Override public boolean legacy() { return false; }

    @Override public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) { wake.start(level, from, to, end); }

    @Override public boolean tick(Level level) { return wake.tick(level); }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        wake.submit(level, camera, partialTick, pose, out);
    }

    @Override public void end() { wake.end(); }
}
