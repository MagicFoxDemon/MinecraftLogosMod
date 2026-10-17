package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.logos.runtime.TimeStop;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** A time-stopped player does not move at all: walking, flying, swimming and gliding all go through travel. */
@Mixin(Player.class)
abstract class PlayerMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void logos$timeStopped(Vec3 input, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (TimeStop.held(self)) {
            self.setDeltaMovement(Vec3.ZERO);
            ci.cancel();
        }
    }
}
