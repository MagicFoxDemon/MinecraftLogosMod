package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaField;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 魔力收集器: draws mana from the air and passes it to the block it faces (see
 * {@link ManaCollectorBlockEntity}). It faces any of six ways; placed against a block, it faces that
 * block. With an empty hand it says what it holds and what the air of its chunk gives.
 */
public class ManaCollectorBlock extends DirectionalBlock implements EntityBlock {
    public static final MapCodec<ManaCollectorBlock> CODEC = simpleCodec(ManaCollectorBlock::new);
    /** Ticks a mote takes to drift in to it. */
    public static final int DRAWN_MOTE_LIFE = 40;

    public ManaCollectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ManaSpreaderBlock.ACTIVE, false));
    }

    @Override
    protected MapCodec<ManaCollectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ManaSpreaderBlock.ACTIVE);
    }

    /** Placed against a block, it faces that block, ready to feed it. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ManaCollectorBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != LogosMod.MANA_COLLECTOR_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<ManaCollectorBlockEntity>) ManaCollectorBlockEntity::serverTick;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof ManaCollectorBlockEntity collector) {
            player.sendOverlayMessage(Component.translatable("logos.mana_collector.status", (int) collector.mana(), (int) ManaCollectorBlockEntity.MAX_MANA,
                    String.format("%.2f", ManaField.ambientRate(server, pos)), (int) Math.round(ManaField.spread(server, pos))).withStyle(ChatFormatting.AQUA));
        }
        return InteractionResult.SUCCESS;
    }

    /** Now and then a mote drifts in to it: more often the more mana its chunk's air holds. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        int air = ManaField.clientLevel(level, pos) + (state.getValue(ManaSpreaderBlock.ACTIVE) ? 1 : 0);
        if (random.nextInt(ManaField.LEVELS + 2) >= air) return;
        Vec3 core = Vec3.atCenterOf(pos);
        Vec3 from = core.add((random.nextDouble() - 0.5) * 3, (random.nextDouble() - 0.5) * 2, (random.nextDouble() - 0.5) * 3);
        Vec3 v = core.subtract(from).scale(1.0 / DRAWN_MOTE_LIFE);
        level.addParticle(LogosMod.MANA_MOTE.get(), from.x, from.y, from.z, v.x, v.y, v.z);
    }
}
