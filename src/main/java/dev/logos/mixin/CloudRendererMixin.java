package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.logos.client.CloudDrift;
import net.minecraft.client.renderer.CloudRenderer;

/** Clouds drift faster while a time-skip races the dimension's clock, see {@link CloudDrift}. */
@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private long logos$cloudTime(long gameTime) {
        return CloudDrift.gameTime(gameTime);
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float logos$cloudPartialTick(float partialTicks) {
        return CloudDrift.partialTick(partialTicks);
    }
}
