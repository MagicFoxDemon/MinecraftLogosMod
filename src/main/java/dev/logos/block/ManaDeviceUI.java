package dev.logos.block;

import java.util.function.Supplier;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableValue;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;

import dev.logos.client.AlchemyArt;
import dev.logos.client.ManaScreens;
import dev.logos.runtime.ManaField;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.AlignContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * The screens of the mana spreader and the mana collector: a ledger page on the bench, as the alchemy
 * table keeps its storage. The spreader's shows its fuel drawer, its mana and a map of the 5×5 chunks
 * around it; the collector's its mana, its chunk's field, and a gauge of what it draws.
 */
public final class ManaDeviceUI {
    private static final int MANA_COLOUR = 0xFF9FD3EE;
    private static final Identifier MANUSCRIPT = Identifier.fromNamespaceAndPath(dev.logos.LogosMod.MODID, "lss/manuscript.lss");

    private ManaDeviceUI() {}

    static ModularUI spreader(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof ManaSpreaderBlockEntity spreader)) return ModularUI.of(UI.of(new UIElement()), player);
        Level level = player.level();
        BlockPos pos = holder.pos;
        ItemSlot fuel = new ItemSlot();
        fuel.setId("fuel");
        fuel.bind(new ResourceHandlerSlot(spreader.fuel(), spreader.fuel()::set, 0, 0, 0));
        Label manaText = new Label();
        manaText.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.mana_device.mana", (int) spreader.mana(),
                (int) ManaSpreaderBlockEntity.MAX_MANA)).build());
        ProgressBar gauge = gauge(() -> (float) spreader.mana(), (float) ManaSpreaderBlockEntity.MAX_MANA, 120);
        gauge.setId("mana_gauge");

        // The survey of the chunks around it, worked out at most once a second and only while the screen is open.
        long[] surveyed = {Long.MIN_VALUE};
        Tag[] survey = {new IntArrayTag(new int[0])};
        BindableValue<Tag> surveyValue = hidden(new BindableValue<>());
        surveyValue.bind(DataBindingBuilder.tagS2C(() -> {
            long second = level.getGameTime() / ManaField.PERIOD;
            if (second != surveyed[0] && level instanceof ServerLevel server) {
                surveyed[0] = second;
                survey[0] = new IntArrayTag(ManaField.survey(server, pos, ManaScreens.ChunkMap.R));
            }
            return survey[0];
        }).build());
        BindableValue<Float> poured = hidden(new BindableValue<>(0f));
        poured.bind(DataBindingBuilder.floatValS2C(() -> (float) spreader.lastPoured()).build());
        ManaScreens.ChunkMap map = new ManaScreens.ChunkMap(surveyValue, poured, pos.getX() >> 4, pos.getZ() >> 4);

        Label status = new Label();
        status.setId("spreader_status");
        status.bind(DataBindingBuilder.componentS2C(() -> spreader.lastPoured() > 0
                ? Component.translatable("logos.mana_spreader.pouring", String.format("%.0f", spreader.lastPoured()))
                : Component.translatable(spreader.mana() > 0 ? "logos.mana_spreader.full" : "logos.mana_spreader.idle")).build());

        UIElement page = page(176,
                title("block.logos.mana_spreader"),
                new Label().setText(Component.translatable("logos.mana_spreader.fuel")),
                row(fuel, column(manaText, gauge)),
                new Label().setText(Component.translatable("logos.mana_spreader.map")),
                new UIElement().layout(l -> l.widthPercent(100).alignItems(AlignItems.CENTER)).addChildren(map),
                status, surveyValue, poured, new InventorySlots());
        return screen(player, page);
    }

    static ModularUI collector(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof ManaCollectorBlockEntity collector)) return ModularUI.of(UI.of(new UIElement()), player);
        Level level = player.level();
        BlockPos pos = holder.pos;
        Label manaText = new Label();
        manaText.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.mana_device.mana", (int) collector.mana(),
                (int) ManaCollectorBlockEntity.MAX_MANA)).build());
        ProgressBar gauge = gauge(() -> (float) collector.mana(), (float) ManaCollectorBlockEntity.MAX_MANA, 148);
        gauge.setId("mana_gauge");
        Label field = new Label();
        field.setId("collector_field");
        field.bind(DataBindingBuilder.componentS2C(() -> level instanceof ServerLevel server
                ? Component.translatable("logos.mana_collector.field", String.format("%.1f", ManaField.spread(server, pos)), (int) ManaField.CAP,
                        String.format("%.2f", ManaField.ambientRate(server, pos)))
                : Component.empty()).build());

        BindableValue<Float> ambient = hidden(new BindableValue<>(0f));
        ambient.bind(DataBindingBuilder.floatValS2C(() -> (float) collector.lastAmbient()).build());
        BindableValue<Float> spread = hidden(new BindableValue<>(0f));
        spread.bind(DataBindingBuilder.floatValS2C(() -> (float) collector.lastSpread()).build());
        Label rate = new Label();
        rate.setId("collector_rate");
        rate.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.mana_collector.rate",
                String.format("%.2f", collector.lastAmbient() + collector.lastSpread()),
                String.format("%.2f", collector.lastAmbient()), String.format("%.2f", collector.lastSpread()))).build());

        UIElement page = page(176,
                title("block.logos.mana_collector"),
                manaText, gauge, field,
                new Label().setText(Component.translatable("logos.mana_collector.gauge")),
                new UIElement().layout(l -> l.widthPercent(100).alignItems(AlignItems.CENTER)).addChildren(new ManaScreens.IntakeGauge(ambient, spread)),
                rate, ambient, spread);
        return screen(player, page);
    }

    /** The page centred on the bench, inked like the alchemy table's ledger. */
    private static ModularUI screen(Player player, UIElement page) {
        UIElement root = new UIElement().layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        root.addChild(page);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(MANUSCRIPT)), player);
    }

    private static UIElement page(int width, UIElement... children) {
        UIElement inner = new UIElement().layout(l -> l.widthPercent(100).gapAll(4).paddingAll(8).paddingTop(10));
        inner.style(s -> s.background(AlchemyArt.ledger()));
        inner.addChildren(children);
        // Lines that run long wrap within the page.
        for (UIElement child : children) {
            if (child instanceof Label label) {
                label.layout(l -> l.widthPercent(100));
                label.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
            }
        }
        AlchemyArt.inked(inner);
        UIElement bench = new UIElement().layout(l -> l.width(width + 28).paddingAll(14));
        bench.setId("mana_device_page");
        bench.style(s -> s.background(AlchemyArt.bench()));
        bench.addChild(inner);
        return bench;
    }

    private static Label title(String key) {
        Label label = new Label();
        label.setText(Component.translatable(key));
        return label;
    }

    /** Synced values the drawn parts read; they take no room. */
    private static <T> BindableValue<T> hidden(BindableValue<T> value) {
        value.layout(l -> l.width(0).height(0));
        return value;
    }

    private static ProgressBar gauge(Supplier<Float> value, float max, int width) {
        ProgressBar bar = new ProgressBar().setRange(0, max);
        bar.label(l -> l.setText(Component.empty()));
        bar.bar(b -> b.style(s -> s.background(new ColorRectTexture(MANA_COLOUR))));
        bar.bind(DataBindingBuilder.floatValS2C(value).build());
        bar.layout(l -> l.width(width).height(12));
        return bar;
    }

    private static UIElement column(UIElement... children) {
        return new UIElement().layout(l -> l.gapAll(2)).addChildren(children);
    }

    private static UIElement row(UIElement... children) {
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8).alignItems(AlignItems.FLEX_END)).addChildren(children);
    }
}
