package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.PossessionView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;

/** Possession and incarnation as the level draws them; see {@link PossessionView} and {@link dev.logos.client.IncarnationView}. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    /** The level draws whatever the local player rides even when it would cull it; not the creature they look out of. */
    @WrapOperation(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hasIndirectPassenger(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean logos$possessedUnseen(Entity entity, Entity passenger, Operation<Boolean> original) {
        return !PossessionView.lookingOutOf(entity) && original.call(entity, passenger);
    }

    /** An incarnated player is drawn as their form (see IncarnationView). */
    @WrapOperation(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
    private void logos$shell(LevelRenderer self, Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack pose,
            MultiBufferSource buffers, Operation<Void> original) {
        var shell = dev.logos.client.IncarnationView.shell(entity);
        original.call(self, shell != null ? shell : entity, camX, camY, camZ, partialTick, pose, buffers);
    }
}
