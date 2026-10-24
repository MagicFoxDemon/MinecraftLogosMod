package dev.logos.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.logos.runtime.DragonFamiliar;
import dev.logos.runtime.Familiars;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/**
 * A familiar dragon's wings and head sweep past its master's side instead of through them. Damage
 * alone is already turned aside; the wing gust is a push, not damage, so it is filtered here.
 * <p>
 * And its flight nodes ring its fight origin: vanilla builds them around the world's 0,0 (where the
 * End's portal stands) whatever the origin, so a familiar would fly off there however it was recentred.
 */
@Mixin(EnderDragon.class)
abstract class EnderDragonMixin {
    @Inject(method = "knockBack", at = @At("HEAD"))
    private void logos$spareMasterFromWings(ServerLevel level, List<Entity> entities, CallbackInfo ci) {
        DragonFamiliar.spare((EnderDragon) (Object) this, entities);
    }

    @Inject(method = "hurt(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V", at = @At("HEAD"))
    private void logos$spareMasterFromHead(ServerLevel level, List<Entity> entities, CallbackInfo ci) {
        DragonFamiliar.spare((EnderDragon) (Object) this, entities);
    }

    // The ground under each node is looked up where the node will stand, and the node is put there.
    @ModifyArg(method = "findClosestNode()I", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;<init>(III)V"), index = 0)
    private int logos$groundXAroundOrigin(int x) {
        return x + logos$originX();
    }

    @ModifyArg(method = "findClosestNode()I", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;<init>(III)V"), index = 2)
    private int logos$groundZAroundOrigin(int z) {
        return z + logos$originZ();
    }

    @ModifyArg(method = "findClosestNode()I", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/Node;<init>(III)V"), index = 0)
    private int logos$nodeXAroundOrigin(int x) {
        return x + logos$originX();
    }

    @ModifyArg(method = "findClosestNode()I", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/Node;<init>(III)V"), index = 2)
    private int logos$nodeZAroundOrigin(int z) {
        return z + logos$originZ();
    }

    /** The End's own dragon keeps vanilla's rings. */
    private int logos$originX() {
        EnderDragon d = (EnderDragon) (Object) this;
        return Familiars.isFamiliar(d) ? d.getFightOrigin().getX() : 0;
    }

    private int logos$originZ() {
        EnderDragon d = (EnderDragon) (Object) this;
        return Familiars.isFamiliar(d) ? d.getFightOrigin().getZ() : 0;
    }
}
