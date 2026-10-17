package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;

/** Fog darkens and closes in under an area storm near the camera, and lifts under a clear zone. */
@Mixin(AtmosphericFogEnvironment.class)
abstract class FogWeatherMixin {
    @ModifyExpressionValue(method = {"getBaseColor", "updateRainFogState"}, at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$areaRain(float world, @Local(argsOnly = true) ClientLevel level) {
        return AreaWeather.skyRain(level, world);
    }

    @ModifyExpressionValue(method = "getBaseColor", at = @At(value = "INVOKE", target = "getThunderLevel(F)F"))
    private float logos$areaThunder(float world, @Local(argsOnly = true) ClientLevel level) {
        return AreaWeather.skyThunder(level, world);
    }
}
