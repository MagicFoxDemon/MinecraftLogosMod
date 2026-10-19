package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 死灰块: nine caput mortuum pressed together. Dead ash holds back mana: every block in a chunk raises
 * the threshold mana must clear to spread between that chunk and its neighbours
 * ({@link dev.logos.runtime.ManaField}). Each block carries an empty {@link Marker} so its chunk can
 * count them exactly, however they came or went.
 */
public class CaputMortuumBlock extends Block implements EntityBlock {
    public static final MapCodec<CaputMortuumBlock> CODEC = simpleCodec(CaputMortuumBlock::new);

    public CaputMortuumBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<CaputMortuumBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Marker(pos, state);
    }

    /** Holds nothing: it is there to be counted among its chunk's block entities. */
    public static class Marker extends BlockEntity {
        public Marker(BlockPos pos, BlockState state) {
            super(LogosMod.CAPUT_MORTUUM_BLOCK_BE.get(), pos, state);
        }
    }
}
