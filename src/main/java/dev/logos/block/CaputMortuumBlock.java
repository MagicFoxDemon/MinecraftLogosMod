package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 死灰块: nine caput mortuum pressed together. Dead ash holds back mana: every block in a chunk raises
 * the threshold mana must clear to spread between that chunk and its neighbours
 * ({@link dev.logos.runtime.ManaField}). Each block carries an empty {@link Marker} so its chunk can
 * count them exactly, however they came or went.
 */
public class CaputMortuumBlock extends FallingBlock implements EntityBlock {
    public static final MapCodec<CaputMortuumBlock> CODEC = simpleCodec(CaputMortuumBlock::new);

    public CaputMortuumBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<CaputMortuumBlock> codec() {
        return CODEC;
    }

    @Override
    public int getDustColor(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getMapColor(level, pos).col;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Marker(pos, state);
    }

    /**
     * Holds nothing: it counts its block in its chunk ({@link ManaField#insulation}). A chunk calls these two for
     * every block entity it gains or loses, however the block came or went and when the chunk loads or unloads.
     */
    public static class Marker extends BlockEntity {
        /** Whether the count includes it: it can be told it was removed more than once. */
        private boolean counted;

        public Marker(BlockPos pos, BlockState state) {
            super(LogosMod.CAPUT_MORTUUM_BLOCK_BE.get(), pos, state);
        }

        @Override
        public void clearRemoved() {
            super.clearRemoved();
            count(true);
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            count(false);
        }

        private void count(boolean in) {
            if (counted != in && level instanceof ServerLevel server) {
                counted = in;
                ManaField.countMarker(server, worldPosition, in ? 1 : -1);
            }
        }
    }
}
