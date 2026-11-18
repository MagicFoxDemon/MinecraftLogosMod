package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/** The sun, moon and stars fade under an area storm near the camera, and come out under a clear zone (1.21.1: LevelRenderer.renderSky). */
@Mixin(LevelRenderer.class)
abstract class SkyWeatherMixin {
    @Shadow private ClientLevel level;

    @ModifyExpressionValue(method = "renderSky", at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$areaWeather(float world) {
        return AreaWeather.skyRain(level, world);
    }
}
