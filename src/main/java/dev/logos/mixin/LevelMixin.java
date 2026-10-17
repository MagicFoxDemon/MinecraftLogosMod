package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.runtime.AreaWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/** Inside an area weather zone, what falls is the zone's, whatever the world's weather (both sides). */
@Mixin(Level.class)
abstract class LevelMixin {
    @Inject(method = "precipitationAt", at = @At("HEAD"), cancellable = true)
    private void logos$areaWeather(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Biome.Precipitation p = AreaWeather.precipitationAt((Level) (Object) this, pos);
        if (p != null) cir.setReturnValue(p);
    }
}
