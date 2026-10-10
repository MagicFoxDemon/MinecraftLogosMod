package dev.logos.block;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mojang.serialization.MapCodec;

import dev.logos.item.SpellPages;
import dev.logos.item.PageTier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Map;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 术式拓印台. The original (a written page or an ember) on top, empty
 * pages below, and the copy on the right. Copying is free and keeps the original; each copy uses
 * one empty page and takes that page's grade.
 */
public class CopyTableBlock extends HorizontalDirectionalBlock implements EntityBlock, BlockUIMenuType.BlockUI {
    public static final MapCodec<CopyTableBlock> CODEC = simpleCodec(CopyTableBlock::new);

    /** Visual inventory state only; the copying recipe and menu still use the original inputs. */
    public enum Sheet implements StringRepresentable {
        NONE, BASIC, CRYSTAL, ECHO, TRANSMUTED, CHALK, FIRMAMENT, SAGE, PRINCIPLE, EMBER;

        public static Sheet of(ItemStack stack) {
            if (stack.isEmpty()) return NONE;
            if (SpellPages.isEmber(stack)) return EMBER;
            PageTier tier = SpellPages.tier(stack);
            return tier == null ? NONE : valueOf(tier.name());
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final EnumProperty<Sheet> LEFT_PAGE = EnumProperty.create("left_page", Sheet.class);
    public static final EnumProperty<Sheet> RIGHT_PAGE = EnumProperty.create("right_page", Sheet.class);
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(0, 0, 0, 16, 12, 16), Block.box(.6, 12, 12.25, 15.4, 19.12, 14.7)));

    public CopyTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(LEFT_PAGE, Sheet.NONE).setValue(RIGHT_PAGE, Sheet.NONE));
    }

    @Override
    protected MapCodec<CopyTableBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT_PAGE, RIGHT_PAGE);
    }

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
        return new CopyTableBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) BlockUIMenuType.openUI(sp, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof CopyTableBlockEntity table)) {
            return ModularUI.of(UI.of(new UIElement()), player);
        }
        ItemSlot original = slot(new Slot(table.inputs(), CopyTableBlockEntity.ORIGINAL, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return CopyTableBlockEntity.isOriginal(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        ItemSlot blank = slot(new Slot(table.inputs(), CopyTableBlockEntity.BLANK, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return SpellPages.isBlank(stack);
            }
        });
        ItemSlot copy = slot(new Slot(table.resultContainer(), 0, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public void onTake(Player p, ItemStack stack) {
                table.onCopyTaken(p);
                if (!p.level().isClientSide()) {
                    p.level().playSound(null, holder.pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1, 1);
                }
                super.onTake(p, stack);
            }
        });

        UIElement inputs = new UIElement().layout(l -> l.gapAll(6));
        inputs.addChildren(labelled("原本", original), labelled("空载页", blank));
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(14).alignItems(AlignItems.CENTER));
        row.addChildren(inputs, new Label().setText(Component.literal("➜")), labelled("抄本", copy));

        Label hint = new Label();
        hint.setText(Component.literal("上方放写有术式的载页或术式余烬，下方放任意空载页。抄本沿用空载页的品级，原本保留"));
        hint.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        hint.layout(l -> l.width(9 * 19));

        UIElement root = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(7).gapAll(5));
        root.addChildren(new Label().setText(Component.translatable("block.logos.copy_table")), row, hint, new InventorySlots());
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    private static ItemSlot slot(Slot s) {
        ItemSlot slot = new ItemSlot();
        slot.bind(s);
        return slot;
    }

    private static UIElement labelled(String text, ItemSlot slot) {
        UIElement e = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
        e.addChildren(slot, new Label().setText(Component.literal(text)));
        return e;
    }
}
