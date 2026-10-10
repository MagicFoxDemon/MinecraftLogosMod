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
 * 咒语抄写台. Like a cartography table: the original (a written page or an ember) on top, empty
 * pages below, and the copy on the right. Copying is free and keeps the original; each copy uses
 * one empty page and takes that page's grade.
 */
public class CopyTableBlock extends Block implements EntityBlock, BlockUIMenuType.BlockUI {
    public static final MapCodec<CopyTableBlock> CODEC = simpleCodec(CopyTableBlock::new);

    public CopyTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
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
