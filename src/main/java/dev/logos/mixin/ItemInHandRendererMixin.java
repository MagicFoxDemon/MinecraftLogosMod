package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.WandTips;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** First person: reports the pose the player's own charging wand is drawn with, so its charge effects sit on the wand head. */
@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderItem", at = @At("HEAD"))
    private void logos$wandTip(LivingEntity mob, ItemStack stack, ItemDisplayContext type, PoseStack poseStack, SubmitNodeCollector collector, int light,
            CallbackInfo ci) {
        if (type.firstPerson()) WandTips.captureFirstPerson(mob, stack, type, poseStack.last().pose());
    }
}
