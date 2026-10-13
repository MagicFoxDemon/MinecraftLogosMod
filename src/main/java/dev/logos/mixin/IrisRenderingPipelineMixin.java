package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.logos.client.ShaderPackOverlay;

/**
 * With an Iris shader pack on: once the pack has composited the level, draw our projectile VFX over it with our own
 * shaders. Iris is optional; without it this mixin never applies, and if Iris renames the method the VFX just stay hidden.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline")
abstract class IrisRenderingPipelineMixin {
    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), require = 0)
    private void logos$drawVfxOverlay(CallbackInfo ci) {
        ShaderPackOverlay.draw();
    }
}
