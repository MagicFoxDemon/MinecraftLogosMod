package dev.logos.block;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import dev.logos.item.WandItem;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 炼金术桌: a table two blocks long. Like a bed, it is placed as a main half and a side half to the
 * placer's right; breaking either takes both away and drops one table. Only the main half holds the
 * table's contents ({@link AlchemyTableBlockEntity}) and draws the model; both halves answer hoppers
 * and pipes, and open the same table.
 */
public class AlchemyTableBlock extends HorizontalDirectionalBlock implements EntityBlock, BlockUIMenuType.BlockUI {
    public static final MapCodec<AlchemyTableBlock> CODEC = simpleCodec(AlchemyTableBlock::new);

    public enum Part implements StringRepresentable {
        MAIN, SIDE;

        @Override
        public String getSerializedName() {
            return this == MAIN ? "main" : "side";
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    /** One bottle of a fluid, as Create and most NeoForge mods count it. */
    public static final int BOTTLE = 250;
    /** The table top; what stands above it (the hourglass, the still) does not block movement. */
    private static final VoxelShape BODY = Block.box(0, 0, 0, 16, 12, 16);
    private static final Map<Direction, VoxelShape> OUTLINE = Shapes.rotateHorizontal(Block.box(0, 0, 0, 16, 16, 16));

    public AlchemyTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.MAIN));
    }

    @Override
    protected MapCodec<AlchemyTableBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    /** Where the other half of the table at {@code pos} is: the side half lies to the main half's left as one faces its front. */
    public static BlockPos otherHalf(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return pos.relative(state.getValue(PART) == Part.MAIN ? facing.getCounterClockWise() : facing.getClockWise());
    }

    public static BlockPos mainHalf(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.MAIN ? pos : otherHalf(pos, state);
    }

    /** The table that the half at {@code pos} belongs to, if it is loaded. */
    public static @Nullable AlchemyTableBlockEntity table(Level level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof AlchemyTableBlock && level.getBlockEntity(mainHalf(pos, state)) instanceof AlchemyTableBlockEntity t ? t : null;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos side = context.getClickedPos().relative(facing.getCounterClockWise());
        Level level = context.getLevel();
        return level.getBlockState(side).canBeReplaced(context) && level.getWorldBorder().isWithinBounds(side)
                ? defaultBlockState().setValue(FACING, facing) : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level.isClientSide()) return;
        level.setBlock(otherHalf(pos, state), state.setValue(PART, Part.SIDE), Block.UPDATE_ALL);
        level.updateNeighborsAt(pos, Blocks.AIR);
        state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
    }

    /** A half whose partner is gone goes too (dropping the table if it was the main half). */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction toNeighbour,
            BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        if (!neighbourPos.equals(otherHalf(pos, state))) return super.updateShape(state, level, ticks, pos, toNeighbour, neighbourPos, neighbour, random);
        return neighbour.is(this) && neighbour.getValue(PART) != state.getValue(PART) && neighbour.getValue(FACING) == state.getValue(FACING)
                ? state : Blocks.AIR.defaultBlockState();
    }

    /** In creative, breaking the side half takes the main half without its drop (beds do the same). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops() && state.getValue(PART) == Part.SIDE) {
            BlockPos main = otherHalf(pos, state);
            BlockState mainState = level.getBlockState(main);
            if (mainState.is(this) && mainState.getValue(PART) == Part.MAIN) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, main, Block.getId(mainState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BODY;
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    /** Drawn by its renderer, from the main half. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.MAIN ? new AlchemyTableBlockEntity(pos, state) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != LogosMod.ALCHEMY_TABLE_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<AlchemyTableBlockEntity>) AlchemyTableBlockEntity::serverTick;
    }

    /**
     * With a wand: hand the table some of one's own mana. With a bucket or other fluid container:
     * fill or drain the tank. With a water bottle or an empty bottle: pour or draw one bottle (250 mB).
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        AlchemyTableBlockEntity table = table(level, pos, state);
        if (table == null) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (stack.getItem() instanceof WandItem) {
            if (!level.isClientSide()) charge(table, player);
            return InteractionResult.SUCCESS;
        }
        if (stack.is(Items.POTION) && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER)) {
            if (!level.isClientSide() && pour(table, FluidResource.of(Fluids.WATER), true)) {
                player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
                level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1, 1);
            }
            return InteractionResult.SUCCESS;
        }
        if (stack.is(Items.GLASS_BOTTLE)) {
            if (!level.isClientSide() && pour(table, FluidResource.of(Fluids.WATER), false)) {
                player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, PotionContents.createItemStack(Items.POTION, Potions.WATER)));
                level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1, 1);
            }
            return InteractionResult.SUCCESS;
        }
        if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hit.getDirection(), null)) return InteractionResult.SUCCESS;
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    /** One bottle of {@code fluid} into the tank ({@code in}) or out of it; all or nothing. */
    private static boolean pour(AlchemyTableBlockEntity table, FluidResource fluid, boolean in) {
        try (Transaction tx = Transaction.openRoot()) {
            int moved = in ? table.tank().insert(fluid, BOTTLE, tx) : table.tank().extract(fluid, BOTTLE, tx);
            if (moved != BOTTLE) return false;
            tx.commit();
            return true;
        }
    }

    private static void charge(AlchemyTableBlockEntity table, Player player) {
        PlayerMagic m = PlayerMagic.of(player);
        double given = table.receiveMana(Math.min(AlchemyTableBlockEntity.WAND_CHARGE, Math.floor(m.mana)));
        m.mana -= given;
        player.sendOverlayMessage(Component.translatable("logos.alchemy_table.charged", (int) given, (int) table.mana(), AlchemyTableBlockEntity.MAX_MANA)
                .withStyle(ChatFormatting.AQUA));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) BlockUIMenuType.openUI(sp, mainHalf(pos, state));
        return InteractionResult.SUCCESS;
    }

    /** The storage, mana and tank, and the canvas of process cards: see {@link AlchemyTableUI}. */
    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return AlchemyTableUI.create(holder);
    }
}
