package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a physical beam leaves when it lets go: the physical ray's afterline along the line as last drawn. The
 * needle narrows to a hairline in two ticks, then breaks into dashes that blink out one by one and is gone in
 * under six seconds. Drawn by {@link PhysicalRayVisual#afterline()}, so the two wakes stay the same.
 */
final class PhysicalBeamWake implements RayVisual {
    private final PhysicalRayVisual line = PhysicalRayVisual.afterline();

    @Override public boolean legacy() { return false; }

    @Override public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) { line.start(level, from, to, end); }

    @Override public boolean tick(Level level) { return line.tick(level); }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        line.submit(level, camera, partialTick, pose, out);
    }

    @Override public void end() { line.end(); }
}
