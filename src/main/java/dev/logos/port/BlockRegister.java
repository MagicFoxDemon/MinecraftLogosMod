package dev.logos.port;

import java.util.function.Function;
import java.util.function.UnaryOperator;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1's block register with 26.1's way of giving properties: a function that adjusts fresh ones, rather than the
 * properties themselves.
 */
public final class BlockRegister extends DeferredRegister.Blocks {
    public BlockRegister(String namespace) {
        super(namespace);
    }

    public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> func, UnaryOperator<BlockBehaviour.Properties> props) {
        return registerBlock(name, func, props.apply(BlockBehaviour.Properties.of()));
    }

    public DeferredBlock<Block> registerSimpleBlock(String name, UnaryOperator<BlockBehaviour.Properties> props) {
        return registerSimpleBlock(name, props.apply(BlockBehaviour.Properties.of()));
    }
}
