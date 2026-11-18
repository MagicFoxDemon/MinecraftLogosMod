package dev.logos.client;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.block.CultivationJarBlockEntity;
import dev.logos.client.port.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import dev.logos.client.port.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The crop growing in a cultivation jar: its own block model at its current age, shrunk to stand
 * on the soil inside the glass (the art's {@code contents_anchor}, soil centre at y 4.5 of 16).
 */
public class CultivationJarRenderer implements BlockEntityRenderer<CultivationJarBlockEntity, CultivationJarRenderer.State> {
    private static final BlockDisplayContext DISPLAY = BlockDisplayContext.create();
    private static final float SOIL = 4.5f / 16;
    /** A full-height crop reaches just under the jar's shoulder (y 11). */
    private static final float SCALE = 0.4f;

    private final BlockModelResolver models;

    public CultivationJarRenderer(BlockEntityRendererProvider.Context context) {
        this.models = context.blockModelResolver();
    }

    public static class State extends BlockEntityRenderState {
        final BlockModelRenderState plant = new BlockModelRenderState();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CultivationJarBlockEntity jar, State state, float partialTicks, Vec3 cameraPosition,
            @Nullable ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(jar, state, partialTicks, cameraPosition, breakProgress);
        BlockState plant = jar.plant();
        if (plant == null) state.plant.clear();
        else models.update(state.plant, plant, DISPLAY);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.plant.isEmpty()) return;
        poseStack.pushPose();
        poseStack.translate(0.5f, SOIL, 0.5f);
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(-0.5f, 0, -0.5f);
        state.plant.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}
