package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.WandTips;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** First person: reports the pose the player's own wand is drawn with, so charges, beams and rays leave from its head. */
@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void logos$handsBegin(float frameInterp, PoseStack poseStack, MultiBufferSource.BufferSource buffers, LocalPlayer player, int light,
            CallbackInfo ci) {
        WandTips.beginHands(poseStack.last().pose());
    }

    @Inject(method = "renderItem", at = @At("HEAD"))
    private void logos$wandTip(LivingEntity mob, ItemStack stack, ItemDisplayContext type, boolean leftHand, PoseStack poseStack,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (type.firstPerson()) WandTips.captureFirstPerson(mob, stack, type, poseStack.last().pose());
    }
}
