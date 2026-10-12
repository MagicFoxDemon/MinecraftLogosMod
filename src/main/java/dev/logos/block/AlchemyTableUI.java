package dev.logos.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.FluidSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;

import dev.logos.alchemy.AlchemyRecipes;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.net.AlchemyCardPayload;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.IndexModifier;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The alchemy table's screen. On the left the storage (raw-material and product areas), the fuel
 * slot and mana gauge, the tank, and the player's inventory. In the middle the canvas, where process
 * cards lie; on the right the four kinds of card, to be dragged onto the canvas. A card on the canvas
 * shows its inputs (designated but empty slots show their item faded), its outputs and its progress,
 * lets one designate an item from storage, and picks a route for each of its process's products. Its
 * title bar drags it about; × takes it off. What a card does is sent to the server as
 * {@link AlchemyCardPayload}s; the screen follows the table's state as the client sees it.
 */
public final class AlchemyTableUI {
    public static final int CANVAS_W = 300, CANVAS_H = 280, CARD_W = 140, CARD_H = 136;
    /** The gauges: the pale blue of the hourglass sand, and water blue. */
    private static final int MANA_COLOUR = 0xFF9FD3EE, FLUID_COLOUR = 0xFF3F76E4;
    /** A designated, empty input slot shows its item under this veil of slot grey. */
    /** A card's note on why it is not working. */
    private static final int STATUS_COLOUR = 0xFFFFB060;
    private static final int GHOST = 0xA08B8B8B;

    private AlchemyTableUI() {}

    /** Where the {@code index}th card goes when none is chosen: the canvas holds two by two. */
    public static int defaultX(int index) {
        return index % 2 * (CARD_W + 10);
    }

    public static int defaultY(int index) {
        return index / 2 * (CARD_H + 4);
    }

    public static int clampX(int x) {
        return Math.clamp(x, 0, CANVAS_W - CARD_W);
    }

    public static int clampY(int y) {
        return Math.clamp(y, 0, CANVAS_H - CARD_H);
    }

    /** What is being dragged: a card from the palette, or a card already on the canvas held at ({@code dx}, {@code dy}). */
    private record NewCard(Process process) {}

    private record MovedCard(int index, float dx, float dy) {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof AlchemyTableBlockEntity table)) {
            return ModularUI.of(UI.of(new UIElement()), player);
        }
        UIElement canvas = new UIElement().layout(l -> l.width(CANVAS_W).height(CANVAS_H));
        canvas.setId("canvas");
        canvas.style(s -> s.background(new ColorRectTexture(0x30000000)));
        canvas.addEventListener(UIEvents.DRAG_PERFORM, e -> {
            Object dragged = e.dragHandler == null ? null : e.dragHandler.draggingObject;
            int x = (int) (e.x - canvas.getPositionX()), y = (int) (e.y - canvas.getPositionY());
            if (dragged instanceof NewCard n) send(AlchemyCardPayload.add(holder.pos, n.process, x - 20, y - 6));
            else if (dragged instanceof MovedCard m) send(AlchemyCardPayload.move(holder.pos, m.index, (int) (x - m.dx), (int) (y - m.dy)));
        });
        List<CardPanel> panels = new ArrayList<>();
        for (int i = 0; i < AlchemyTableBlockEntity.MAX_CARDS; i++) panels.add(new CardPanel(table, holder.pos, i));
        panels.forEach(canvas::addChild);
        // Hidden elements are not ticked, so the always-shown canvas keeps the panels up to date.
        canvas.addEventListener(UIEvents.TICK, e -> panels.forEach(CardPanel::refresh));

        UIElement root = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(7).gapAll(8).flexDirection(FlexDirection.ROW));
        root.addChildren(storage(table), canvas, palette(table));
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    private static void send(AlchemyCardPayload payload) {
        dev.logos.client.ClientHooks.sendToServer(payload);
    }

    // ---- storage ---------------------------------------------------------------------------------

    /** The left column: storage, fuel and mana, the tank, the inventory. */
    private static UIElement storage(AlchemyTableBlockEntity table) {
        UIElement raw = new UIElement();
        for (int row = 0; row < AlchemyTableBlockEntity.RAW_SLOTS / 9; row++) raw.addChild(slotRow(table, row * 9, true));
        UIElement products = slotRow(table, AlchemyTableBlockEntity.RAW_SLOTS, false);

        // Fuel slot beside the mana gauge.
        ItemSlot fuel = new ItemSlot();
        fuel.bind(new ResourceHandlerSlot(table.fuel(), table.fuel()::set, 0, 0, 0));
        Label manaText = new Label();
        manaText.bind(DataBindingBuilder.componentS2C(() -> Component.translatable("logos.alchemy_table.mana",
                (int) table.mana(), AlchemyTableBlockEntity.MAX_MANA)).build());
        ProgressBar manaGauge = gauge(() -> (float) table.mana(), AlchemyTableBlockEntity.MAX_MANA, 9 * 18 - 26, MANA_COLOUR);
        manaGauge.setId("mana_gauge");
        UIElement manaRow = row(fuel, column(manaText, manaGauge));

        // The tank slot (buckets and bottles can be clicked on it) beside the fluid gauge and the button that empties it.
        FluidSlot tank = new FluidSlot().bind(table.tank(), 0).setCapacity(AlchemyTableBlockEntity.TANK_CAPACITY);
        tank.setAllowClickFilled(true).setAllowClickDrained(true);
        Label fluidText = new Label();
        fluidText.bind(DataBindingBuilder.componentS2C(() -> {
            var f = table.fluid();
            return Component.translatable("logos.alchemy_table.fluid", f.isEmpty() ? Component.translatable("logos.alchemy_table.empty") : f.getHoverName(),
                    f.getAmount(), AlchemyTableBlockEntity.TANK_CAPACITY);
        }).build());
        Button clear = new Button().setText(Component.translatable("logos.alchemy_table.clear_tank"));
        clear.setOnServerClick(e -> table.clearTank());
        ProgressBar fluidGauge = gauge(() -> (float) table.fluid().getAmount(), AlchemyTableBlockEntity.TANK_CAPACITY, 9 * 18 - 66, FLUID_COLOUR);
        fluidGauge.setId("fluid_gauge");
        UIElement tankRow = row(tank, column(fluidText, row(fluidGauge, clear)));

        return new UIElement().layout(l -> l.gapAll(4)).addChildren(new Label().setText(Component.translatable("block.logos.alchemy_table")),
                new Label().setText(Component.translatable("logos.alchemy_table.raw")), raw,
                new Label().setText(Component.translatable("logos.alchemy_table.products")), products,
                manaRow, tankRow, new InventorySlots());
    }

    /** Nine storage slots from {@code start}; product slots can only be taken from. */
    private static UIElement slotRow(AlchemyTableBlockEntity table, int start, boolean raw) {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
        for (int i = start; i < start + 9; i++) {
            ItemSlot slot = new ItemSlot();
            slot.bind(raw ? new ResourceHandlerSlot(table.items(), table.items()::set, i, 0, 0) : new ResourceHandlerSlot(table.items(), table.items()::set, i, 0, 0) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
            row.addChild(slot);
        }
        return row;
    }

    // ---- the palette -----------------------------------------------------------------------------

    /** The right column: one card of each process, to drag onto the canvas, and how many are laid. */
    private static UIElement palette(AlchemyTableBlockEntity table) {
        UIElement column = new UIElement().layout(l -> l.gapAll(4).width(64));
        column.addChild(new Label().setText(Component.translatable("logos.alchemy_table.cards")));
        for (Process p : Process.values()) {
            UIElement chip = new UIElement().layout(l -> l.width(64).height(18).paddingAll(3));
            chip.setId("palette_" + p.getSerializedName());
            chip.style(s -> s.background(new ColorRectTexture(0x40FFFFFF)));
            Label name = new Label();
            name.setText(Component.literal(p.symbol + " ").append(p.displayName()));
            name.setAllowHitTest(false);
            chip.addChild(name);
            chip.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                if (table.cards().size() < AlchemyTableBlockEntity.MAX_CARDS) {
                    chip.startDrag(new NewCard(p), new TextTexture(p.symbol + " " + p.displayName().getString()));
                }
            });
            column.addChild(chip);
        }
        Label count = new Label();
        count.addEventListener(UIEvents.TICK, e -> count.setText(Component.translatable("logos.alchemy_table.card_count",
                table.cards().size(), AlchemyTableBlockEntity.MAX_CARDS)));
        Label hint = new Label();
        hint.setText(Component.translatable("logos.alchemy_table.card_hint"));
        hint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
        hint.layout(l -> l.width(64));
        column.addChildren(count, hint);
        return column;
    }

    // ---- a card on the canvas --------------------------------------------------------------------

    /**
     * The {@code index}th card's place on the canvas. All four are built with the screen, so their
     * slots are part of the menu from the start; each shows itself only while the table has that
     * many cards, and follows the card now in that place.
     */
    private static final class CardPanel extends UIElement {
        private final AlchemyTableBlockEntity table;
        private final BlockPos pos;
        private final int index;
        private final Label title = new Label();
        /** Why the card is not working, when it is not. */
        private final Label status = new Label();
        private final ItemSlot[] inputs = new ItemSlot[ProcessCard.SLOTS];
        private final Selector<Item> designate = new Selector<>();
        private final UIElement routes = new UIElement().layout(l -> l.gapAll(2));
        /** What the route rows were built for, so they are rebuilt only when that changes. */
        private @Nullable Object routesFor;
        private final IGuiTexture[] ghosts = new IGuiTexture[ProcessCard.SLOTS];
        private final Item[] ghostItems = new Item[ProcessCard.SLOTS];

        CardPanel(AlchemyTableBlockEntity table, BlockPos pos, int index) {
            this.table = table;
            this.pos = pos;
            this.index = index;
            setId("card" + index);
            addClass("panel_bg");
            layout(l -> l.positionType(TaffyPosition.ABSOLUTE).width(CARD_W).paddingAll(4).gapAll(3));

            // Title bar: drag it to move the card; × takes the card off.
            title.setId("card" + index + "_title");
            title.layout(l -> l.flex(1));
            title.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                float dx = e.x - getPositionX(), dy = e.y - getPositionY();
                title.startDrag(new MovedCard(index, dx, dy), new ColorRectTexture(0x40FFFFFF));
            });
            Button remove = new Button().setText("×");
            remove.setId("card" + index + "_remove");
            remove.setOnClick(e -> send(AlchemyCardPayload.remove(pos, index)));
            ProgressBar progress = new ProgressBar().setRange(0, ProcessCard.CRAFT_TICKS);
            progress.label(l -> l.setText(Component.empty()));
            progress.bar(b -> b.style(s -> s.background(new ColorRectTexture(MANA_COLOUR))));
            progress.bind(DataBindingBuilder.floatValS2C(() -> {
                ProcessCard c = card();
                return c == null ? 0f : (float) c.progress();
            }).build());
            progress.layout(l -> l.width(36).height(12));
            UIElement header = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER));
            header.addChildren(title, progress, remove);

            // Inputs → outputs.
            UIElement in = grid(new CardSlots(table, index, true), true), out = grid(new CardSlots(table, index, false), false);
            UIElement body = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
            Label arrow = new Label();
            arrow.setText(Component.literal("→"));
            arrow.layout(l -> l.width(10));
            body.addChildren(in, arrow, out);

            // Designate an item that lies in storage; clear the designations of empty slots.
            designate.setId("card" + index + "_designate");
            designate.setCandidateUIProvider(UIElementProvider.text(item -> item == null
                    ? Component.translatable("logos.alchemy_table.designate") : new ItemStack(item).getHoverName()));
            designate.layout(l -> l.flex(1));
            designate.setOnValueChanged(item -> {
                if (item != null) send(AlchemyCardPayload.designate(pos, index, item));
            });
            Button clear = new Button().setText(Component.translatable("logos.alchemy_table.clear_designations"));
            clear.setOnClick(e -> send(AlchemyCardPayload.clear(pos, index)));
            UIElement designateRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER));
            designateRow.addChildren(designate, clear);

            status.setId("card" + index + "_status");
            status.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true).textColor(STATUS_COLOUR));
            status.layout(l -> l.width(CARD_W - 8));
            addChildren(header, status, body, designateRow, routes);
            setDisplay(false);
        }

        private @Nullable ProcessCard card() {
            return index < table.cards().size() ? table.cards().get(index) : null;
        }

        private UIElement grid(CardSlots handler, boolean input) {
            UIElement grid = new UIElement();
            for (int r = 0; r < 3; r++) {
                UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
                for (int c = 0; c < 3; c++) {
                    int slot = r * 3 + c;
                    ItemSlot s = new ItemSlot();
                    s.setId("card" + index + (input ? "_in" : "_out") + slot);
                    s.bind(new ResourceHandlerSlot(handler, handler, slot, 0, 0));
                    if (input) inputs[slot] = s;
                    row.addChild(s);
                }
                grid.addChild(row);
            }
            return grid;
        }

        /** Follows the card now in this place: shown or not, where, which process, its designations and routes. */
        private void refresh() {
            ProcessCard card = card();
            setDisplay(card != null);
            if (card == null) return;
            layout(l -> l.left(card.x()).top(card.y()));
            title.setText(Component.literal(card.process.symbol + " ").append(card.process.displayName()));
            status.setText(card.status());
            status.setDisplay(!card.status().getString().isEmpty());
            for (int i = 0; i < ProcessCard.SLOTS; i++) {
                Item item = card.designated(i);
                if (item == ghostItems[i]) continue;
                ghostItems[i] = item;
                ghosts[i] = item == null ? IGuiTexture.EMPTY : new GuiTextureGroup(new ItemStackTexture(item), new ColorRectTexture(GHOST));
                IGuiTexture ghost = ghosts[i];
                inputs[i].slotStyle(s -> s.slotOverlay(ghost).showSlotOverlayOnlyEmpty(true));
            }
            List<Item> stored = new ArrayList<>();
            for (int i = 0; i < table.items().size(); i++) {
                ItemResource r = table.items().getResource(i);
                if (!r.isEmpty() && !stored.contains(r.getItem())) stored.add(r.getItem());
            }
            if (!stored.equals(designate.getCandidates())) designate.setCandidates(stored);
            refreshRoutes(card);
        }

        /** One row per product of the card's process: the product, and where it goes. */
        private void refreshRoutes(ProcessCard card) {
            List<Item> products = AlchemyRecipes.products(card.process);
            List<Integer> targets = new ArrayList<>(List.of(ProcessCard.KEEP, ProcessCard.PRODUCTS));
            for (int j = 0; j < table.cards().size(); j++) if (j != index) targets.add(j);
            List<Object> key = new ArrayList<>(List.of(card.process, products, targets));
            for (Item p : products) key.add(card.route(p));
            for (int j : targets) if (j >= 0) key.add(table.cards().get(j).process);
            if (Objects.equals(key, routesFor)) return;
            routesFor = key;
            for (UIElement child : new ArrayList<>(routes.getChildren())) routes.removeChild(child);
            for (Item product : products) {
                UIElement icon = new UIElement().layout(l -> l.width(16).height(16));
                icon.style(s -> s.background(new ItemStackTexture(product)));
                Selector<Integer> where = new Selector<>();
                where.setId("card" + index + "_route_" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(product).getPath());
                where.setCandidateUIProvider(UIElementProvider.text(this::targetName));
                where.setCandidates(targets);
                where.setSelected(card.route(product), false);
                where.layout(l -> l.flex(1));
                where.setOnValueChanged(target -> {
                    if (target != null) send(AlchemyCardPayload.route(pos, index, product, target));
                });
                routes.addChild(new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER))
                        .addChildren(icon, where));
            }
        }

        private Component targetName(@Nullable Integer target) {
            if (target == null) return Component.empty();
            if (target == ProcessCard.KEEP) return Component.translatable("logos.alchemy_table.route_keep");
            if (target == ProcessCard.PRODUCTS) return Component.translatable("logos.alchemy_table.route_products");
            if (target >= 0 && target < table.cards().size()) {
                Process p = table.cards().get(target).process;
                return Component.literal("➜ " + (target + 1) + " " + p.symbol + " ").append(p.displayName());
            }
            return Component.literal("?");
        }
    }

    /**
     * The inputs or outputs of whichever card is in place {@code card} now, for the screen's slots;
     * empty and closed while there is none. Outputs can only be taken from.
     */
    private record CardSlots(AlchemyTableBlockEntity table, int card, boolean input) implements ResourceHandler<ItemResource>, IndexModifier<ItemResource> {
        private @Nullable ResourceHandler<ItemResource> handler() {
            if (card >= table.cards().size()) return null;
            ProcessCard c = table.cards().get(card);
            return input ? c.inputs() : c.outputs();
        }

        @Override
        public int size() {
            return ProcessCard.SLOTS;
        }

        @Override
        public ItemResource getResource(int index) {
            var h = handler();
            return h == null ? ItemResource.EMPTY : h.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            var h = handler();
            return h == null ? 0 : h.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            var h = handler();
            return h == null ? 0 : h.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            var h = handler();
            return input && h != null && h.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            var h = handler();
            return input && h != null ? h.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            var h = handler();
            return h == null ? 0 : h.extract(index, resource, amount, transaction);
        }

        @Override
        public void set(int index, ItemResource resource, int amount) {
            if (card >= table.cards().size()) return;
            ProcessCard c = table.cards().get(card);
            (input ? c.inputs() : c.outputs()).set(index, resource, amount);
        }
    }

    // ---- small pieces ----------------------------------------------------------------------------

    /** A bar following a server-side value from 0 to {@code max}. */
    private static ProgressBar gauge(Supplier<Float> value, float max, int width, int colour) {
        ProgressBar bar = new ProgressBar().setRange(0, max);
        bar.label(l -> l.setText(Component.empty()));
        bar.bar(b -> b.style(s -> s.background(new ColorRectTexture(colour))));
        bar.bind(DataBindingBuilder.floatValS2C(value).build());
        bar.layout(l -> l.width(width).height(12));
        return bar;
    }

    private static UIElement column(UIElement... children) {
        return new UIElement().layout(l -> l.gapAll(2)).addChildren(children);
    }

    private static UIElement row(UIElement... children) {
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).alignItems(AlignItems.CENTER)).addChildren(children);
    }
}
