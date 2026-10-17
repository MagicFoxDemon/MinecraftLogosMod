package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Rain and snow columns, splashes and rain sounds near an area weather zone. The renderer runs when a zone
 * is close; each column then draws the zone's weather inside it and the world's (if any) outside.
 */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$drawNearZones(float world, @Local(argsOnly = true) Level level) {
        return AreaWeather.renderRain(level, world);
    }

    @ModifyExpressionValue(method = "tickRainParticles", at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$splashNearZones(float world, @Local(argsOnly = true) ClientLevel level) {
        return AreaWeather.renderRain(level, world);
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void logos$zoneColumn(Level level, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Biome.Precipitation p = AreaWeather.renderedAt(level, pos);
        if (p != null) cir.setReturnValue(p);
    }
}
