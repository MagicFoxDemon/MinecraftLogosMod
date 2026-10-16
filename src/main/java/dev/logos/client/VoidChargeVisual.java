package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * The void charge (CHARGE_VFX.md): the shared charge in void's colours, its grand and colossal inner circle
 * the ringed eye of MagicCircle 07, and at the head, in place of the shared ball, a sphere of open void in
 * the style of every void effect ({@link VoidChargeBall}: the void beam's sphere, the screen-projected
 * starfield rimmed in violet), with a see-through one closing in on a colossal charge.
 */
final class VoidChargeVisual extends ChargeCore {
    VoidChargeVisual() {
        super(ChargeStyle.of(Element.VOID).withCircles("charge/circle_17", "charge/circle_18", "void_charge/circle_07",
                "charge/circle_03", "charge/circle_11", "void_charge/circle_07", "charge/circle_17"), false);
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        VoidChargeBall.submit(charge, ChargeParts.View.of(camera), partialTick, pose, out);
        pose.popPose();
    }
}
