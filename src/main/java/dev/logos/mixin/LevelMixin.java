package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.runtime.AreaWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Inside an area weather zone, what falls is the zone's, whatever the world's weather (both sides). 26.1 asks
 * {@code precipitationAt}; 1.21.1 asks whether it rains at a place, which is what fire, cauldrons and the rest go by.
 */
@Mixin(Level.class)
abstract class LevelMixin {
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void logos$areaWeather(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        Biome.Precipitation p = AreaWeather.precipitationAt(level, pos);
        if (p != null) cir.setReturnValue(p == Biome.Precipitation.RAIN && level.canSeeSky(pos));
    }
}
