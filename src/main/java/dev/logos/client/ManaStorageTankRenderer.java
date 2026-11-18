package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.block.ManaStorageTankBlock;
import dev.logos.block.ManaStorageTankBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.neoforged.neoforge.client.model.data.ModelData;

/** A mana storage tank's contents: the mana fog, as high as the tank is full, and the crystal growing on its floor. */
public class ManaStorageTankRenderer implements BlockEntityRenderer<ManaStorageTankBlockEntity> {
    /** The floor of the glass (y 3.25 of 16) the crystal stands on. */
    private static final float FLOOR = 3.25f / 16;
    /** A full-grown crystal's cross model, scaled to stand under the lid. */
    private static final float CRYSTAL = 0.75f;
    /** Mana glows: fog and crystal are drawn at full light. */
    private static final int GLOW = 0xF000F0;

    private final BlockRenderDispatcher blocks;

    public ManaStorageTankRenderer(BlockEntityRendererProvider.Context context) {
        this.blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(ManaStorageTankBlockEntity tank, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        int tier = tank.growingTier();
        if (tier >= 0) {
            float scale = CRYSTAL * (0.3f + 0.7f * tank.growth());
            poseStack.pushPose();
            poseStack.translate(0.5f, FLOOR, 0.5f);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5f, 0, -0.5f);
            blocks.renderSingleBlock(LogosMod.MANA_CLUSTER.get().defaultBlockState().setValue(ManaStorageTankBlock.Cluster.TIER, tier),
                    poseStack, buffers, GLOW, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            poseStack.popPose();
        }
        int level = (int) Math.ceil(tank.mana() / ManaStorageTankBlockEntity.MAX_MANA * 10);
        if (level > 0) {
            blocks.renderSingleBlock(LogosMod.MANA_FOG.get().defaultBlockState().setValue(ManaStorageTankBlock.Fog.LEVEL, Math.min(10, level)),
                    poseStack, buffers, GLOW, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        }
    }
}
