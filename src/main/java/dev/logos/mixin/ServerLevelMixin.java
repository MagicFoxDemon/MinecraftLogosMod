package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.logos.runtime.AreaWeather;
import dev.logos.runtime.TimeSkipObj;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A time-skip speeds up random ticks (and snow and ice, which run on the same count) in its own dimension only.
 * Area weather snows, freezes and fills cauldrons by its own rules inside its zones.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true)
    private int logos$timeSkipRandomTicks(int tickSpeed) {
        return TimeSkipObj.randomTickSpeed((ServerLevel) (Object) this, tickSpeed);
    }

    @Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
    private void logos$areaWeather(BlockPos pos, CallbackInfo ci) {
        if (AreaWeather.tickPrecipitation((ServerLevel) (Object) this, pos)) ci.cancel();
    }
}
