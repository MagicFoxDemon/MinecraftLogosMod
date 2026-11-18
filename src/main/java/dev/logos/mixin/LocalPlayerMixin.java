package dev.logos.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While charging a wand the player walks and sprints at full speed, as 26.1 gives a wand through its use-effects
 * component: here using a wand does not count as using an item for movement.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @WrapOperation(method = {"aiStep", "canStartSprinting"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean logos$wandKeepsPace(LocalPlayer self, Operation<Boolean> original) {
        return original.call(self) && !dev.logos.runtime.Charging.keepsPace(self.getUseItem());
    }
}
