package dev.logos.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.logos.runtime.Constructs;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Lets constructs collide: wherever an entity's movement gathers the entity colliders around it,
 * the cells of nearby constructs that are solid for that entity are added. Runs on both sides,
 * so a player's own client stops them at a wall exactly where the server does.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
    /** A rideable familiar takes players only, as many as it has seats. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "canAddPassenger", at = @At("HEAD"), cancellable = true)
    private void logos$familiarSeats(Entity passenger, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (dev.logos.runtime.FamiliarRiding.rideable(self)) {
            cir.setReturnValue(passenger instanceof net.minecraft.world.entity.player.Player
                    && self.getPassengers().size() < dev.logos.runtime.FamiliarRiding.seats(self));
        }
    }

    /** A familiar dragon's riders sit on its back, one behind the other. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"), cancellable = true)
    private void logos$dragonSeats(Entity passenger, Entity.MoveFunction move, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ((Object) this instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon && dev.logos.runtime.Familiars.isFamiliar(dragon)) {
            int seat = Math.max(0, dragon.getPassengers().indexOf(passenger));
            Vec3 at = dev.logos.runtime.FamiliarRiding.dragonSeat(dragon, seat);
            move.accept(passenger, at.x, at.y, at.z);
            ci.cancel();
        }
    }

    private static final String GET_ENTITY_COLLISIONS =
            "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;";

    @WrapOperation(method = "collide", at = @At(value = "INVOKE", target = GET_ENTITY_COLLISIONS))
    private List<VoxelShape> logos$constructsOnMove(Level level, Entity source, AABB area, Operation<List<VoxelShape>> original) {
        return Constructs.withCollisions(original.call(level, source, area), level, source, area);
    }

    @WrapOperation(method = "collectAllColliders", at = @At(value = "INVOKE", target = GET_ENTITY_COLLISIONS))
    private static List<VoxelShape> logos$constructsOnCollect(Level level, Entity source, AABB area, Operation<List<VoxelShape>> original) {
        return Constructs.withCollisions(original.call(level, source, area), level, source, area);
    }
}
