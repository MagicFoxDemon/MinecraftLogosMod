package dev.logos.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.logos.runtime.Familiars;
import dev.logos.runtime.FamiliarRiding;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;

/** Familiar exceptions to vanilla steering and target validation. */
@Mixin(Mob.class)
abstract class MobMixin {
    @Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
    private void logos$masterSteers(CallbackInfoReturnable<LivingEntity> cir) {
        var rider = FamiliarRiding.controller((Mob) (Object) this);
        if (rider != null) cir.setReturnValue(rider);
    }

    /** Healing is allowed even when the master cannot be attacked, including peaceful difficulty. */
    @ModifyReturnValue(method = "asValidTarget", at = @At("RETURN"))
    private @Nullable LivingEntity logos$allowHealingMaster(@Nullable LivingEntity original, @Nullable LivingEntity target) {
        Mob self = (Mob) (Object) this;
        if (original == null && self instanceof Witch && target != null) {
            var master = Familiars.masterOf(self);
            if (master != null && master.equals(target.getUUID()) && Familiars.actionTarget(self) == target) return target;
        }
        return original;
    }
}
