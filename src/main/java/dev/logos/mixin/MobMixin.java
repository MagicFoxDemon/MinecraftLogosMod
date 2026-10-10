package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.runtime.FamiliarRiding;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** A rideable familiar is steered by its master on its back, as a horse is by its rider. */
@Mixin(Mob.class)
abstract class MobMixin {
    @Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
    private void logos$masterSteers(CallbackInfoReturnable<LivingEntity> cir) {
        var rider = FamiliarRiding.controller((Mob) (Object) this);
        if (rider != null) cir.setReturnValue(rider);
    }
}
