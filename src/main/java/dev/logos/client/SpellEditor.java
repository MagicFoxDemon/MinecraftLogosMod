package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphEditorView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.ModelElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.NodeCommands;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.Model;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wiget.StickyNoteModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wiget.PlacematModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wire.WireModel;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.EmitterGraphs;
import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.LogosNode;
import dev.logos.graph.SpellGraph;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.net.EmitterEditPayload;
import dev.logos.net.InscribePayload;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The spell workbench: a node-graph canvas on the left (typed ports, wires snap only when the
 * slot shapes agree) and, on the right, an overview of what is drawn: whether it holds, roughly what
 * one cast costs against what it injects, and its intent — inferred, or set here by the player.
 */
public final class SpellEditor {
    /** Ink and paper for the runes on the canvas; layered over the toolkit theme. */
    static final Identifier MANUSCRIPT = Identifier.fromNamespaceAndPath(LogosMod.MODID, "lss/manuscript.lss");
    /** Where the inscription table holding the page stands; saving writes into that page. */
    private final BlockPos table;
    /** Grade of the page on the table: the editor warns when the spell injects more than it holds. */
    private final PageTier tier;
    private final GraphEditorView editor = new GraphEditorView(SpellGraphView::new);
    private final Label status = new Label();
    private final Label overview = new Label();
    private final Selector<SpecialNodes.IntentChoice> intent = new Selector<>();
    /** The wax seal on the inscribe button: grey while the drawing does not hold. */
    private final ManuscriptControls.Seal seal = new ManuscriptControls.Seal(() -> this.graph != null && this.graph.result().ok());
    /** Set while the selector is being matched to the graph, so that does not count as the player choosing. */
    private boolean syncingIntent;
    /** Set while 试射 saves, so the save asks the emitter to cast once. */
    private boolean fireOnSave;
    private SpellGraph graph;
    /** Every rune, to drag onto the canvas; shares the right of the page with the overview. */
    private final RuneLibrary library = new RuneLibrary(this::placeInView);
    /** Which page is open beside the canvas: the overview, the rune library, or neither. */
    private Shelf shelf = Shelf.OVERVIEW;
    private UIElement overviewPage;

    private enum Shelf { OVERVIEW, LIBRARY, NONE }
    /** Ticks until the canvas is framed around a freshly loaded graph (node sizes need a layout pass first). */
    private int fitIn = -1;

    /** A spell canvas needs navigation, but not the toolkit's variable and appearance panels. */
    private static final class SpellGraphView extends GraphView {
        private SpellGraphView() {
            header.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
            graphView.graphViewStyle(s -> s.gridLineColor(0x183E2918).gridAccentColor(0x303E2918));
            SpellNavigation navigation = new SpellNavigation(this);
            navigation.setId("spell_navigation");
            navigation.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                    .right(8).bottom(28).width(64).height(64));
            navigation.style(s -> s.backgroundTexture(new ColorRectTexture(0x5033261A)));
            canvas.addChild(navigation);
            graphView.addEventListener(UIEvents.DRAG_PERFORM, event -> {
                if (event.dragHandler.getDraggingObject() instanceof RuneLibrary.Dragged dragged
                        && graphView.isMouseOverContent(event.x, event.y)) {
                    place(dragged.item(), event.x, event.y);
                }
            });
        }

        /** Places a rune where it was dropped, through the toolkit's create-node command so it can be undone. */
        void place(NodeModelLibraryItem item, float screenX, float screenY) {
            Vector2f at = snapPosition(getContentViewContainer().worldToLocalLayoutOffset(new Vector2f(screenX, screenY)));
            dispatchCommand(new NodeCommands.CreateNodeCommand().onGraph(item, at, null));
        }

        @Override
        public ModelElement createAndAddModelElement(Model model) {
            boolean rune = model instanceof NodeModel && model instanceof ICustomNodeModel custom && custom.getNode() instanceof LogosNode;
            if (!rune && !(model instanceof WireModel) && !(model instanceof StickyNoteModel) && !(model instanceof PlacematModel)) {
                return super.createAndAddModelElement(model);
            }
            ModelElement existing = getModelElement(model);
            if (existing != null) return existing;
            ModelElement element = rune
                    ? new ManuscriptNode((NodeModel) model, (LogosNode) ((ICustomNodeModel) model).getNode())
                    : model instanceof WireModel wire
                    ? new ManuscriptWire(wire)
                    : model instanceof StickyNoteModel note
                    ? new SpellAnnotations.PaperNote(note)
                    : new SpellAnnotations.InkRegion((PlacematModel) model);
            if (addElement(element)) element.doCompleteUpdate();
            return element;
        }

        /** The overview on the right already reports what the log footer would; the footer stays hidden. */
        @Override
        protected void updateGraphLogFooter() {
            super.updateGraphLogFooter();
            for (UIElement child : canvas.getChildren()) {
                if (child.hasClass("__node-graph-view_log-footer__")) Style.importantPipeline(child.getLayout(), l -> l.display(TaffyDisplay.NONE));
            }
        }

        @Override
        protected void initPanels() {
            // Leave the dock empty; navigation is attached directly to the canvas instead.
        }

        @Override
        public GraphView loadGraph(Graph graph) {
            super.loadGraph(graph);
            if (graph == null) return this;
            // Library labels otherwise come from the registration ID, not the node's localized name.
            var orphan = GraphNodeCreationData.ofOrphan(graph.graphModel);
            itemLibrary.getAllItems().forEach(item -> {
                if (item instanceof NodeModelLibraryItem entry
                        && entry.createNode(orphan) instanceof ICustomNodeModel model
                        && model.getNode() instanceof LogosNode node) {
                    Component name = Component.translatableWithFallback(node.nameKey(), node.word());
                    item.setDisplayName(name);
                    item.setSearchableName(name.getString() + " " + node.word() + " " + item.getSearchableName());
                }
            });
            itemLibrary.nodeTree.reloadList();
            return this;
        }
    }

    /**
     * Set when the page lies in a spell emitter at {@link #table} rather than on an inscription table:
     * the emitter's node stands in for the invocation, and what it has no place for is kept here.
     */
    private final @org.jspecify.annotations.Nullable SpellEmitterBlockEntity emitter;
    private EmitterGraphs.Kept kept = EmitterGraphs.Kept.DEFAULT;

    private SpellEditor(BlockPos table, PageTier tier, @org.jspecify.annotations.Nullable SpellEmitterBlockEntity emitter) {
        this.table = table;
        this.tier = tier;
        this.emitter = emitter;
    }

    /** Opens the editor for the page on the inscription table at {@code table}, restoring its drawing (or its source). */
    public static void open(BlockPos table, ItemStack page) {
        PageTier tier = SpellPages.tier(page);
        SpellEditor e = new SpellEditor(table, tier == null ? PageTier.BASIC : tier, null);
        e.load(initialGraph(page));
        Minecraft.getInstance().setScreen(new ModularUIScreen(e.createUI(), Component.literal("Logos")));
    }

    /** Opens the editor on the page in the spell emitter at {@code pos}, the emitter's settings in its node. */
    public static void openEmitter(BlockPos pos, SpellEmitterBlockEntity emitter) {
        PageTier tier = SpellPages.tier(emitter.page());
        SpellEditor e = new SpellEditor(pos, tier == null ? PageTier.BASIC : tier, emitter);
        e.load(initialGraph(emitter.page()));
        Minecraft.getInstance().setScreen(new ModularUIScreen(e.createUI(), Component.translatable("block.logos.spell_emitter")));
    }

    private static SpellGraph initialGraph(ItemStack wand) {
        CompoundTag tag = wand.get(LogosMod.SPELL_GRAPH.get());
        if (tag != null) {
            try {
                SpellGraph g = new SpellGraph();
                g.graphModel.deserialize(TagValueInput.create(ProblemReporter.DISCARDING, Platform.getFrozenRegistry(), tag));
                return g;
            } catch (RuntimeException ex) {
                LogosMod.LOGGER.warn("Could not restore spell graph, rebuilding from source", ex);
            }
        }
        String src = wand.get(LogosMod.SPELL_SOURCE.get());
        if (src != null) {
            try {
                return GraphBuilder.build(Compiler.compile(src));
            } catch (LogosError ignored) {
                // fall through to an empty graph
            }
        }
        return GraphBuilder.empty();
    }

    private void load(SpellGraph g) {
        if (emitter != null) {
            // A template or a cleared page takes the trigger already set; the emitter's own one the first time.
            SpecialNodes.EmitterRoot current = graph == null ? null : EmitterGraphs.root(graph, SpecialNodes.EmitterRoot.class);
            kept = EmitterGraphs.toEmitter(g, current == null ? emitter.mode() : current.mode(), current == null ? emitter.interval() : current.interval());
        }
        graph = g;
        graph.addListener(this::refresh);
        editor.loadGraph(graph, this::send);
        fitIn = 3;
        refresh();
    }

    private ModularUI createUI() {
        editor.setId("spell_canvas");
        editor.layout(l -> l.flex(2).minWidth(0).heightPercent(100));
        editor.addEventListener(UIEvents.TICK, ev -> {
            if (fitIn > 0 && --fitIn == 0) editor.getCurrentView().fitGraphChildren();
        });

        Selector<String> templates = new Selector<>();
        templates.setId("templates");
        templates.setCandidateUIProvider(UIElementProvider.text(id -> Component.translatableWithFallback("logos.example." + id, id)));
        templates.setCandidates(new ArrayList<>(Examples.ALL.keySet()));
        templates.setSelected("fireball", false);
        templates.layout(l -> l.widthPercent(100));

        Button loadTemplate = new Button().setText("载入模板");
        loadTemplate.setId("load_template");
        loadTemplate.setOnClick(ev -> {
            String id = templates.getValue();
            if (id != null && Examples.ALL.containsKey(id)) load(GraphBuilder.build(Compiler.compile(Examples.ALL.get(id))));
        });
        Button clear = new Button().setText("清空");
        clear.setOnClick(ev -> load(GraphBuilder.empty()));
        Button inscribe = new Button().setText(emitter == null ? "写入术式载页" : "写入发射器");
        inscribe.setId("inscribe");
        inscribe.setOnClick(ev -> inscribe());
        // 试射: save, then cast once as soon as the emitter is charged.
        Button testFire = new Button().setText("试射");
        testFire.setId("test_fire");
        testFire.setOnClick(ev -> {
            fireOnSave = true;
            inscribe();
            fireOnSave = false;
        });

        status.setId("status");
        overview.setId("overview");
        for (Label l : List.of(status, overview)) {
            l.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true).textColor(0xFF392719).textShadow(false));
            l.layout(x -> x.widthPercent(100));
        }
        // The intent a lifeform files this spell under: inferred unless the player picks one.
        intent.setId("intent");
        intent.setCandidateUIProvider(UIElementProvider.text(c -> Component.translatable("dev.logos.graph.SpecialNodes$IntentChoice." + c.name())));
        intent.setCandidates(List.of(SpecialNodes.IntentChoice.values()));
        intent.setSelected(SpecialNodes.IntentChoice.AUTO, false);
        intent.layout(l -> l.widthPercent(100));
        intent.setOnValueChanged(choice -> {
            if (syncingIntent || choice == null) return;
            SpecialNodes.SpellRoot root = root();
            if (root == null) return;
            root.setOption(SpecialNodes.SpellRoot.INTENT, choice);
            graph.invalidate();
        });

        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).widthPercent(100));
        row.addChildren(loadTemplate, clear);

        UIElement side = new UIElement()
                .layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(5));
        side.setId("spell_details");
        overviewPage = side;
        UIElement details = new UIElement().layout(l -> l.widthPercent(100).gapAll(5).paddingAll(6));
        details.addChildren(
                new Label().setText(Component.literal(emitter == null ? "☉ Logos 术式工坊" : "☉ 术式发射器 · 载页")),
                new Label().setText(Component.literal("模板")),
                templates,
                row,
                status,
                new Label().setText(Component.literal("☷ 总览")));
        // The emitter casts on its own trigger; the intent a lifeform reads stays as the page had it.
        if (emitter == null) details.addChildren(new Label().setText(Component.literal("意图（魔力生命体据此决定何时施放）")), intent);
        details.addChild(overview);
        for (UIElement child : details.getChildren()) {
            if (child instanceof Label label) {
                label.layout(l -> l.widthPercent(100));
                label.textStyle(s -> s.textColor(0xFF392719).textShadow(false)
                        .textWrap(TextWrap.WRAP).adaptiveHeight(true));
            }
        }
        ScrollerView detailsView = new ScrollerView();
        detailsView.setId("spell_details_scroll");
        detailsView.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        detailsView.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        detailsView.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        detailsView.addScrollViewChild(details);
        inscribe.layout(l -> l.widthPercent(100).flexShrink(0));
        seal.apply(inscribe);
        ManuscriptControls.paper(loadTemplate);
        ManuscriptControls.paper(clear);
        ManuscriptControls.paper(templates);
        ManuscriptControls.paper(intent);
        side.addChildren(detailsView, inscribe);
        if (emitter != null) {
            testFire.layout(l -> l.widthPercent(100).flexShrink(0));
            ManuscriptControls.paper(testFire);
            side.addChild(testFire);
        }

        UIElement root = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW)
                .widthPercent(100).heightPercent(100).paddingAll(28).gapAll(8));
        root.setId("spell_editor_background");
        SpellEditorBackground parchment = new SpellEditorBackground();
        // The toolkit builds toolbar parts and menus on the fly; they are dressed as the page is drawn, before their first frame.
        GuiTexture page = (context, x, y, width, height) -> {
            parchment.draw(context, x, y, width, height);
            ManuscriptControls.dressToolbar(editor.getCurrentView().header);
            ManuscriptControls.dressMenus(root);
            ManuscriptControls.dressList(templates);
            ManuscriptControls.dressList(intent);
        };
        root.style(s -> s.backgroundTexture(page));
        library.setDisplay(TaffyDisplay.NONE);
        // Bookmark ribbons on the page's edge: each opens its page and shuts the other; clicking the open one shuts it.
        UIElement ribbons = new UIElement().layout(l -> l.width(16).heightPercent(100).gapAll(4).paddingTop(16));
        ribbons.setId("spell_shelf_tabs");
        UIElement overviewTab = ManuscriptControls.ribbon("logos.editor.overview", () -> shelf == Shelf.OVERVIEW, () -> toggle(Shelf.OVERVIEW));
        overviewTab.setId("tab_overview");
        UIElement libraryTab = ManuscriptControls.ribbon("logos.editor.library", () -> shelf == Shelf.LIBRARY, () -> toggle(Shelf.LIBRARY));
        libraryTab.setId("tab_library");
        ribbons.addChildren(overviewTab, libraryTab);
        root.addChildren(editor, side, library, ribbons);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(MANUSCRIPT)));
    }

    /** Opens {@code page} beside the canvas and shuts the other; asking for the open page again shuts it, giving the canvas the whole width. */
    private void toggle(Shelf page) {
        shelf = shelf == page ? Shelf.NONE : page;
        overviewPage.setDisplay(shelf == Shelf.OVERVIEW ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        library.setDisplay(shelf == Shelf.LIBRARY ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        if (shelf == Shelf.LIBRARY) library.fill(editor.getCurrentView());
    }

    /** A double-clicked library rune lands in the middle of what the canvas shows. */
    private void placeInView(NodeModelLibraryItem item) {
        if (!(editor.getCurrentView() instanceof SpellGraphView view)) return;
        var canvas = view.graphView;
        view.place(item, canvas.getPositionX() + canvas.getSizeWidth() / 2, canvas.getPositionY() + canvas.getSizeHeight() / 2);
    }

    private void refresh() {
        GraphCompiler.Result r = graph.result();
        if (r.ok()) {
            var s = r.spell();
            StringBuilder sb = new StringBuilder("✓ 《" + s.name() + "》成立\n");
            sb.append("注入魔力 ").append(Node.fmt(s.mana())).append(" · 稳定度 ").append(Node.fmt(s.stability())).append('\n');
            if (s.mana() > tier.capacity()) {
                sb.append(emitter == null ? "⚠ 台上的载页只能承载 " : "⚠ 发射器里的载页只能承载 ").append(tier.capacity())
                        .append(" 魔力：可以照常写入，施放时只注入 ").append(tier.capacity())
                        .append(emitter != null ? "（发射器没有焚式）" : s.immolate() ? "（持焚式法杖可放大上限，载页焚毁）" : "（已关闭焚式）").append('\n');
            }
            for (String note : s.notes()) sb.append("∴ ").append(note).append('\n');
            status.setText(Component.literal(sb.toString().strip()));
        } else {
            status.setText(Component.literal("✗ " + r.error()));
        }
        String emitterInfo = emitter == null ? "" : emitterInfo(r.ok() ? r.spell() : null);
        overview.setText(Component.literal((r.ok() ? overview(r.spell()) + (emitter == null ? "" : "\n\n") : "") + emitterInfo));
        SpecialNodes.SpellRoot root = root();
        SpecialNodes.IntentChoice chosen = root == null ? null : root.option(SpecialNodes.SpellRoot.INTENT, SpecialNodes.IntentChoice.class);
        syncingIntent = true;
        intent.setValue(chosen == null ? SpecialNodes.IntentChoice.AUTO : chosen, false);
        syncingIntent = false;
    }

    private SpecialNodes.@org.jspecify.annotations.Nullable SpellRoot root() {
        for (var n : graph.getNodes()) if (n instanceof SpecialNodes.SpellRoot r) return r;
        return null;
    }

    /** Mana against the estimate, reach and intent, in the player's terms; the source itself is not shown. */
    private static String overview(dev.logos.lang.Spell s) {
        var p = s.profile();
        StringBuilder sb = new StringBuilder();
        double left = s.mana() - p.estimate();
        sb.append("✦ 一次施放估计约需 ").append(Math.round(p.estimate())).append(" 魔力（注入 ").append(Node.fmt(s.mana())).append("）\n");
        if (left < 0) sb.append("⚠ 估计比注入的多 ").append(Math.round(-left)).append("：术式可能在中途魔力耗尽而熄灭\n");
        else if (left > Math.max(5, s.mana() * 0.5)) sb.append("∴ 估计会剩下约 ").append(Math.round(left)).append("，自然结束时化为游离魔力，可以少注入一些\n");
        else sb.append("∴ 注入大致合适\n");
        sb.append("⚑ 意图：").append(p.intentsZh()).append(p.declared() ? "（已指定）" : "（推测）").append('\n');
        if (p.range() > 0) sb.append("➶ 作用距离约 ").append(Math.round(p.range())).append(" 格\n");
        if (!p.assumptions().isEmpty()) {
            sb.append("\n估算依据：\n");
            for (String a : p.assumptions()) sb.append(" · ").append(a).append('\n');
        }
        return sb.toString().strip();
    }

    /** The emitter's store, how long one charge takes, and what its trigger does, as set on its node. */
    private String emitterInfo(dev.logos.lang.@org.jspecify.annotations.Nullable Spell s) {
        StringBuilder sb = new StringBuilder("⚙ 发射器魔力 ").append((int) emitter.mana()).append(" / ").append((int) SpellEmitterBlockEntity.MAX_MANA)
                .append("（收集器对准它，或用灌魔补充）\n");
        if (s != null) {
            double in = Math.min(s.mana(), tier.capacity());
            double secs = dev.logos.runtime.Charging.ticks(in, SpellEmitterBlockEntity.FLOW, SpellEmitterBlockEntity.SURGE) / 20.0;
            sb.append("⌛ 每次注入 ").append(Node.fmt(in)).append("，蓄满约 ").append(String.format("%.1f", secs)).append(" 秒\n");
        }
        SpecialNodes.EmitterRoot node = EmitterGraphs.root(graph, SpecialNodes.EmitterRoot.class);
        SpecialNodes.EmitterMode mode = node == null ? emitter.mode() : node.mode();
        int every = node == null ? emitter.interval() : node.interval();
        sb.append("⚑ ").append(switch (mode) {
            case PULSE -> "红石脉冲：收到信号时施放一次（蓄满后）";
            case HOLD -> "红石持续：有信号期间，每次蓄满就施放";
            case TIMED -> "定时：每 " + Math.clamp(every, 1, SpellEmitterBlockEntity.MAX_INTERVAL) + " 秒施放一次，每次另耗 " + Node.fmt(SpellEmitterBlockEntity.AUTO_COST) + " 魔力";
            case FULL -> "满能即放：每次蓄满就施放，每次另耗 " + Node.fmt(SpellEmitterBlockEntity.AUTO_COST) + " 魔力";
        });
        sb.append("\n☉ 从发射器中心施放，没指定方向时朝它面对的方向");
        return sb.toString();
    }

    /** The inscribe button saves through the editor, so it shares one path with the editor's own save (ctrl+S). */
    private void inscribe() {
        editor.notifySaved();
    }

    /** Save callback: send the compiled spell and its drawing to the server, if it compiles. */
    private void send(CompoundTag graphTag) {
        GraphCompiler.Result r = graph.result();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!r.ok()) {
            player.sendOverlayMessage(Component.literal("✗ 术式无法成立：" + r.error()));
            return;
        }
        if (emitter != null) {
            sendToEmitter(graphTag);
            return;
        }
        ClientPacketDistributor.sendToServer(new InscribePayload(table, r.source(), graphTag));
        seal.stamp();
    }

    /** The page gets its invocation back (cast by hand) before it is written; the emitter keeps the trigger from its node. */
    private void sendToEmitter(CompoundTag graphTag) {
        SpecialNodes.EmitterRoot node = EmitterGraphs.root(graph, SpecialNodes.EmitterRoot.class);
        if (node == null) return;
        SpellGraph page = new SpellGraph();
        page.graphModel.deserialize(TagValueInput.create(ProblemReporter.DISCARDING, Platform.getFrozenRegistry(), graphTag));
        EmitterGraphs.toPage(page, kept);
        GraphCompiler.Result r = GraphCompiler.compile(page);
        var player = Minecraft.getInstance().player;
        if (!r.ok() || player == null) {
            if (player != null) player.sendOverlayMessage(Component.literal("✗ 术式无法成立：" + r.error()));
            return;
        }
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, Platform.getFrozenRegistry());
        page.graphModel.serialize(out);
        ClientPacketDistributor.sendToServer(new EmitterEditPayload(table, r.source(), out.buildResult(), node.mode(), node.interval(), fireOnSave));
        seal.stamp();
    }
}
