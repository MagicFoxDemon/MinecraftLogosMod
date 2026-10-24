package dev.logos.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.runtime.DragonFamiliar;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoverPhase;
import net.minecraft.world.phys.Vec3;

/**
 * A familiar dragon hovering with its master on its back is flown by them (see {@code DragonFamiliar}):
 * with no point to hover at, vanilla's flight step neither steers it nor pulls it back.
 */
@Mixin(DragonHoverPhase.class)
abstract class DragonHoverPhaseMixin extends AbstractDragonPhaseInstance {
    DragonHoverPhaseMixin(EnderDragon dragon) {
        super(dragon);
    }

    @Inject(method = "getFlyTargetLocation", at = @At("HEAD"), cancellable = true)
    private void logos$riderFlies(CallbackInfoReturnable<@Nullable Vec3> cir) {
        if (DragonFamiliar.flownByRider(this.dragon)) cir.setReturnValue(null);
    }
}
