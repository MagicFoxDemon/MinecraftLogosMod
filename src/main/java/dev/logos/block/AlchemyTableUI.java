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
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;

import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.net.AlchemyCardPayload;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.AlignContent;
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
 * The alchemy table's screen, in two tabs so it fits a 16:9 window at the usual GUI scale. 存储:
 * the storage (raw-material and product areas), the switch, the fuel slot and mana gauge, the tank,
 * and the player's inventory, about a chest's size. 工艺: the four kinds of card above the board where
 * process cards lie, the product node beside it and the hotbar below, filling most of the window. A card on the canvas
 * shows its inputs (designated but empty slots show their item faded), its outputs and its progress,
 * lets one designate an item from storage, and lists the products it has made. A product is wired on
 * by dragging it onto another card or onto the product-area node at the canvas's right; unwired, it
 * stays in the card. Wires are drawn with arrows running along them. A card's title bar drags it
 * about; × takes it off. What a card does is sent to the server as
 * {@link AlchemyCardPayload}s; the screen follows the table's state as the client sees it.
 */
public final class AlchemyTableUI {
    /** The board cards lie on; the screen shows as much of it as fits, and it scrolls and pans. */
    public static final int CANVAS_W = 600, CANVAS_H = 400, CARD_W = 140, CARD_H = 136;
    /** The product-area node on the canvas's right edge, which products can be wired to. */
    private static final int NODE_W = 52, NODE_H = 64;
    /** The gauges: the pale blue of the hourglass sand, and water blue. */
    private static final int MANA_COLOUR = 0xFF9FD3EE, FLUID_COLOUR = 0xFF3F76E4;
    /** A card's note on why it is not working. */
    private static final int STATUS_COLOUR = 0xFFFFB060;
    /** A designated, empty input slot shows its item under this veil of slot grey. */
    private static final int GHOST = 0xA08B8B8B;
    /**
     * Wires: to another card in mana blue, to the product area in brass; the arrows running along
     * them in white. Plain shapes for now; the look comes with the screen's art (issue #64).
     */
    private static final ColorRectTexture WIRE_TO_CARD = new ColorRectTexture(0xFF6FB6E8), WIRE_TO_PRODUCTS = new ColorRectTexture(0xFFD9A441),
            ARROW = new ColorRectTexture(0xFFFFFFFF), PORT = new ColorRectTexture(0xFFE0E0E0);
    /** How long an arrow takes to run the length of a wire. */
    private static final long ARROW_MS = 1400;

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

    /** A wire being drawn from card {@code card}'s product {@code item}. */
    private record WireDrag(int card, Item item) {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof AlchemyTableBlockEntity table)) {
            return ModularUI.of(UI.of(new UIElement()), player);
        }
        // The 工艺 page: the palette above, the board (scrolling, with the product node beside it), the hotbar below.
        UIElement productsNode = productsNode();
        Canvas canvas = new Canvas(table, holder.pos, productsNode);
        ScrollerView view = new ScrollerView();
        view.setId("canvas_view");
        view.layout(l -> l.flex(1).heightPercent(100));
        view.addScrollViewChild(canvas);
        canvas.pansIn(view);
        UIElement work = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flex(1).gapAll(4).alignItems(AlignItems.CENTER).widthPercent(100));
        work.addChildren(view, productsNode);
        UIElement processPage = new UIElement().layout(l -> l.flex(1).gapAll(4).widthPercent(100));
        processPage.setId("process_page");
        processPage.addChildren(palette(table), work, hotbar(player));
        canvas.takesDropsOn(processPage);

        // The 存储 page: storage, mana, the tank and the inventory, about a chest's size.
        UIElement storagePage = storage(table);
        storagePage.setId("storage_page");

        UIElement panel = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(6).gapAll(4));
        Button storageTab = new Button().setText(Component.translatable("logos.alchemy_table.tab_storage"));
        storageTab.setId("tab_storage");
        Button processTab = new Button().setText(Component.translatable("logos.alchemy_table.tab_process"));
        processTab.setId("tab_process");
        Runnable showStorage = () -> {
            storagePage.setDisplay(true);
            processPage.setDisplay(false);
            panel.layout(l -> l.widthAuto().heightAuto());
        };
        storageTab.setOnClick(e -> showStorage.run());
        // The process page takes most of the window, whatever its size.
        processTab.setOnClick(e -> {
            storagePage.setDisplay(false);
            processPage.setDisplay(true);
            panel.layout(l -> l.widthPercent(96).heightPercent(94));
        });
        UIElement tabs = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3));
        tabs.addChildren(storageTab, processTab);
        panel.addChildren(tabs, storagePage, processPage);
        showStorage.run();

        UIElement root = new UIElement().layout(l -> l.widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        root.addChild(panel);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    /** The hotbar, on the process page, for putting things into cards straight from one's hand. */
    private static UIElement hotbar(Player player) {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
        for (int i = 0; i < 9; i++) {
            ItemSlot slot = new ItemSlot();
            slot.bind(new net.minecraft.world.inventory.Slot(player.getInventory(), i, 0, 0));
            row.addChild(slot);
        }
        return row;
    }

    /** The product area's node, beside the board; wiring a product to it sends that product to the product area. */
    private static UIElement productsNode() {
        UIElement node = new UIElement();
        node.setId("products_node");
        node.layout(l -> l.width(NODE_W).height(NODE_H).paddingAll(2).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        node.style(s -> s.background(new ColorRectTexture(0x60D9A441)));
        Label name = new Label();
        name.setText(Component.translatable("logos.alchemy_table.products_node"));
        name.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
        name.layout(l -> l.width(NODE_W - 4));
        name.setAllowHitTest(false);
        node.addChild(name);
        return node;
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

        // The switch that runs or stops all the processes; a redstone signal stops them too.
        Button power = new Button().setText(Component.translatable("logos.alchemy_table.toggle"));
        power.setId("run_switch");
        power.setOnServerClick(e -> table.setEnabled(!table.enabled()));
        Label running = new Label();
        running.setId("run_state");
        running.bind(DataBindingBuilder.componentS2C(() -> Component.translatable(!table.enabled() ? "logos.alchemy_table.stopped"
                : table.powered() ? "logos.alchemy_table.redstone_stopped" : "logos.alchemy_table.running")).build());

        return new UIElement().layout(l -> l.gapAll(4)).addChildren(new Label().setText(Component.translatable("block.logos.alchemy_table")), row(power, running),
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

    /** A strip above the board: one card of each process, to drag onto it, and how many are laid. */
    private static UIElement palette(AlchemyTableBlockEntity table) {
        UIElement column = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).alignItems(AlignItems.CENTER));
        for (Process p : Process.values()) {
            UIElement chip = new UIElement().layout(l -> l.width(76).height(16).paddingAll(3));
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
        column.addChild(count);
        return column;
    }

    // ---- the canvas ------------------------------------------------------------------------------

    /**
     * Where cards lie and wires run: a board larger than the screen shows, scrolled with the wheel
     * and the scrollbars or panned by dragging an empty spot. It takes what is dropped on the process
     * page (a new card, a moved card, a wire's end) and draws the wires behind the cards, each with
     * arrows running from its product to where it goes, plus the wire being drawn while one is dragged.
     * While no card is laid it says how to lay one.
     */
    private static final class Canvas extends UIElement {
        private final AlchemyTableBlockEntity table;
        private final List<CardPanel> panels = new ArrayList<>();
        private final BlockPos pos;
        private final UIElement productsNode;
        private final Label hint = new Label();
        private float mouseX, mouseY;
        private @Nullable WireDrag drawing;
        /** Where the mouse was while an empty spot of the board is dragged to pan it; null otherwise. */
        private float @Nullable [] panFrom;

        Canvas(AlchemyTableBlockEntity table, BlockPos pos, UIElement productsNode) {
            this.table = table;
            this.pos = pos;
            this.productsNode = productsNode;
            setId("canvas");
            layout(l -> l.width(CANVAS_W).height(CANVAS_H));
            style(s -> s.background(new ColorRectTexture(0x30000000)));

            hint.setText(Component.translatable("logos.alchemy_table.card_hint"));
            hint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
            hint.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(8).top(8).width(220));
            hint.setAllowHitTest(false);
            addChild(hint);

            for (int i = 0; i < AlchemyTableBlockEntity.MAX_CARDS; i++) panels.add(new CardPanel(table, pos, i));
            panels.forEach(this::addChild);
            // Hidden elements are not ticked, so the always-shown canvas keeps the panels up to date.
            addEventListener(UIEvents.TICK, e -> {
                panels.forEach(CardPanel::refresh);
                hint.setDisplay(table.cards().isEmpty());
            });
        }

        /** Drops anywhere on {@code page} (the board, the product node) land here. */
        void takesDropsOn(UIElement page) {
            page.addEventListener(UIEvents.DRAG_UPDATE, e -> {
                mouseX = e.x;
                mouseY = e.y;
                drawing = e.dragHandler != null && e.dragHandler.draggingObject instanceof WireDrag w ? w : null;
            }, true);
            page.addEventListener(UIEvents.DRAG_END, e -> drawing = null, true);
            page.addEventListener(UIEvents.DRAG_PERFORM, e -> {
                drawing = null;
                Object dragged = e.dragHandler == null ? null : e.dragHandler.draggingObject;
                int x = (int) (e.x - getPositionX()), y = (int) (e.y - getPositionY());
                if (dragged instanceof NewCard n) send(AlchemyCardPayload.add(pos, n.process, x - 20, y - 6));
                else if (dragged instanceof MovedCard m) send(AlchemyCardPayload.move(pos, m.index, (int) (x - m.dx), (int) (y - m.dy)));
                else if (dragged instanceof WireDrag w) send(AlchemyCardPayload.route(pos, w.card, w.item, wireTarget(w.card, e.x, e.y)));
            }, true);
        }

        /** Dragging an empty spot of the board pans {@code view}. */
        void pansIn(ScrollerView view) {
            addEventListener(UIEvents.MOUSE_DOWN, e -> {
                if (e.target == this && e.button == 0) panFrom = new float[] {e.x, e.y};
            });
            view.addEventListener(UIEvents.MOUSE_MOVE, e -> {
                if (panFrom == null) return;
                view.horizontalScroller.scrollValue(panFrom[0] - e.x);
                view.verticalScroller.scrollValue(panFrom[1] - e.y);
                panFrom = new float[] {e.x, e.y};
            }, true);
            view.addEventListener(UIEvents.MOUSE_UP, e -> panFrom = null, true);
            view.addEventListener(UIEvents.MOUSE_LEAVE, e -> panFrom = null);
        }

        /** What a wire dropped at ({@code x}, {@code y}) connects to: the product node, another card, or nothing (the product stays). */
        private int wireTarget(int from, float x, float y) {
            if (inside(productsNode, x, y)) return ProcessCard.PRODUCTS;
            for (int j = 0; j < panels.size(); j++) {
                if (j != from && j < table.cards().size() && inside(panels.get(j), x, y)) return j;
            }
            return ProcessCard.KEEP;
        }

        private static boolean inside(UIElement e, float x, float y) {
            return x >= e.getPositionX() && x <= e.getPositionX() + e.getSizeWidth() && y >= e.getPositionY() && y <= e.getPositionY() + e.getSizeHeight();
        }

        @Override
        protected void drawBackgroundAdditional(IGUIContext ctx) {
            super.drawBackgroundAdditional(ctx);
            long now = System.currentTimeMillis();
            for (int i = 0; i < Math.min(panels.size(), table.cards().size()); i++) {
                ProcessCard card = table.cards().get(i);
                CardPanel from = panels.get(i);
                for (Item item : card.made()) {
                    int target = card.route(item);
                    UIElement port = from.port(item);
                    if (port == null || target == ProcessCard.KEEP) continue;
                    float sx = port.getPositionX() + port.getSizeWidth(), sy = port.getPositionY() + port.getSizeHeight() / 2;
                    if (target == ProcessCard.PRODUCTS) {
                        wire(ctx, sx, sy, productsNode.getPositionX(), productsNode.getPositionY() + NODE_H / 2f, WIRE_TO_PRODUCTS, now);
                    } else if (target >= 0 && target < table.cards().size() && target != i) {
                        CardPanel to = panels.get(target);
                        float tx = to.getPositionX(), ty = to.getPositionY() + 10;
                        wire(ctx, sx, sy, tx, ty, WIRE_TO_CARD, now);
                        ctx.drawTexture(PORT, tx - 3, ty - 3, 3, 6);
                    }
                }
            }
            if (drawing != null && drawing.card < panels.size()) {
                UIElement port = panels.get(drawing.card).port(drawing.item);
                if (port != null) wire(ctx, port.getPositionX() + port.getSizeWidth(), port.getPositionY() + port.getSizeHeight() / 2, mouseX, mouseY, ARROW, -1);
            }
        }

        /** A curve from (sx, sy) out to the right and into (tx, ty) from the left, with two arrows running along it ({@code now} below 0: none). */
        private static void wire(IGUIContext ctx, float sx, float sy, float tx, float ty, ColorRectTexture colour, long now) {
            float bend = Math.max(30, Math.abs(tx - sx) / 2);
            int steps = (int) Math.max(12, (Math.abs(tx - sx) + Math.abs(ty - sy)) / 1.5f);
            for (int k = 0; k <= steps; k++) {
                float[] p = curve(sx, sy, tx, ty, bend, k / (float) steps);
                ctx.drawTexture(colour, p[0] - 1, p[1] - 1, 2, 2);
            }
            if (now < 0) return;
            for (float offset : new float[] {0, 0.5f}) {
                float t = ((now % ARROW_MS) / (float) ARROW_MS + offset) % 1f;
                float[] p = curve(sx, sy, tx, ty, bend, t), q = curve(sx, sy, tx, ty, bend, Math.min(1, t + 0.01f));
                float dx = q[0] - p[0], dy = q[1] - p[1], len = (float) Math.max(1e-3, Math.hypot(dx, dy));
                dx /= len;
                dy /= len;
                // An arrowhead pointing along the wire: two short strokes back from its tip.
                for (int k = 0; k <= 3; k++) {
                    ctx.drawTexture(ARROW, p[0] - dx * k - dy * k - 1, p[1] - dy * k + dx * k - 1, 2, 2);
                    ctx.drawTexture(ARROW, p[0] - dx * k + dy * k - 1, p[1] - dy * k - dx * k - 1, 2, 2);
                }
            }
        }

        /** The point at {@code t} on the cubic curve leaving (sx, sy) rightwards and entering (tx, ty) from the left. */
        private static float[] curve(float sx, float sy, float tx, float ty, float bend, float t) {
            float u = 1 - t, a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
            return new float[] {a * sx + b * (sx + bend) + c * (tx - bend) + d * tx, a * sy + b * sy + c * ty + d * ty};
        }
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
        private final Selector<ItemResource> designate = new Selector<>();
        /** One row per product the card has made, each with a port to wire it on from. */
        private final UIElement products = new UIElement().layout(l -> l.gapAll(2));
        private final java.util.Map<Item, UIElement> ports = new java.util.HashMap<>();
        /** What the product rows were built for, so they are rebuilt only when that changes. */
        private @Nullable Object productsFor;
        private final IGuiTexture[] ghosts = new IGuiTexture[ProcessCard.SLOTS];
        private final ItemResource[] ghostItems = new ItemResource[ProcessCard.SLOTS];

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
            designate.setCandidateUIProvider(UIElementProvider.text(what -> what == null
                    ? Component.translatable("logos.alchemy_table.designate") : what.getHoverName()));
            designate.layout(l -> l.flex(1));
            designate.setOnValueChanged(what -> {
                if (what != null) send(AlchemyCardPayload.designate(pos, index, what));
            });
            Button clear = new Button().setText(Component.translatable("logos.alchemy_table.clear_designations"));
            clear.setOnClick(e -> send(AlchemyCardPayload.clear(pos, index)));
            UIElement designateRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER));
            designateRow.addChildren(designate, clear);

            status.setId("card" + index + "_status");
            status.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true).textColor(STATUS_COLOUR));
            status.layout(l -> l.width(CARD_W - 8));
            addChildren(header, status, body, designateRow, products);
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
                ItemResource what = card.designated(i);
                if (what.equals(ghostItems[i])) continue;
                ghostItems[i] = what;
                ghosts[i] = what.isEmpty() ? IGuiTexture.EMPTY : new GuiTextureGroup(new ItemStackTexture(what.toStack()), new ColorRectTexture(GHOST));
                IGuiTexture ghost = ghosts[i];
                inputs[i].slotStyle(s -> s.slotOverlay(ghost).showSlotOverlayOnlyEmpty(true));
            }
            // Each distinct thing in storage, by its exact kind: every potion by name.
            List<ItemResource> stored = new ArrayList<>();
            for (int i = 0; i < table.items().size(); i++) {
                ItemResource r = table.items().getResource(i);
                if (!r.isEmpty() && !stored.contains(r)) stored.add(r);
            }
            if (!stored.equals(designate.getCandidates())) designate.setCandidates(stored);
            refreshProducts(card);
        }

        /** Where {@code item}'s wire starts, if the card lists it. */
        @Nullable UIElement port(Item item) {
            return ports.get(item);
        }

        /**
         * One row per product the card has made: the product, where it goes, and its port. Drag the
         * row onto a card or the product node to wire it there; right-click it to unwire it.
         */
        private void refreshProducts(ProcessCard card) {
            List<Item> made = card.made();
            List<Object> key = new ArrayList<>(List.of(made, table.cards().size()));
            for (Item p : made) key.add(card.route(p));
            for (ProcessCard c : table.cards()) key.add(c.process);
            if (Objects.equals(key, productsFor)) return;
            productsFor = key;
            for (UIElement child : new ArrayList<>(products.getChildren())) products.removeChild(child);
            ports.clear();
            for (Item product : made) {
                UIElement icon = new UIElement().layout(l -> l.width(16).height(16));
                icon.style(s -> s.background(new ItemStackTexture(product)));
                icon.setAllowHitTest(false);
                Label where = new Label();
                where.setText(targetName(card.route(product)));
                where.layout(l -> l.flex(1));
                where.setAllowHitTest(false);
                UIElement port = new UIElement().layout(l -> l.width(6).height(6));
                port.style(s -> s.background(PORT));
                port.setAllowHitTest(false);
                ports.put(product, port);
                UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER).widthPercent(100));
                row.setId("card" + index + "_product_" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(product).getPath());
                row.addChildren(icon, where, port);
                row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                    if (e.button == 1) send(AlchemyCardPayload.route(pos, index, product, ProcessCard.KEEP));
                    else row.startDrag(new WireDrag(index, product), new ItemStackTexture(product));
                });
                products.addChild(row);
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
