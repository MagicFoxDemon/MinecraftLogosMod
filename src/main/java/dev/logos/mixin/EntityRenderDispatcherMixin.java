package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.logos.client.PossessionView;
import dev.logos.runtime.Possession;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

/**
 * A player inside a possessed creature is not drawn where they ride (nor their armour, held items
 * or name; their body stands in for them, see {@link PossessionView}), and the creature they look
 * out of is not drawn for them.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void logos$possession(E entity, Frustum culler, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (Possession.possessing(entity) || PossessionView.lookingOutOf(entity)) cir.setReturnValue(false);
    }
}
