package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;

/**
 * The lasting sphere round one body while its spell shield holds, after Piloto's HoneyShield in an
 * elementless look. {@link WardSpheres} makes one per shielded body and drives it; this draws nothing yet.
 */
final class WardSphereVisual {
    /** Each client tick while the shield holds. */
    void tick(LivingEntity body) {}

    /**
     * Each frame. {@code firstPerson} when {@code body} is the camera seen from its own eyes: the sphere
     * must not cloud that view, and may simply not be drawn.
     */
    void submit(LivingEntity body, CameraRenderState cam, float partialTick, PoseStack pose, SubmitNodeCollector out, boolean firstPerson) {}

    /** The shield is gone (broken, or the body died or left view); release anything still playing. */
    void end() {}
}
