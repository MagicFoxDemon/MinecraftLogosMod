package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.logos.client.CloudDrift;
import net.minecraft.client.renderer.LevelRenderer;

/** Clouds drift with the day's clock (see {@link CloudDrift}). On 1.21.1 the clouds are LevelRenderer.renderClouds, timed by its tick count. */
@Mixin(LevelRenderer.class)
abstract class CloudRendererMixin {
    @ModifyExpressionValue(method = "renderClouds", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;ticks:I"))
    private int logos$cloudTime(int ticks) {
        return (int) CloudDrift.gameTime(ticks);
    }

    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float logos$cloudPartialTick(float partialTicks) {
        return CloudDrift.partialTick(partialTicks);
    }
}
