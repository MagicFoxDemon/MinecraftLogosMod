package dev.logos.block;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 法式编纂台. Right-click with a spell page to lay it on the table; sneak + right-click to take it
 * back; right-click otherwise opens the spell workbench, and saving writes into the page.
 */
public class InscriptionTableBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<InscriptionTableBlock> CODEC = simpleCodec(InscriptionTableBlock::new);

    /** Which sheet the drawing board shows; mirrors the block entity's page so the model can draw it. */
    public enum Page implements StringRepresentable {
        NONE("none"), BLANK("blank"), INSCRIBED("inscribed");

        private final String name;

        Page(String name) {
            this.name = name;
        }

        public static Page of(ItemStack stack) {
            if (stack.isEmpty()) return NONE;
            return SpellPages.isInscribed(stack) ? INSCRIBED : BLANK;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Page> PAGE = EnumProperty.create("page", Page.class);

    /**
     * Desk body plus the slanted drawing board (drawers facing north). The model overhangs the block
     * by a couple of pixels; the shape stays inside it so neighbours and pathing behave.
     */
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(0, 0, 0, 16, 11.5, 16),
            Block.box(4.75, 11.5, 1, 16, 21.5, 11)));

    public InscriptionTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PAGE, Page.NONE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PAGE);
    }

    /** The drawers face whoever placed the table. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InscriptionTableBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!SpellPages.isPage(stack) || player.isSecondaryUseActive()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(level.getBlockEntity(pos) instanceof InscriptionTableBlockEntity table) || !table.page().isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide()) {
            table.setPage(stack.copyWithCount(1));
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1, 1);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof InscriptionTableBlockEntity table)) return InteractionResult.PASS;
        if (table.page().isEmpty()) {
            if (!level.isClientSide()) {
                player.sendOverlayMessage(Component.literal("先用术式载页右键，把它放到法式编纂台上").withStyle(ChatFormatting.YELLOW));
            }
            return InteractionResult.SUCCESS;
        }
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide()) {
                ItemStack page = table.page();
                table.setPage(ItemStack.EMPTY);
                if (!player.getInventory().add(page)) player.drop(page, false);
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1, 1);
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) dev.logos.client.ClientHooks.openTableEditor(pos, table.page().copy());
        return InteractionResult.SUCCESS;
    }

    /**
     * Server side: write a spell onto the page lying on the table at {@code pos}, keeping its grade.
     * Returns that grade, or null if nothing was written.
     */
    public static @Nullable PageTier inscribe(ServerPlayer player, BlockPos pos, String source, net.minecraft.nbt.CompoundTag graph) {
        if (player.distanceToSqr(pos.getCenter()) > 64) return null;
        if (!(player.level().getBlockEntity(pos) instanceof InscriptionTableBlockEntity table) || table.page().isEmpty()) return null;
        PageTier tier = SpellPages.tier(table.page());
        table.setPage(SpellPages.write(table.page(), source, graph));
        player.level().playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1, 1.2f);
        return tier == null ? PageTier.BASIC : tier;
    }
}
