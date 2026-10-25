package dev.logos.block;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;

import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * The cultivation jar's screen, a ledger page like the mana devices': the seed and how far it has grown
 * toward its harvests, the medium on the soil with its reserve, the jar's mana, and what it will yield.
 */
final class CultivationJarUI {
    private static final int GROWTH_COLOUR = 0xFF7FB65A;

    private CultivationJarUI() {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof CultivationJarBlockEntity jar)) return ModularUI.of(UI.of(new UIElement()), player);
        ItemSlot seed = slot(jar.seedSlot(), 0, "jar_seed");
        ProgressBar growth = new ProgressBar().setRange(0, 1);
        growth.setId("jar_growth");
        growth.label(l -> l.setText(Component.empty()));
        growth.bar(b -> b.style(s -> s.background(new ColorRectTexture(GROWTH_COLOUR))));
        growth.bind(DataBindingBuilder.floatValS2C(() -> jar.maxAge() == 0 ? 0f : (float) jar.age() / jar.maxAge()).build());
        growth.layout(l -> l.width(56).height(12));
        UIElement outputs = ManaDeviceUI.row();
        for (int i = 0; i < CultivationJarBlockEntity.OUTPUTS; i++) outputs.addChild(slot(jar.outputs(), i, "jar_output_" + i));

        ItemSlot reserve = slot(jar.reserve(), 0, "jar_reserve");
        Label medium = new Label();
        medium.setId("jar_medium");
        medium.bind(DataBindingBuilder.componentS2C(() -> jar.medium().isEmpty()
                ? Component.translatable("logos.cultivation_jar.no_medium")
                : Component.translatable("logos.cultivation_jar.medium", jar.medium().getHoverName(), jar.mediumUses())).build());
        medium.layout(l -> l.width(140));
        medium.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));

        Label manaText = new Label();
        manaText.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.mana_device.mana", (int) jar.mana(),
                (int) CultivationJarBlockEntity.MAX_MANA)).build());
        ProgressBar mana = ManaDeviceUI.gauge(() -> (float) jar.mana(), (float) CultivationJarBlockEntity.MAX_MANA, 148);

        Label yield = new Label();
        yield.setId("jar_yield");
        yield.bind(DataBindingBuilder.componentS2C(jar::yieldLine).build());
        Label status = new Label();
        status.setId("jar_status");
        status.bind(DataBindingBuilder.componentS2C(jar::status).build());

        UIElement page = ManaDeviceUI.page(176,
                ManaDeviceUI.title("block.logos.cultivation_jar"),
                ManaDeviceUI.row(seed, new UIElement().layout(l -> l.height(18).alignItems(AlignItems.CENTER).justifyContent(
                        dev.vfyjxf.taffy.style.AlignContent.CENTER)).addChildren(growth), outputs),
                ManaDeviceUI.row(reserve, medium),
                manaText, mana, yield, status, new InventorySlots());
        return ManaDeviceUI.screen(player, page);
    }

    private static ItemSlot slot(ItemStacksResourceHandler handler, int index, String id) {
        ItemSlot slot = new ItemSlot();
        slot.setId(id);
        slot.bind(new ResourceHandlerSlot(handler, handler::set, index, 0, 0));
        return slot;
    }
}
