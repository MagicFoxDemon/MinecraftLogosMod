package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 培养罐: a glass jar of soul humus a seed grows in, at random ticks (so the time-skip rune hurries it
 * too) and in any light. Right-click with a seed to plant it, with a bottled medium to pour it, and the
 * ripe crop to harvest it; otherwise right-click (or sneak) opens its screen ({@link CultivationJarUI}).
 */
public class CultivationJarBlock extends BaseEntityBlock implements BonemealableBlock, BlockUIMenuType.BlockUI {
    public static final MapCodec<CultivationJarBlock> CODEC = simpleCodec(CultivationJarBlock::new);
    /** Whether a medium lies on the soil: its layer is drawn, tinted by the medium's colour. */
    public static final BooleanProperty MEDIUM = BooleanProperty.create("medium");
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 16, 13);

    public CultivationJarBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(MEDIUM, false));
    }

    @Override
    protected MapCodec<CultivationJarBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MEDIUM);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CultivationJarBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        // Bone meal is the item's own use.
        if (stack.is(Items.BONE_MEAL)) return InteractionResult.PASS;
        // The client swings in useWithoutItem; the server decides what the hand does.
        if (level.isClientSide()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (jar.seed().isEmpty() && jar.plant(stack)) {
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1, 1);
            return InteractionResult.SUCCESS;
        }
        if (jar.pour(stack, player)) {
            level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1, 1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar)) return InteractionResult.PASS;
        if (jar.isRipe() && !player.isSecondaryUseActive()) {
            if (level instanceof ServerLevel server) {
                jar.harvest(server).forEach(drop -> Block.popResource(level, pos.above(), drop));
                level.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1, 1);
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp) BlockUIMenuType.openUI(sp, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return CultivationJarUI.create(holder);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar) jar.randomTick(level, random);
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar && !jar.isEmpty() && !jar.isRipe();
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar && jar.canGrow();
    }

    /** A stage, paying its mana like any other. */
    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar) jar.grow();
    }
}
