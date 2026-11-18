package dev.logos.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.SurfaceAuras;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Every item drawn (in hand, on the ground, in a frame) goes through here: what clings to an item wearing a surface
 * aura or a mana wrap is drawn on it (see {@link SurfaceAuras}). Stands in for 26.1's ItemModelResolver and
 * ItemStackRenderState hooks.
 */
@Mixin(ItemRenderer.class)
abstract class ItemRendererMixin {
    /** The model is drawn, the pose still in its space. */
    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"))
    private void logos$aura(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack, MultiBufferSource buffers,
            int light, int overlay, BakedModel model, CallbackInfo ci) {
        SurfaceAuras.renderedItem(stack, context, model, poseStack, buffers);
    }

    /** A held item (first person included): it belongs to who holds it. */
    @Inject(method = "renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V",
            at = @At("HEAD"))
    private void logos$holderBegin(@Nullable LivingEntity holder, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
            MultiBufferSource buffers, @Nullable Level level, int light, int overlay, int seed, CallbackInfo ci) {
        SurfaceAuras.drawingFor(holder);
    }

    @Inject(method = "renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V",
            at = @At("RETURN"))
    private void logos$holderEnd(@Nullable LivingEntity holder, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
            MultiBufferSource buffers, @Nullable Level level, int light, int overlay, int seed, CallbackInfo ci) {
        SurfaceAuras.doneDrawing();
    }
}
