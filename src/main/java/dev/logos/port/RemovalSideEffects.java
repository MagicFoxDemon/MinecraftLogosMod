package dev.logos.port;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stands in for 26.1's {@code BlockEntity#preRemoveSideEffects}: a block entity that does something (drops what it
 * holds) as its block is removed, before the block entity goes. {@code dev.logos.mixin.BlockBehaviourMixin} calls it.
 */
public interface RemovalSideEffects {
    void preRemoveSideEffects(BlockPos pos, BlockState state);
}
