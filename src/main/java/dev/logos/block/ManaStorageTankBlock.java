package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 魔力存储罐 ({@link ManaStorageTankBlockEntity}): right-click with a mana item to drop it in, otherwise to
 * open its screen. Motes drift in its glass, more the fuller it is; its fog and growing crystal are drawn
 * by {@code ManaStorageTankRenderer} from the two render-only blocks below.
 */
public class ManaStorageTankBlock extends BaseEntityBlock implements BlockUIMenuType.BlockUI {
    public static final MapCodec<ManaStorageTankBlock> CODEC = simpleCodec(ManaStorageTankBlock::new);

    public ManaStorageTankBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ManaStorageTankBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ManaStorageTankBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != LogosMod.MANA_STORAGE_TANK_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<ManaStorageTankBlockEntity>) ManaStorageTankBlockEntity::serverTick;
    }

    /** A mana item in hand goes into the input, as much as fits. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (AlchemyTableBlockEntity.fuelValue(stack) <= 0 || !(level.getBlockEntity(pos) instanceof ManaStorageTankBlockEntity tank)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide()) {
            var in = tank.input();
            var res = net.neoforged.neoforge.transfer.item.ItemResource.of(stack);
            int held = in.getAmountAsInt(0);
            if (held > 0 && !in.getResource(0).equals(res)) return InteractionResult.TRY_WITH_EMPTY_HAND;
            int add = Math.min(stack.getCount(), stack.getMaxStackSize() - held);
            if (add <= 0) return InteractionResult.TRY_WITH_EMPTY_HAND;
            in.set(0, res, held + add);
            stack.consume(add, player);
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
        return ManaStorageTankUI.create(holder);
    }

    /** Motes rise slowly in the glass, more the fuller the tank. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof ManaStorageTankBlockEntity tank)) return;
        double fill = tank.mana() / ManaStorageTankBlockEntity.MAX_MANA;
        int motes = (int) (fill * 3) + (random.nextDouble() < fill * 3 % 1 ? 1 : 0);
        for (int i = 0; i < motes; i++) {
            double x = pos.getX() + 0.15 + random.nextDouble() * 0.7, z = pos.getZ() + 0.15 + random.nextDouble() * 0.7;
            // Within the fog, rising a little, never through the lid.
            double y = pos.getY() + 0.22 + random.nextDouble() * 0.5 * fill;
            level.addParticle(LogosMod.MANA_MOTE.get(), x, y, z, 0, 0.0015 + random.nextDouble() * 0.0015, 0);
        }
    }

    /** The mana fog in a tank, never placed: its {@link #LEVEL} is how high the fog stands, in tenths of the glass. */
    public static class Fog extends Block {
        public static final MapCodec<Fog> CODEC = simpleCodec(Fog::new);
        public static final IntegerProperty LEVEL = IntegerProperty.create("level", 1, 10);

        public Fog(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(LEVEL, 1));
        }

        @Override
        protected MapCodec<Fog> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(LEVEL);
        }
    }

    /** The crystal growing on a tank's seed, never placed: {@link #TIER} 0 shard, 1 crystal, 2 gemstone. */
    public static class Cluster extends Block {
        public static final MapCodec<Cluster> CODEC = simpleCodec(Cluster::new);
        public static final IntegerProperty TIER = IntegerProperty.create("tier", 0, 2);

        public Cluster(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(TIER, 0));
        }

        @Override
        protected MapCodec<Cluster> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(TIER);
        }
    }
}
