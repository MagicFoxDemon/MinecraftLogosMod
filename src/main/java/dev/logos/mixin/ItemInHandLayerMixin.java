package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.CastPoses;
import dev.logos.client.WandTips;
import dev.logos.client.port.HumanoidRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Third person: reports the pose a player's wand is drawn with, so charges, beams and rays leave from its head. */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void logos$wandTip(LivingEntity entity, ItemStack stack, ItemDisplayContext context, HumanoidArm arm, PoseStack poseStack,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        // A held cast pose may turn the item in the hand first, so the head is reported where it is drawn.
        if (((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof HumanoidModel<?> model) {
            CastPoses.tiltItem(HumanoidRenderState.of(entity, model), poseStack, arm, stack);
        }
        if (entity instanceof Player || entity instanceof dev.logos.runtime.PossessionBody) {
            WandTips.captureThirdPerson(entity, stack, context, arm == HumanoidArm.LEFT, arm == entity.getMainArm(), poseStack.last().pose());
        }
    }
}
