package dev.logos.block;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

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
        ItemSlot seed = ManaDeviceUI.slot(jar.seedSlot(), 0, "jar_seed");
        UIElement growth = ManaDeviceUI.growthBar("jar_growth", () -> jar.maxAge() == 0 ? 0f : (float) jar.age() / jar.maxAge(), GROWTH_COLOUR, 56);
        UIElement outputs = ManaDeviceUI.row();
        for (int i = 0; i < CultivationJarBlockEntity.OUTPUTS; i++) outputs.addChild(ManaDeviceUI.slot(jar.outputs(), i, "jar_output_" + i));

        ItemSlot reserve = ManaDeviceUI.slot(jar.reserve(), 0, "jar_reserve");
        Label medium = ManaDeviceUI.line("jar_medium", () -> jar.medium().isEmpty()
                ? Component.translatable("logos.cultivation_jar.no_medium")
                : Component.translatable("logos.cultivation_jar.medium", jar.medium().getHoverName(), jar.mediumUses()));
        medium.layout(l -> l.width(140));
        medium.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));

        Label manaText = ManaDeviceUI.manaText(jar);
        ProgressBar mana = ManaDeviceUI.gauge(jar, 148);

        Label yield = ManaDeviceUI.line("jar_yield", jar::yieldLine);
        Label status = ManaDeviceUI.line("jar_status", jar::status);

        UIElement page = ManaDeviceUI.page(176,
                ManaDeviceUI.title("block.logos.cultivation_jar"),
                ManaDeviceUI.row(seed, growth, outputs),
                ManaDeviceUI.row(reserve, medium),
                manaText, mana, yield, status, new InventorySlots());
        return ManaDeviceUI.screen(player, page);
    }
}
