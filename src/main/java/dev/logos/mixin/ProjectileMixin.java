package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.runtime.DragonFamiliar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * A familiar dragon's breath leaves through its own head. Vanilla spares only the shooter's fellow
 * passengers, not the dragon's parts, so its fireballs burst on its own head and neck mid-air.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void logos$dragonSparesItself(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (DragonFamiliar.ownBody((Projectile) (Object) this, entity)) cir.setReturnValue(false);
    }
}
