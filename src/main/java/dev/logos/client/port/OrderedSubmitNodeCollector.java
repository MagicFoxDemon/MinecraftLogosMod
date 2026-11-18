package dev.logos.client.port;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Stands in for 26.1's {@code net.minecraft.client.renderer.OrderedSubmitNodeCollector}, as far as the effects use it:
 * custom geometry of a render type, and name tags.
 */
public interface OrderedSubmitNodeCollector {
    void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer);

    void submitNameTag(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough,
            int lightCoords, double distanceToCameraSq, CameraRenderState camera);
}
