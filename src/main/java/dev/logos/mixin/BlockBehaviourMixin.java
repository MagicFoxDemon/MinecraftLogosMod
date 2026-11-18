package dev.logos.mixin;

import dev.logos.port.RemovalSideEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block entity with side effects of removal ({@link RemovalSideEffects}) has them as its block goes, as on 26.1. */
@Mixin(BlockBehaviour.class)
abstract class BlockBehaviourMixin {
    @Inject(method = "onRemove", at = @At("HEAD"))
    private void logos$removalSideEffects(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved, CallbackInfo ci) {
        if (state.hasBlockEntity() && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof RemovalSideEffects be) {
            be.preRemoveSideEffects(pos, state);
        }
    }
}
