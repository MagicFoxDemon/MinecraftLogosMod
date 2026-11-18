package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.PossessionView;
import dev.logos.client.SurfaceAuras;
import dev.logos.runtime.Possession;
import net.minecraft.client.renderer.MultiBufferSource;
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

    /** Items drawn while an entity is drawn are its own (see SurfaceAuras): held, worn, shown in a frame, lying on the ground. */
    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void logos$ownerBegin(E entity, double x, double y, double z, float yRot, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        SurfaceAuras.drawingFor(entity);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void logos$ownerEnd(E entity, double x, double y, double z, float yRot, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        SurfaceAuras.doneDrawing();
    }
}
