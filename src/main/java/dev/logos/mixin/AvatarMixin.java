package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.runtime.Incarnation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;

/** An incarnated player has their form's size and eye height in every pose (sleeping aside), on both sides. 26.1's avatar is 1.21.1's player. */
@Mixin(Player.class)
abstract class AvatarMixin {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void logos$incarnated(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (Incarnation.formType((Player) (Object) this) instanceof net.minecraft.world.entity.EntityType<?> type) cir.setReturnValue(type.getDimensions());
    }
}
