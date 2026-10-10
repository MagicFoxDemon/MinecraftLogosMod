package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dev.logos.runtime.Constructs;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/**
 * Standing on a construct is standing on something: without this, a player on a platform high
 * in the air would be kicked for flying, since the server only looks for blocks under their feet.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @ModifyReturnValue(method = "noBlocksAround", at = @At("RETURN"))
    private boolean logos$constructsCountAsGround(boolean none, Entity entity) {
        return none && !Constructs.supports(entity);
    }
}
