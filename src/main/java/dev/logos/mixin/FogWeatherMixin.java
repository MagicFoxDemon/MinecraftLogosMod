package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;

/** Fog darkens under an area storm near the camera, and lifts under a clear zone (1.21.1: FogRenderer.setupColor). */
@Mixin(FogRenderer.class)
abstract class FogWeatherMixin {
    @ModifyExpressionValue(method = "setupColor", at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private static float logos$areaRain(float world, @Local(argsOnly = true) ClientLevel level) {
        return AreaWeather.skyRain(level, world);
    }

    @ModifyExpressionValue(method = "setupColor", at = @At(value = "INVOKE", target = "getThunderLevel(F)F"))
    private static float logos$areaThunder(float world, @Local(argsOnly = true) ClientLevel level) {
        return AreaWeather.skyThunder(level, world);
    }
}
