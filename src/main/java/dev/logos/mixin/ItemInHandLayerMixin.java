package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.WandTips;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/** Third person: reports the pose a charging player's wand is drawn with, so its charge effects sit on the wand head. */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void logos$wandTip(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack poseStack,
            SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar) WandTips.captureThirdPerson(avatar.id, stack, item, arm == HumanoidArm.LEFT, poseStack.last().pose());
    }
}
