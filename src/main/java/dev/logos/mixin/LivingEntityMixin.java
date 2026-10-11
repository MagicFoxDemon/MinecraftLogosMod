package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.logos.runtime.FamiliarRiding;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * How a ridden familiar moves: by its rider's keys (like a horse), at its own walking speed,
 * facing where its rider looks, and stepping up whole blocks so it does not stall at every ledge.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    private boolean logos$riddenBy(Player controller) {
        return FamiliarRiding.controller((LivingEntity) (Object) this) == controller;
    }

    @Inject(method = "getRiddenInput", at = @At("HEAD"), cancellable = true)
    private void logos$riddenInput(Player controller, Vec3 selfInput, CallbackInfoReturnable<Vec3> cir) {
        if (logos$riddenBy(controller)) cir.setReturnValue(FamiliarRiding.riddenInput((LivingEntity) (Object) this, controller));
    }

    @Inject(method = "getRiddenSpeed", at = @At("HEAD"), cancellable = true)
    private void logos$riddenSpeed(Player controller, CallbackInfoReturnable<Float> cir) {
        if (logos$riddenBy(controller)) cir.setReturnValue(FamiliarRiding.riddenSpeed((LivingEntity) (Object) this));
    }

    @Inject(method = "tickRidden", at = @At("HEAD"))
    private void logos$tickRidden(Player controller, Vec3 riddenInput, CallbackInfo ci) {
        if (logos$riddenBy(controller)) FamiliarRiding.tickRidden((LivingEntity) (Object) this, controller);
    }

    @ModifyReturnValue(method = "maxUpStep", at = @At("RETURN"))
    private float logos$ridersStepUp(float step) {
        return FamiliarRiding.controller((LivingEntity) (Object) this) != null ? Math.max(step, 1.0f) : step;
    }
}
