package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 术式发射器: holds one spell page and casts it from its own mana (see {@link SpellEmitterBlockEntity}).
 * It faces any of six ways, away from whoever placed it, like a dispenser; that is where its spells
 * aim unless they say otherwise. Right-click with a page to put it in, sneak + right-click to take it
 * out; right-click otherwise opens the workbench on the page, where the emitter's own settings sit
 * in place of the invocation node. Only whoever placed it may change it: it casts in their name.
 */
public class SpellEmitterBlock extends DirectionalBlock implements EntityBlock {
    public static final MapCodec<SpellEmitterBlock> CODEC = simpleCodec(SpellEmitterBlock::new);

    public SpellEmitterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ManaSpreaderBlock.ACTIVE, false)
                .setValue(BlockStateProperties.TRIGGERED, false));
    }

    @Override
    protected MapCodec<SpellEmitterBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ManaSpreaderBlock.ACTIVE, BlockStateProperties.TRIGGERED);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (by instanceof Player player && level.getBlockEntity(pos) instanceof SpellEmitterBlockEntity emitter) emitter.setOwner(player);
    }

    /** Solid to everything but its own spells, which start inside it and must get out. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext c && c.getEntity() instanceof SpellEmitterBlockEntity.Shadow s && s.home().equals(pos)) {
            return Shapes.empty();
        }
        return super.getCollisionShape(state, level, pos, context);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpellEmitterBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != LogosMod.SPELL_EMITTER_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<SpellEmitterBlockEntity>) SpellEmitterBlockEntity::serverTick;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean moved) {
        boolean powered = level.hasNeighborSignal(pos);
        if (powered == state.getValue(BlockStateProperties.TRIGGERED)) return;
        level.setBlock(pos, state.setValue(BlockStateProperties.TRIGGERED, powered), Block.UPDATE_CLIENTS);
        if (powered && level.getBlockEntity(pos) instanceof SpellEmitterBlockEntity emitter) emitter.pulse();
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** How full its store of mana is. */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        if (!(level.getBlockEntity(pos) instanceof SpellEmitterBlockEntity emitter) || emitter.mana() <= 0) return 0;
        return 1 + (int) Math.floor(emitter.mana() / SpellEmitterBlockEntity.MAX_MANA * 14);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!SpellPages.isPage(stack) || player.isSecondaryUseActive()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(level.getBlockEntity(pos) instanceof SpellEmitterBlockEntity emitter) || !emitter.page().isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!level.isClientSide()) {
            if (!emitter.mayChange(player)) return InteractionResult.SUCCESS;
            var spell = SpellPages.spell(stack);
            if (spell != null && spell.isPassive()) {
                player.sendOverlayMessage(Component.literal("《" + spell.name() + "》是被动术式：术式发射器只施放主动术式").withStyle(ChatFormatting.YELLOW));
                return InteractionResult.SUCCESS;
            }
            emitter.setPage(stack.copyWithCount(1), player);
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1, 1);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof SpellEmitterBlockEntity emitter)) return InteractionResult.PASS;
        if (emitter.page().isEmpty()) {
            if (level instanceof ServerLevel) {
                player.sendOverlayMessage(Component.literal("先用术式载页右键，把它放进术式发射器 · 魔力 " + (int) emitter.mana() + "/"
                        + (int) SpellEmitterBlockEntity.MAX_MANA).withStyle(ChatFormatting.YELLOW));
            }
            return InteractionResult.SUCCESS;
        }
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide() && emitter.mayChange(player)) {
                ItemStack page = emitter.page();
                emitter.setPage(ItemStack.EMPTY, player);
                if (!player.getInventory().add(page)) player.drop(page, false);
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1, 1);
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) {
            if (emitter.owner() == null || emitter.owner().equals(player.getUUID())) dev.logos.client.ClientHooks.openEmitterEditor(pos, emitter);
        } else {
            emitter.mayChange(player);
        }
        return InteractionResult.SUCCESS;
    }
}
