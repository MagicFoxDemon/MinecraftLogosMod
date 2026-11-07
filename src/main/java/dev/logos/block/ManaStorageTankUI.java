package dev.logos.block;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
        Label manaText = ManaDeviceUI.manaText(tank);
        manaText.setId("tank_mana");
        ProgressBar gauge = ManaDeviceUI.gauge(tank, 148);
        gauge.setId("mana_gauge");

        Button mode = new Button().setText(Component.translatable("logos.mana_storage_tank.switch"));
        mode.setId("tank_mode");
        mode.setOnServerClick(e -> tank.setMode(tank.mode().next()));
        dev.logos.client.AlchemyArt.paper(mode);
        Label modeText = ManaDeviceUI.line("tank_mode_text", () -> Component.translatable("logos.mana_storage_tank.mode", tank.mode().displayName()));

        ItemSlot input = ManaDeviceUI.slot(tank.input(), 0, "tank_input");
        Label inputHint = new Label();
        inputHint.setText(Component.translatable("logos.mana_storage_tank.input"));
        inputHint.layout(l -> l.width(140));
        inputHint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));

        ItemSlot seed = ManaDeviceUI.slot(tank.seed(), 0, "tank_seed");
        UIElement growth = ManaDeviceUI.growthBar("tank_growth", tank::growth, CRYSTAL_COLOUR, 80);
        ItemSlot output = ManaDeviceUI.slot(tank.output(), 0, "tank_output");

        Label status = ManaDeviceUI.line("tank_status", () -> status(tank));

        UIElement page = ManaDeviceUI.page(176,
                ManaDeviceUI.title("block.logos.mana_storage_tank"),
                manaText, gauge, ManaDeviceUI.row(mode, modeText),
                ManaDeviceUI.row(input, inputHint),
                new Label().setText(Component.translatable("logos.mana_storage_tank.condense")),
                ManaDeviceUI.row(seed, growth, output),
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
}
