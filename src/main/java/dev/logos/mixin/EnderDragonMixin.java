package dev.logos.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.logos.runtime.DragonFamiliar;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/**
 * A familiar dragon's wings and head sweep past its master's side instead of through them. Damage
 * alone is already turned aside; the wing gust is a push, not damage, so it is filtered here.
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
}
