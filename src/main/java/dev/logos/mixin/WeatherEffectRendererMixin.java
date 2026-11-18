package dev.logos.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Rain and snow columns, splashes and rain sounds near an area weather zone. The renderer runs when a zone
 * is close; each column then draws the zone's weather inside it and the world's (if any) outside. On 1.21.1 the
 * rain is LevelRenderer's (renderSnowAndRain, tickRain).
 */
@Mixin(LevelRenderer.class)
abstract class WeatherEffectRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    @ModifyExpressionValue(method = {"renderSnowAndRain", "tickRain"}, at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$drawNearZones(float world) {
        return minecraft.level == null ? world : AreaWeather.renderRain(minecraft.level, world);
    }

    /** A column in a zone falls even in a biome that has none of its own. */
    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;hasPrecipitation()Z"))
    private boolean logos$zoneFalls(Biome biome, Operation<Boolean> original, @Local BlockPos.MutableBlockPos pos) {
        Biome.Precipitation p = minecraft.level == null ? null : AreaWeather.renderedAt(minecraft.level, pos);
        return p != null ? p != Biome.Precipitation.NONE : original.call(biome);
    }

    @WrapOperation(method = {"renderSnowAndRain", "tickRain"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation logos$zoneColumn(Biome biome, BlockPos pos, Operation<Biome.Precipitation> original) {
        Biome.Precipitation p = minecraft.level == null ? null : AreaWeather.renderedAt(minecraft.level, pos);
        return p != null ? p : original.call(biome, pos);
    }
}
