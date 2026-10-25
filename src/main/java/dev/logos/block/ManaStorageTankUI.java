package dev.logos.block;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;

import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * The mana storage tank's screen, a ledger page like the other mana devices': its mana, the mode switch, the
 * input that dissolves mana items, and the seed with the crystal growing on it toward the output.
 */
final class ManaStorageTankUI {
    private static final int CRYSTAL_COLOUR = 0xFF8FD8F0;

    private ManaStorageTankUI() {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof ManaStorageTankBlockEntity tank)) return ModularUI.of(UI.of(new UIElement()), player);
        Label manaText = new Label();
        manaText.setId("tank_mana");
        manaText.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.mana_device.mana", (int) tank.mana(),
                (int) ManaStorageTankBlockEntity.MAX_MANA)).build());
        ProgressBar gauge = ManaDeviceUI.gauge(() -> (float) tank.mana(), (float) ManaStorageTankBlockEntity.MAX_MANA, 148);
        gauge.setId("mana_gauge");

        Button mode = new Button().setText(Component.translatable("logos.mana_storage_tank.switch"));
        mode.setId("tank_mode");
        mode.setOnServerClick(e -> tank.setMode(tank.mode().next()));
        dev.logos.client.AlchemyArt.paper(mode);
        Label modeText = new Label();
        modeText.setId("tank_mode_text");
        modeText.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.mana_storage_tank.mode", tank.mode().displayName())).build());

        ItemSlot input = slot(tank.input(), "tank_input");
        Label inputHint = new Label();
        inputHint.setText(Component.translatable("logos.mana_storage_tank.input"));
        inputHint.layout(l -> l.width(140));
        inputHint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));

        ItemSlot seed = slot(tank.seed(), "tank_seed");
        ProgressBar growth = new ProgressBar().setRange(0, 1);
        growth.setId("tank_growth");
        growth.label(l -> l.setText(Component.empty()));
        growth.bar(b -> b.style(s -> s.background(new ColorRectTexture(CRYSTAL_COLOUR))));
        growth.bind(DataBindingBuilder.floatValS2C(tank::growth).build());
        growth.layout(l -> l.width(80).height(12));
        ItemSlot output = slot(tank.output(), "tank_output");

        Label status = new Label();
        status.setId("tank_status");
        status.bind(DataBindingBuilder.componentS2C(() -> status(tank)).build());

        UIElement page = ManaDeviceUI.page(176,
                ManaDeviceUI.title("block.logos.mana_storage_tank"),
                manaText, gauge, ManaDeviceUI.row(mode, modeText),
                ManaDeviceUI.row(input, inputHint),
                new Label().setText(Component.translatable("logos.mana_storage_tank.condense")),
                ManaDeviceUI.row(seed, new UIElement().layout(l -> l.height(18).alignItems(AlignItems.CENTER)
                        .justifyContent(dev.vfyjxf.taffy.style.AlignContent.CENTER)).addChildren(growth), output),
                status, new InventorySlots());
        return ManaDeviceUI.screen(player, page);
    }

    /** What the tank is doing in its mode. */
    private static Component status(ManaStorageTankBlockEntity tank) {
        if (tank.getLevel() != null && tank.getLevel().hasNeighborSignal(tank.getBlockPos()) && tank.mode() != ManaStorageTankBlockEntity.Mode.STORE) {
            return Component.translatable("logos.mana_storage_tank.redstone");
        }
        return switch (tank.mode()) {
            case STORE -> Component.translatable("logos.mana_storage_tank.storing");
            case SUPPLY -> Component.translatable("logos.mana_storage_tank.supplying", String.format("%.0f", tank.lastSupplied()));
            case CONDENSE -> {
                if (tank.seed().getAmountAsInt(0) == 0) yield Component.translatable("logos.mana_storage_tank.no_seed");
                Item from = tank.seed().getResource(0).getItem();
                Item into = ManaStorageTankBlockEntity.grownFrom(from);
                yield Component.translatable("logos.mana_storage_tank.growing", new ItemStack(into).getHoverName(),
                        (int) (tank.growth() * ManaStorageTankBlockEntity.growCost(from)), (int) Math.ceil(ManaStorageTankBlockEntity.growCost(from)));
            }
        };
    }

    private static ItemSlot slot(ItemStacksResourceHandler handler, String id) {
        ItemSlot slot = new ItemSlot();
        slot.setId(id);
        slot.bind(new ResourceHandlerSlot(handler, handler::set, 0, 0, 0));
        return slot;
    }
}
