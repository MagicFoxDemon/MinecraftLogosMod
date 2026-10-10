package dev.logos.item;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;

import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** A chest-like panel of page slots above the player inventory (wand slots, codex). */
final class PageSlotsUI {
    private PageSlotsUI() {}

    private static Label hintLabel(Component hint, int columns) {
        Label l = new Label();
        l.setText(hint);
        l.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
        l.layout(x -> x.width(Math.max(columns, 9) * 19));
        return l;
    }

    static ModularUI create(Player player, PageContainer container, int columns, Component title, Component hint) {
        UIElement grid = new UIElement().layout(l -> l.gapAll(1));
        UIElement row = null;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (i % columns == 0) {
                row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(1));
                grid.addChild(row);
            }
            ItemSlot slot = new ItemSlot();
            slot.bind(container.slot(i));
            row.addChild(slot);
        }
        UIElement root = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(7).gapAll(4));
        root.addChildren(
                new Label().setText(title),
                grid,
                hintLabel(hint, columns),
                new InventorySlots());
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }
}
