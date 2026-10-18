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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.IndexModifier;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The alchemy table's screen, one page drawn like the table itself (see {@code client.AlchemyArt}):
 * the four kinds of card above the bench top where process cards lie, the product crate beside it,
 * and beside that the storage ledger (raw-material and product areas, the switch, the fuel slot and
 * mana gauge, the tank, the player's inventory), which its ribbon folds away to give the bench the
 * page; the hotbar shows under the bench while the ledger is folded. A card on the canvas is drawn
 * as the apparatus that works its process; it shows its inputs (designated but empty slots show
 * their item faded), its outputs and its progress, lets one designate an item from storage, and
 * lists the products it has made. A product is piped on by dragging it onto another card or onto
 * the product crate at the canvas's right; unwired, it stays in the card. Pipes carry beads along
 * them. A card's title bar drags it about; × takes it off. What a card does is sent to the server as
 * {@link AlchemyCardPayload}s; the screen follows the table's state as the client sees it.
 */
public final class AlchemyTableUI {
    /** The board cards lie on; the screen shows as much of it as fits, and it scrolls and pans. */
    public static final int CANVAS_W = 600, CANVAS_H = 400, CARD_W = 140, CARD_H = 136;
    /** A card's width in the overview, where the board is drawn at half size. */
    private static final int COMPACT_W = 72;
    /** The product-area node on the canvas's right edge, which products can be wired to. */
    private static final int NODE_W = 52, NODE_H = 64;
    /** The gauges: the pale blue of the hourglass sand, and water blue. */
    private static final int MANA_COLOUR = 0xFF9FD3EE, FLUID_COLOUR = 0xFF3F76E4;
    /** A card's note on why it is not working. */
    private static final int STATUS_COLOUR = 0xFFFFB060;
    /** A designated, empty input slot shows its item under this veil of slot grey. */
    private static final int GHOST = 0xA08B8B8B;
    /** The storage ledger beside the board: nine slots, its margins and room for a scrollbar. */
    private static final int STORAGE_W = 9 * 18 + 22;
    /** Ink and paper for the ledger and the controls, shared with the spell workbench. */
    private static final Identifier MANUSCRIPT = Identifier.fromNamespaceAndPath(dev.logos.LogosMod.MODID, "lss/manuscript.lss");
    /** A card's title row, which its apparatus's brass nameplate sits under. */
    private static final int TITLE_H = 16;

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
    private record WireDrag(int card, ItemResource product) {}

    static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        Player player = holder.player;
        if (!(player.level().getBlockEntity(holder.pos) instanceof AlchemyTableBlockEntity table)) {
            return ModularUI.of(UI.of(new UIElement()), player);
        }
        // The board: the palette of apparatus above, the bench top (scrolling, with the product crate beside it),
        // and the hotbar below while the storage page is folded away.
        UIElement productsNode = productsNode();
        Canvas canvas = new Canvas(table, holder.pos, productsNode);
        ScrollerView view = new ScrollerView();
        view.setId("canvas_view");
        view.layout(l -> l.flex(1).heightPercent(100));
        view.style(s -> s.background(IGuiTexture.EMPTY));
        view.viewPort(v -> v.style(s -> s.background(IGuiTexture.EMPTY)));
        view.addScrollViewChild(canvas);
        canvas.pansIn(view);
        productsNode.style(s -> s.background(dev.logos.client.AlchemyArt.crate(ctx -> canvas.cratePipes(ctx, view))));
        UIElement work = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flex(1).minHeight(0).gapAll(10).alignItems(AlignItems.CENTER).widthPercent(100));
        work.addChildren(view, productsNode);
        UIElement hotbar = hotbar(player);
        UIElement processPage = new UIElement().layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(8).paddingTop(8));
        processPage.setId("process_page");
        // The overview: a button beside the palette, or Ctrl and the wheel over the board (down to step back, up to come close).
        Button zoom = new Button().setText(Component.translatable("logos.alchemy_table.overview"));
        zoom.setId("zoom_toggle");
        zoom.setOnClick(e -> canvas.setOverview(!canvas.overview()));
        dev.logos.client.AlchemyArt.paper(zoom);
        canvas.onOverview(on -> zoom.setText(Component.translatable(on ? "logos.alchemy_table.detail" : "logos.alchemy_table.overview")));
        view.addEventListener(UIEvents.MOUSE_WHEEL, e -> {
            if (!e.isCtrlDown()) return;
            canvas.setOverview(e.deltaY < 0);
            e.stopPropagation();
        }, true);
        UIElement palette = palette(table);
        palette.addChild(zoom);
        processPage.addChildren(palette, work, hotbar);
        canvas.takesDropsOn(processPage);

        // Storage, mana, the tank and the inventory, on a ledger page beside the board; its ribbon folds it away.
        // It scrolls when the window is too short to show it all.
        ScrollerView ledger = new ScrollerView();
        ledger.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        ledger.style(s -> s.background(IGuiTexture.EMPTY));
        ledger.viewPort(v -> v.style(s -> s.background(IGuiTexture.EMPTY)));
        ledger.addScrollViewChild(storage(table));
        UIElement storagePage = new UIElement();
        storagePage.setId("storage_page");
        storagePage.layout(l -> l.width(STORAGE_W).heightPercent(100).paddingAll(4).paddingTop(8).flexShrink(0));
        storagePage.style(s -> s.background(dev.logos.client.AlchemyArt.ledger()));
        storagePage.addChild(ledger);
        dev.logos.client.AlchemyArt.inked(storagePage);
        boolean[] storageOpen = {true};
        hotbar.setDisplay(false);
        UIElement storageTab = dev.logos.client.AlchemyArt.ribbon("logos.alchemy_table.tab_storage", () -> storageOpen[0], () -> {
            storageOpen[0] = !storageOpen[0];
            storagePage.setDisplay(storageOpen[0]);
            hotbar.setDisplay(!storageOpen[0]);
        });
        storageTab.setId("tab_storage");
        UIElement ribbons = new UIElement().layout(l -> l.width(16).heightPercent(100).paddingTop(16).flexShrink(0));
        ribbons.addChild(storageTab);

        UIElement root = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).heightPercent(100).paddingAll(14).gapAll(8));
        root.style(s -> s.background(dev.logos.client.AlchemyArt.bench()));
        root.addChildren(processPage, storagePage, ribbons);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(MANUSCRIPT)), player);
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
        node.style(s -> s.background(dev.logos.client.AlchemyArt.crate()));
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
        dev.logos.client.AlchemyArt.paper(clear);
        ProgressBar fluidGauge = gauge(() -> (float) table.fluid().getAmount(), AlchemyTableBlockEntity.TANK_CAPACITY, 9 * 18 - 66, FLUID_COLOUR);
        fluidGauge.setId("fluid_gauge");
        UIElement tankRow = row(tank, column(fluidText, row(fluidGauge, clear)));

        // The switch that runs or stops all the processes; a redstone signal stops them too.
        Button power = new Button().setText(Component.translatable("logos.alchemy_table.toggle"));
        power.setId("run_switch");
        power.setOnServerClick(e -> table.setEnabled(!table.enabled()));
        dev.logos.client.AlchemyArt.paper(power);
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
        UIElement column = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP).gapAll(4).alignItems(AlignItems.CENTER).widthPercent(100));
        for (Process p : Process.values()) {
            // A brass plate, as on the table's front, with the process's alchemical sign and name.
            UIElement chip = new UIElement().layout(l -> l.height(16).paddingAll(3).paddingLeft(5).paddingRight(5));
            chip.setId("palette_" + p.getSerializedName());
            chip.style(s -> s.background(dev.logos.client.AlchemyArt.plate(p)));
            Label name = new Label();
            name.setText(Component.literal(p.symbol + " ").append(p.displayName()));
            name.textStyle(s -> s.textColor(dev.logos.client.AlchemyArt.ENGRAVED).textShadow(false).adaptiveWidth(true));
            name.setAllowHitTest(false);
            chip.addChild(name);
            chip.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                if (table.cards().size() < AlchemyTableBlockEntity.MAX_CARDS) {
                    chip.startDrag(new NewCard(p), new TextTexture(p.symbol + " " + p.displayName().getString()));
                }
            });
            column.addChild(chip);
            // Calcination and purification are offered only while their block stands beside the table (hidden elements are not ticked, so the strip checks).
            if (!p.onTable()) column.addEventListener(UIEvents.TICK, e -> chip.setDisplay(table.hasStation(p)));
        }
        Label count = new Label();
        count.textStyle(t -> t.adaptiveWidth(true));
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
        /** The overview: the board at half size, each card a small apparatus showing what it holds and has made. */
        private boolean overview;
        private java.util.function.Consumer<Boolean> overviewChanged = on -> {};

        Canvas(AlchemyTableBlockEntity table, BlockPos pos, UIElement productsNode) {
            this.table = table;
            this.pos = pos;
            this.productsNode = productsNode;
            setId("canvas");
            layout(l -> l.width(CANVAS_W).height(CANVAS_H));
            style(s -> s.background(dev.logos.client.AlchemyArt.benchTop()));

            hint.setText(Component.translatable("logos.alchemy_table.card_hint"));
            hint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
            hint.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(8).top(8).width(220));
            hint.setAllowHitTest(false);
            addChild(hint);

            for (int i = 0; i < AlchemyTableBlockEntity.MAX_CARDS; i++) {
                panels.add(new CardPanel(table, pos, i, () -> overview, () -> setOverview(false)));
            }
            panels.forEach(this::addChild);
            // Hidden elements are not ticked, so the always-shown canvas keeps the panels up to date.
            addEventListener(UIEvents.TICK, e -> {
                panels.forEach(CardPanel::refresh);
                hint.setDisplay(table.cards().isEmpty());
            });
        }

        boolean overview() {
            return overview;
        }

        /** How large the board is drawn: whole, or half in the overview. Cards keep their places on the table either way. */
        private float scale() {
            return overview ? 0.5f : 1f;
        }

        /** Switches between the whole board and the overview, telling {@code onOverview} (the toggle's label). */
        void setOverview(boolean on) {
            if (on == overview) return;
            overview = on;
            float s = scale();
            layout(l -> l.width(CANVAS_W * s).height(CANVAS_H * s));
            panels.forEach(p -> p.setOverview(on));
            overviewChanged.accept(on);
        }

        void onOverview(java.util.function.Consumer<Boolean> changed) {
            overviewChanged = changed;
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
                // Dropped points are on the board as drawn; the table keeps cards in whole-board places.
                float s = scale(), x = e.x - getPositionX(), y = e.y - getPositionY();
                if (dragged instanceof NewCard n) send(AlchemyCardPayload.add(pos, n.process, (int) (x / s) - 20, (int) (y / s) - 6));
                else if (dragged instanceof MovedCard m) send(AlchemyCardPayload.move(pos, m.index, (int) ((x - m.dx) / s), (int) ((y - m.dy) / s)));
                else if (dragged instanceof WireDrag w) send(AlchemyCardPayload.route(pos, w.card, w.product, wireTarget(w.card, e.x, e.y)));
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
            pipes(ctx, false);
            if (drawing != null && drawing.card < panels.size()) {
                UIElement port = panels.get(drawing.card).port(drawing.product);
                if (port != null) dev.logos.client.AlchemyArt.pipe(ctx, port.getPositionX() + port.getSizeWidth(), port.getPositionY() + port.getSizeHeight() / 2,
                        mouseX, mouseY, dev.logos.client.AlchemyArt.Pipe.LAYING, 0, -1);
            }
        }

        /**
         * The stretch of each pipe to the product crate beyond the board's edge: the board is clipped
         * to {@code view}, so the crate draws this part, from the view's edge to its funnel.
         */
        void cratePipes(IGUIContext ctx, UIElement view) {
            float left = view instanceof ScrollerView scroller && scroller.verticalScroller.isDisplayed()
                    ? scroller.verticalScroller.getPositionX() : view.getPositionX() + view.getSizeWidth();
            dev.logos.client.AlchemyArt.clipped(ctx, left, view.getPositionY(), productsNode.getPositionX() - left, view.getSizeHeight(),
                    c -> pipes(c, true));
        }

        /** The laid pipes: between apparatus and to the crate, or ({@code crateOnly}) only those to the crate. */
        private void pipes(IGUIContext ctx, boolean crateOnly) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < Math.min(panels.size(), table.cards().size()); i++) {
                ProcessCard card = table.cards().get(i);
                CardPanel from = panels.get(i);
                for (ItemResource product : card.made()) {
                    int target = card.route(product);
                    UIElement port = from.port(product);
                    if (port == null || target == ProcessCard.KEEP) continue;
                    float sx = port.getPositionX() + port.getSizeWidth(), sy = port.getPositionY() + port.getSizeHeight() / 2;
                    // Products run to the crate through brass, and between apparatus through glass, coloured by what the source works.
                    int carried = dev.logos.client.AlchemyArt.liquid(card.process);
                    if (target == ProcessCard.PRODUCTS) {
                        dev.logos.client.AlchemyArt.pipe(ctx, sx, sy, productsNode.getPositionX() - 5, productsNode.getPositionY() + NODE_H / 2f,
                                dev.logos.client.AlchemyArt.Pipe.BRASS, carried, now);
                    } else if (!crateOnly && target >= 0 && target < table.cards().size() && target != i) {
                        CardPanel to = panels.get(target);
                        dev.logos.client.AlchemyArt.pipe(ctx, sx, sy, to.getPositionX(), to.getPositionY() + 10,
                                dev.logos.client.AlchemyArt.Pipe.GLASS, carried, now);
                    }
                }
            }
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
        private final java.util.Map<ItemResource, UIElement> ports = new java.util.HashMap<>();
        /** What the product rows were built for, so they are rebuilt only when that changes. */
        private @Nullable Object productsFor;
        private final ProgressBar progress = new ProgressBar().setRange(0, ProcessCard.CRAFT_TICKS);
        /** The process the card's apparatus is drawn for, so it is redrawn only when another card takes the place. */
        private @Nullable Process drawnAs;
        private final IGuiTexture[] ghosts = new IGuiTexture[ProcessCard.SLOTS];
        private final ItemResource[] ghostItems = new ItemResource[ProcessCard.SLOTS];
        /** Whether the board is in its overview, and how to leave it (double-clicking a card). */
        private final java.util.function.BooleanSupplier overview;
        /** The full card's parts below its title: shown on the whole board. */
        private final List<UIElement> detail = new ArrayList<>();
        /** The overview's summary: what the card holds or wants, and what it has made, each as icons with counts. */
        private final UIElement summary = new UIElement().layout(l -> l.gapAll(2).minHeight(34));
        private final UIElement materials = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP).gapAll(1));
        private final UIElement madeIcons = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP).gapAll(2));
        /** In the overview, a mark beside the title when the card is not working; its tooltip says why. */
        private final Label flag = new Label();
        private final java.util.Map<ItemResource, UIElement> miniPorts = new java.util.HashMap<>();
        private @Nullable Object summaryFor;

        CardPanel(AlchemyTableBlockEntity table, BlockPos pos, int index, java.util.function.BooleanSupplier overview, Runnable leaveOverview) {
            this.table = table;
            this.pos = pos;
            this.index = index;
            this.overview = overview;
            addEventListener(UIEvents.DOUBLE_CLICK, e -> {
                if (overview.getAsBoolean()) leaveOverview.run();
            });
            setId("card" + index);
            layout(l -> l.positionType(TaffyPosition.ABSOLUTE).width(CARD_W).paddingAll(4).gapAll(3));

            // Title bar, engraved on the apparatus's brass nameplate: drag it to move the card; × takes the card off.
            title.setId("card" + index + "_title");
            title.layout(l -> l.flex(1).marginLeft(2));
            title.textStyle(s -> s.textColor(dev.logos.client.AlchemyArt.ENGRAVED).textShadow(false));
            title.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                float dx = e.x - getPositionX(), dy = e.y - getPositionY();
                title.startDrag(new MovedCard(index, dx, dy), new ColorRectTexture(0x40FFFFFF));
            });
            Button remove = new Button().setText("×");
            remove.setId("card" + index + "_remove");
            remove.setOnClick(e -> send(AlchemyCardPayload.remove(pos, index)));
            dev.logos.client.AlchemyArt.paper(remove);
            progress.label(l -> l.setText(Component.empty()));
            progress.bind(DataBindingBuilder.floatValS2C(() -> {
                ProcessCard c = card();
                return c == null ? 0f : (float) c.progress();
            }).build());
            progress.layout(l -> l.width(36).height(12));
            flag.setText(Component.literal("!"));
            flag.textStyle(s -> s.textColor(STATUS_COLOUR));
            flag.setDisplay(false);
            UIElement header = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER));
            header.addChildren(title, flag, progress, remove);
            title.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.HOVER_ROLL));
            title.layout(l -> l.minWidth(0));

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
            dev.logos.client.AlchemyArt.paper(designate);
            Button clear = new Button().setText(Component.translatable("logos.alchemy_table.clear_designations"));
            clear.setOnClick(e -> send(AlchemyCardPayload.clear(pos, index)));
            dev.logos.client.AlchemyArt.paper(clear);
            UIElement designateRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER));
            designateRow.addChildren(designate, clear);

            status.setId("card" + index + "_status");
            status.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true).textColor(STATUS_COLOUR));
            status.layout(l -> l.width(CARD_W - 8));
            summary.addChildren(materials, madeIcons);
            summary.setDisplay(false);
            detail.addAll(List.of(body, designateRow, products));
            addChildren(header, status, body, designateRow, products, summary);
            setDisplay(false);
        }

        /** The full card on the whole board, or the small apparatus with its summary in the overview. */
        void setOverview(boolean on) {
            layout(l -> l.width(on ? COMPACT_W : CARD_W).paddingAll(on ? 3 : 4));
            progress.setDisplay(!on);
            detail.forEach(part -> part.setDisplay(!on));
            summary.setDisplay(on);
            summaryFor = null;
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
            boolean small = overview.getAsBoolean();
            float scale = small ? 0.5f : 1f;
            layout(l -> l.left(card.x() * scale).top(card.y() * scale));
            if (card.process != drawnAs) {
                drawnAs = card.process;
                IGuiTexture apparatus = dev.logos.client.AlchemyArt.vessel(card.process, progress::getNormalizedValue, TITLE_H);
                style(s -> s.background(apparatus));
                int liquid = dev.logos.client.AlchemyArt.liquid(card.process);
                progress.bar(b -> b.style(s -> s.background(new ColorRectTexture(liquid))));
            }
            dev.logos.client.AlchemyArt.dressList(designate);
            title.setText(Component.literal(card.process.symbol + " ").append(card.process.displayName()));
            status.setText(card.status());
            boolean idle = !card.status().getString().isEmpty();
            status.setDisplay(idle && !small);
            flag.setDisplay(idle && small);
            if (idle) flag.style(st -> st.tooltips(card.status()));
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
            if (small) refreshSummary(card);
        }

        /** Where {@code item}'s wire starts, if the card lists it: its row, or its icon in the overview. */
        @Nullable UIElement port(ItemResource product) {
            return overview.getAsBoolean() ? miniPorts.get(product) : ports.get(product);
        }

        /**
         * The overview's two rows. Materials: each thing the card holds or has designated, with how
         * many it holds; designated but missing ones faded at 0. Products: each thing it has made, with
         * how many wait in its outputs, and a valve to pipe it on from (drag; right-click unwires).
         */
        private void refreshSummary(ProcessCard card) {
            java.util.Map<ItemResource, int[]> held = new java.util.LinkedHashMap<>();
            for (int i = 0; i < ProcessCard.SLOTS; i++) {
                ItemResource in = card.inputs().getResource(i), wanted = card.designated(i);
                if (!in.isEmpty()) held.computeIfAbsent(in, k -> new int[1])[0] += card.inputs().getAmountAsInt(i);
                else if (!wanted.isEmpty()) held.computeIfAbsent(wanted, k -> new int[1]);
            }
            List<ItemResource> made = card.made();
            java.util.Map<ItemResource, Integer> waiting = new java.util.HashMap<>();
            for (int i = 0; i < ProcessCard.SLOTS; i++) {
                ItemResource out = card.outputs().getResource(i);
                if (!out.isEmpty()) waiting.merge(out, card.outputs().getAmountAsInt(i), Integer::sum);
            }
            List<Object> key = new ArrayList<>();
            held.forEach((r, n) -> key.add(List.of(r, n[0])));
            key.add(made);
            key.add(waiting);
            if (key.equals(summaryFor)) return;
            summaryFor = key;
            for (UIElement child : new ArrayList<>(materials.getChildren())) materials.removeChild(child);
            held.forEach((r, n) -> materials.addChild(token(r, n[0], n[0] == 0)));
            for (UIElement child : new ArrayList<>(madeIcons.getChildren())) madeIcons.removeChild(child);
            miniPorts.clear();
            java.util.Map<Item, Integer> seen = new java.util.HashMap<>();
            for (ItemResource product : made) {
                UIElement icon = token(product, waiting.getOrDefault(product, 0), false);
                UIElement port = new UIElement().layout(l -> l.width(5).height(5));
                port.style(st -> st.background(dev.logos.client.AlchemyArt.valve()));
                port.setAllowHitTest(false);
                miniPorts.put(product, port);
                UIElement piece = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(1));
                int nth = seen.merge(product.getItem(), 1, Integer::sum);
                String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(product.getItem()).getPath();
                piece.setId("card" + index + "_mini_" + path + (nth > 1 ? "_" + nth : ""));
                piece.addChildren(icon, port);
                piece.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                    if (e.button == 1) send(AlchemyCardPayload.route(pos, index, product, ProcessCard.KEEP));
                    else piece.startDrag(new WireDrag(index, product), new ItemStackTexture(product.toStack()));
                });
                madeIcons.addChild(piece);
            }
        }

        /** An item's icon with a count in its corner, as a slot shows it; faded when the card lacks it. */
        private static UIElement token(ItemResource item, int count, boolean missing) {
            UIElement token = new UIElement().layout(l -> l.width(16).height(16));
            IGuiTexture icon = new ItemStackTexture(item.toStack());
            IGuiTexture shown = missing ? new GuiTextureGroup(icon, new ColorRectTexture(GHOST)) : icon;
            token.style(st -> st.background(shown).tooltips(item.getHoverName()));
            Label n = new Label();
            n.setText(Component.literal(String.valueOf(count)));
            n.textStyle(t -> t.adaptiveWidth(true).textColor(missing ? STATUS_COLOUR : 0xFFFFFFFF));
            n.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).right(-1).bottom(-2));
            n.setAllowHitTest(false);
            token.addChild(n);
            return token;
        }

        /**
         * One row per product the card has made: the product, where it goes, and its port. Drag the
         * row onto a card or the product node to wire it there; right-click it to unwire it.
         */
        private void refreshProducts(ProcessCard card) {
            List<ItemResource> made = card.made();
            List<Object> key = new ArrayList<>(List.of(made, table.cards().size()));
            for (ItemResource p : made) key.add(card.route(p));
            for (ProcessCard c : table.cards()) key.add(c.process);
            if (Objects.equals(key, productsFor)) return;
            productsFor = key;
            for (UIElement child : new ArrayList<>(products.getChildren())) products.removeChild(child);
            ports.clear();
            java.util.Map<Item, Integer> seen = new java.util.HashMap<>();
            for (ItemResource product : made) {
                UIElement icon = new UIElement().layout(l -> l.width(16).height(16));
                icon.style(s -> s.background(new ItemStackTexture(product.toStack())));
                icon.setAllowHitTest(false);
                Label where = new Label();
                where.setText(targetName(card.route(product)));
                where.layout(l -> l.flex(1));
                where.setAllowHitTest(false);
                UIElement port = new UIElement().layout(l -> l.width(6).height(6));
                port.style(s -> s.background(dev.logos.client.AlchemyArt.valve()));
                port.setAllowHitTest(false);
                ports.put(product, port);
                UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(3).alignItems(AlignItems.CENTER).widthPercent(100));
                // One id per row: the item, and for a second kind of the same item (another potion) a number.
                int nth = seen.merge(product.getItem(), 1, Integer::sum);
                String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(product.getItem()).getPath();
                row.setId("card" + index + "_product_" + path + (nth > 1 ? "_" + nth : ""));
                row.addChildren(icon, where, port);
                row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                    if (e.button == 1) send(AlchemyCardPayload.route(pos, index, product, ProcessCard.KEEP));
                    else row.startDrag(new WireDrag(index, product), new ItemStackTexture(product.toStack()));
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
