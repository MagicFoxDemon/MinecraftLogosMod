package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A lasting arcane {@code beam}. Not drawn yet: while {@link #legacy()} is true LogosFx keeps the generic
 * beacon-style tube (see BEAM_VFX.md). Same calls as the other elements' beam visuals.
 */
final class ArcaneBeamVisual {
    /** True until this element draws its own beam. */
    static boolean legacy() { return true; }

    /** {@code defaultMotes}: false when a {@code :trail} particle replaces the element's own motes. */
    ArcaneBeamVisual(boolean defaultMotes) {}

    /** Each frame, from the cast point to the end as it reaches out; {@code out} already goes through {@link ShaderPackOverlay}. */
    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera, PoseStack pose, SubmitNodeCollector out) {}

    /** The beam is gone: stop anything still playing. */
    void end() {}
}
