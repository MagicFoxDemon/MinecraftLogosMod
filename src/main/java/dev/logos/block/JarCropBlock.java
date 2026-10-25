package dev.logos.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A plant that grows only in a cultivation jar (niter sprouts, the metal plants, mandrake…), in four
 * stages like beetroot. It is never placed in the world: the jar draws it at its age and harvests it
 * by its {@link dev.logos.alchemy.CultivationRecipe}, so it has no item and no loot of its own.
 */
public class JarCropBlock extends CropBlock {
    public static final MapCodec<JarCropBlock> CODEC = simpleCodec(JarCropBlock::new);
    public static final IntegerProperty AGE = BlockStateProperties.AGE_3;

    public JarCropBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<JarCropBlock> codec() {
        return CODEC;
    }

    @Override
    protected IntegerProperty getAgeProperty() {
        return AGE;
    }

    @Override
    public int getMaxAge() {
        return 3;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }
}
