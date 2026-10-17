package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;

/** The sun, moon and stars fade under an area storm near the camera, and come out under a clear zone. */
@Mixin(SkyRenderer.class)
abstract class SkyWeatherMixin {
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$areaWeather(float world, @Local(argsOnly = true) ClientLevel level) {
        return AreaWeather.skyRain(level, world);
    }
}
