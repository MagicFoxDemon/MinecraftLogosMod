package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.logos.runtime.Familiars;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * Wardens never take another warden as a target. A familiar warden may, but only the one its master
 * is fighting: everything else about whom it may target (and wild wardens' truce) stays vanilla.
 */
@Mixin(Warden.class)
abstract class WardenMixin {
    @ModifyReturnValue(method = "canTargetEntity", at = @At("RETURN"))
    private boolean logos$familiarMayFightOrderedWarden(boolean allowed, Entity entity) {
        if (allowed || !(entity instanceof Warden other) || !other.isAlive() || other.level() != ((Entity) (Object) this).level()) return allowed;
        Warden self = (Warden) (Object) this;
        LivingEntity ordered = Familiars.isFamiliar(self) ? Familiars.orderedTarget(self) : null;
        return ordered == other;
    }
}
