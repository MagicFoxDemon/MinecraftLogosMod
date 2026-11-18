package dev.logos.client.port;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Stands in for 26.1's {@code net.minecraft.client.renderer.SubmitNodeCollector}: what an effect hands its geometry to.
 * {@link #order} puts what is submitted through it after (higher) or before (lower) the rest.
 */
public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
    OrderedSubmitNodeCollector order(int order);

    @FunctionalInterface
    interface CustomGeometryRenderer {
        void render(PoseStack.Pose pose, VertexConsumer buffer);
    }
}
