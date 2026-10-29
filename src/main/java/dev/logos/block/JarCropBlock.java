package dev.logos.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A plant grown in a cultivation jar (niter sprouts, the metal plants, mandrake…), in four stages
 * like beetroot. The jar draws it at its age and harvests it by its
 * {@link dev.logos.alchemy.CultivationRecipe}: element herbs use their block loot, other crops
 * specify a fixed harvest. It has no block item; seeds are registered separately.
 * Ground substrates are opt-in through {@link #plantableOn()}, empty by default; add-ons supply
 * placement and world generation when enabling ground cultivation.
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

    /** Ground substrates for this crop: {@code <namespace>:plantable_on/<crop block path>}. */
    public TagKey<Block> plantableOn() {
        return TagKey.create(Registries.BLOCK, BuiltInRegistries.BLOCK.getKey(this).withPrefix("plantable_on/"));
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(plantableOn());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }
}
