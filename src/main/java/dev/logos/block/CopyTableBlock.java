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
import dev.vfyjxf.taffy.style.AlignContent;
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

        // Drawn like the table: the original's page and the empty paper in iron trays on its top,
        // the copy on the dark banner between them, a golden thread joining them while a copy is ready.
        original.style(s -> s.background(dev.logos.client.CopyTableArt.socket(true)));
        blank.style(s -> s.background(dev.logos.client.CopyTableArt.socket(true)));
        copy.style(s -> s.background(dev.logos.client.CopyTableArt.socket(false)));
        UIElement originalTray = tray(original, dev.logos.client.CopyTableArt.originalTray(() -> original.getSlot().hasItem()));
        UIElement blankTray = tray(blank, dev.logos.client.CopyTableArt.blankTray(() -> blank.getSlot().hasItem()));
        UIElement banner = new UIElement().layout(l -> l.width(26).height(TRAY_H - 14).alignItems(AlignItems.CENTER).paddingTop(8));
        banner.style(s -> s.background(dev.logos.client.CopyTableArt.banner()));
        banner.addChild(copy);
        UIElement middle = new UIElement().layout(l -> l.alignItems(AlignItems.CENTER).justifyContent(AlignContent.FLEX_END).height(TRAY_H));
        middle.addChild(banner);

        UIElement trays = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).justifyContent(AlignContent.SPACE_BETWEEN)
                .alignItems(AlignItems.CENTER).paddingAll(5));
        trays.addChildren(originalTray, middle, blankTray);
        UIElement captions = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).justifyContent(AlignContent.SPACE_BETWEEN)
                .paddingHorizontal(5).paddingBottom(4));
        captions.addChildren(caption("logos.copy_table.original", TRAY_W), caption("logos.copy_table.copy", 40),
                caption("logos.copy_table.blank", TRAY_W));
        UIElement desk = new UIElement().layout(l -> l.width(WIDTH));
        desk.style(s -> s.background(dev.logos.client.CopyTableArt.top()));
        desk.addChildren(trays, captions);
        trays.style(s -> s.overlay(dev.logos.client.CopyTableArt.thread(originalTray, blankTray, copy, () -> copy.getSlot().hasItem())));

        Label hint = new Label();
        hint.setText(Component.translatable("logos.copy_table.hint"));
        hint.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        hint.layout(l -> l.width(9 * 18));
        UIElement page = new UIElement().layout(l -> l.width(WIDTH).paddingAll(5).paddingTop(7).gapAll(5).alignItems(AlignItems.CENTER));
        page.style(s -> s.background(dev.logos.client.CopyTableArt.ledger()));
        dev.logos.client.CopyTableArt.inked(page);
        InventorySlots inventory = new InventorySlots();
        paperSlots(inventory);
        page.addChildren(hint, inventory);

        Label title = new Label();
        title.setText(Component.translatable("block.logos.copy_table"));
        title.textStyle(s -> s.textColor(0xFF2A1A08).textShadow(false).adaptiveWidth(true));
        UIElement plate = new UIElement().layout(l -> l.paddingHorizontal(9).paddingVertical(3));
        plate.style(s -> s.background(dev.logos.client.CopyTableArt.plate()));
        plate.addChild(title);

        UIElement root = new UIElement().layout(l -> l.paddingAll(9).gapAll(6).alignItems(AlignItems.CENTER));
        root.style(s -> s.background(dev.logos.client.CopyTableArt.box()));
        root.addChildren(plate, desk, page);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(MANUSCRIPT)), player);
    }

    private static final net.minecraft.resources.Identifier MANUSCRIPT =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.logos.LogosMod.MODID, "lss/manuscript.lss");
    private static final int WIDTH = 9 * 18 + 10, TRAY_W = 46, TRAY_H = 58;

    private static ItemSlot slot(Slot s) {
        ItemSlot slot = new ItemSlot();
        slot.bind(s);
        return slot;
    }

    private static UIElement tray(ItemSlot slot, com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture art) {
        UIElement e = new UIElement().layout(l -> l.width(TRAY_W).height(TRAY_H).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        e.style(s -> s.background(art));
        e.addChild(slot);
        return e;
    }

    /** The player's slots drawn as shallow squares inked on the parchment, like the trays' own. */
    private static void paperSlots(UIElement element) {
        for (UIElement child : element.getChildren()) {
            if (child instanceof ItemSlot slot) slot.style(s -> s.background(dev.logos.client.CopyTableArt.socket(true)));
            else paperSlots(child);
        }
    }

    private static Label caption(String key, int width) {
        Label label = new Label();
        label.setText(Component.translatable(key));
        label.textStyle(s -> s.textColor(dev.logos.client.CopyTableArt.ON_WOOD).textAlignHorizontal(com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER));
        label.layout(l -> l.width(width));
        return label;
    }
}
