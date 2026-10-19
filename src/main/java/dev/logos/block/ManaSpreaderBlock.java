package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaField;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 魔力散布器: puts mana into the air around it (see {@link ManaSpreaderBlockEntity}). Fuel goes in its
 * drawer by hand or by hopper; with an empty hand it opens its screen: the drawer, what it holds, and a
 * map of the field in the chunks around it. It glows while it has mana to give or its chunk holds some.
 */
public class ManaSpreaderBlock extends HorizontalDirectionalBlock implements EntityBlock, BlockUIMenuType.BlockUI {
    public static final MapCodec<ManaSpreaderBlock> CODEC = simpleCodec(ManaSpreaderBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public ManaSpreaderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<ManaSpreaderBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    /** Its drawer faces whoever placed it. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Lights or darkens a spreader or a collector (both have {@link #ACTIVE}) to match {@code on}. */
    static void light(Level level, BlockPos pos, BlockState state, boolean on) {
        if (state.getValue(ACTIVE) != on) level.setBlock(pos, state.setValue(ACTIVE, on), Block.UPDATE_CLIENTS);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ManaSpreaderBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != LogosMod.MANA_SPREADER_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<ManaSpreaderBlockEntity>) ManaSpreaderBlockEntity::serverTick;
    }

    /** Mana fuel in hand goes into the drawer, as much as fits. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (AlchemyTableBlockEntity.fuelValue(stack) <= 0 || !(level.getBlockEntity(pos) instanceof ManaSpreaderBlockEntity spreader)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide()) {
            try (Transaction tx = Transaction.openRoot()) {
                int in = spreader.fuel().insert(ItemResource.of(stack), stack.getCount(), tx);
                tx.commit();
                if (!player.getAbilities().instabuild) stack.shrink(in);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) BlockUIMenuType.openUI(sp, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return ManaDeviceUI.spreader(holder);
    }

    /** While lit, a few motes rise slowly from the gap under its lid. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE) || random.nextInt(3) != 0) return;
        level.addParticle(LogosMod.MANA_MOTE.get(), pos.getX() + 0.2 + random.nextDouble() * 0.6, pos.getY() + 0.8,
                pos.getZ() + 0.2 + random.nextDouble() * 0.6, 0, 0.015 + random.nextDouble() * 0.01, 0);
    }
}
