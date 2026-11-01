package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.logos.client.PossessionView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

/** Possession as the level draws it; see {@link PossessionView}. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    /** The level draws whatever the local player rides even when it would cull it; not the creature they look out of. */
    @WrapOperation(method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hasIndirectPassenger(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean logos$possessedUnseen(Entity entity, Entity passenger, Operation<Boolean> original) {
        return !PossessionView.lookingOutOf(entity) && original.call(entity, passenger);
    }

    /** A possessing player's body is drawn as the player, standing where they left it. */
    @WrapOperation(method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState logos$bodyAsOwner(LevelRenderer self, Entity entity, float partialTick, Operation<EntityRenderState> original) {
        var owner = PossessionView.owner(entity);
        if (owner == null) return original.call(self, entity, partialTick);
        EntityRenderState state = original.call(self, owner, partialTick);
        PossessionView.standIn(state, (Mannequin) entity, partialTick);
        return state;
    }
}
