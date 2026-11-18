package dev.logos.client.port;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Stands in for 26.1's {@code net.minecraft.client.renderer.OrderedSubmitNodeCollector}, as far as the effects use it:
 * custom geometry of a render type, name tags, and (port only) drawings into a buffer source.
 */
public interface OrderedSubmitNodeCollector {
    void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer);

    void submitNameTag(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough,
            int lightCoords, double distanceToCameraSq, CameraRenderState camera);

    /**
     * Port only: draws, in its turn, what 1.21.1 draws into a buffer source (an item, an entity), {@code poseStack} at
     * the pose it was submitted with. Stands in for 26.1's item and entity submits.
     */
    void submitDrawing(PoseStack poseStack, Drawing drawing);

    @FunctionalInterface
    interface Drawing {
        void draw(PoseStack poseStack, MultiBufferSource buffers);
    }
}
