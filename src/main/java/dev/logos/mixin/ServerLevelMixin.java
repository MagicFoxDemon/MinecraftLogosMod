package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.logos.runtime.TimeSkipObj;
import net.minecraft.server.level.ServerLevel;

/** A time-skip speeds up random ticks (and snow and ice, which run on the same count) in its own dimension only. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true)
    private int logos$timeSkipRandomTicks(int tickSpeed) {
        return TimeSkipObj.randomTickSpeed((ServerLevel) (Object) this, tickSpeed);
    }
}
