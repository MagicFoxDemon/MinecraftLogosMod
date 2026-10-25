package dev.logos.client;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.block.ManaStorageTankBlock;
import dev.logos.block.ManaStorageTankBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/**
 * What is inside a mana storage tank's glass: pale blue fog that stands higher the more mana it holds,
 * and the crystal growing on its seed, from a sliver to full size. Both are block models of the render-only
 * blocks {@link ManaStorageTankBlock.Fog} and {@link ManaStorageTankBlock.Cluster}.
 */
public class ManaStorageTankRenderer implements BlockEntityRenderer<ManaStorageTankBlockEntity, ManaStorageTankRenderer.State> {
    private static final BlockDisplayContext DISPLAY = BlockDisplayContext.create();
    /** The floor of the glass (y 3.25 of 16) the crystal stands on. */
    private static final float FLOOR = 3.25f / 16;
    /** A full-grown crystal's cross model, scaled to stand under the lid. */
    private static final float CRYSTAL = 0.75f;
    /** Mana glows: fog and crystal are drawn at full light. */
    private static final int GLOW = 0xF000F0;

    private final BlockModelResolver models;

    public ManaStorageTankRenderer(BlockEntityRendererProvider.Context context) {
        this.models = context.blockModelResolver();
    }

    public static class State extends BlockEntityRenderState {
        final BlockModelRenderState fog = new BlockModelRenderState();
        final BlockModelRenderState crystal = new BlockModelRenderState();
        float growth;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ManaStorageTankBlockEntity tank, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(tank, state, partialTicks, cameraPosition, breakProgress);
        int level = (int) Math.ceil(tank.mana() / ManaStorageTankBlockEntity.MAX_MANA * 10);
        if (level <= 0) state.fog.clear();
        else models.update(state.fog, LogosMod.MANA_FOG.get().defaultBlockState().setValue(ManaStorageTankBlock.Fog.LEVEL, Math.min(10, level)), DISPLAY);
        int tier = tank.growingTier();
        state.growth = tank.growth();
        if (tier < 0) state.crystal.clear();
        else models.update(state.crystal, LogosMod.MANA_CLUSTER.get().defaultBlockState().setValue(ManaStorageTankBlock.Cluster.TIER, tier), DISPLAY);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.crystal.isEmpty()) {
            float scale = CRYSTAL * (0.3f + 0.7f * state.growth);
            poseStack.pushPose();
            poseStack.translate(0.5f, FLOOR, 0.5f);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5f, 0, -0.5f);
            state.crystal.submit(poseStack, collector, GLOW, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        if (!state.fog.isEmpty()) state.fog.submit(poseStack, collector, GLOW, OverlayTexture.NO_OVERLAY, 0);
    }
}
