package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.block.CultivationJarBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * The crop growing in a cultivation jar: its own block model at its current age, shrunk to stand
 * on the soil inside the glass (the art's {@code contents_anchor}, soil centre at y 4.5 of 16).
 */
public class CultivationJarRenderer implements BlockEntityRenderer<CultivationJarBlockEntity> {
    private static final float SOIL = 4.5f / 16;
    /** A full-height crop reaches just under the jar's shoulder (y 11). */
    private static final float SCALE = 0.4f;

    private final BlockRenderDispatcher blocks;

    public CultivationJarRenderer(BlockEntityRendererProvider.Context context) {
        this.blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(CultivationJarBlockEntity jar, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        BlockState plant = jar.plant();
        if (plant == null) return;
        poseStack.pushPose();
        poseStack.translate(0.5f, SOIL, 0.5f);
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(-0.5f, 0, -0.5f);
        blocks.renderSingleBlock(plant, poseStack, buffers, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        poseStack.popPose();
    }
}
