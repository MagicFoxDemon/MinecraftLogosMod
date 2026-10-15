package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
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

import dev.logos.LogosMod;
import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.net.InscribePayload;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The spell workbench: a node-graph canvas on the left (typed ports, wires snap only when the
 * slot shapes agree) and, on the right, an overview of what is drawn: whether it holds, roughly what
 * one cast costs against what it injects, and its intent — inferred, or set here by the player.
 */
public final class SpellEditor {
    /** Where the inscription table holding the page stands; saving writes into that page. */
    private final BlockPos table;
    /** Grade of the page on the table: the editor warns when the spell injects more than it holds. */
    private final PageTier tier;
    private final GraphEditorView editor = new GraphEditorView(SpellGraphView::new);
    private final Label status = new Label();
    private final Label overview = new Label();
    private final Selector<SpecialNodes.IntentChoice> intent = new Selector<>();
    /** Set while the selector is being matched to the graph, so that does not count as the player choosing. */
    private boolean syncingIntent;
    private SpellGraph graph;
    /** Ticks until the canvas is framed around a freshly loaded graph (node sizes need a layout pass first). */
    private int fitIn = -1;

    /** A spell canvas needs navigation, but not the toolkit's variable and appearance panels. */
    private static final class SpellGraphView extends GraphView {
        private SpellGraphView() {
            SpellNavigation navigation = new SpellNavigation(this);
            navigation.setId("spell_navigation");
            navigation.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                    .right(8).bottom(8).width(110).height(110));
            navigation.style(s -> s.backgroundTexture(new ColorRectTexture(0x80181818)));
            canvas.addChild(navigation);
        }

        @Override
        protected void initPanels() {
            // Leave the dock empty; navigation is attached directly to the canvas instead.
        }
    }

    private SpellEditor(BlockPos table, PageTier tier) {
        this.table = table;
        this.tier = tier;
    }

    /** Opens the editor for the page on the inscription table at {@code table}, restoring its drawing (or its source). */
    public static void open(BlockPos table, ItemStack page) {
        PageTier tier = SpellPages.tier(page);
        SpellEditor e = new SpellEditor(table, tier == null ? PageTier.BASIC : tier);
        e.load(initialGraph(page));
        Minecraft.getInstance().setScreen(new ModularUIScreen(e.createUI(), Component.literal("Logos")));
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
        graph = g;
        graph.addListener(this::refresh);
        editor.loadGraph(graph, this::send);
        fitIn = 3;
        refresh();
    }

    private ModularUI createUI() {
        editor.layout(l -> l.flex(1).heightPercent(100));
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
        Button inscribe = new Button().setText("🜔 写入术式载页");
        inscribe.setId("inscribe");
        inscribe.setOnClick(ev -> inscribe());

        status.setId("status");
        overview.setId("overview");
        for (Label l : List.of(status, overview)) {
            l.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
            l.layout(x -> x.widthPercent(100));
        }
        ScrollerView overviewView = new ScrollerView();
        overviewView.layout(l -> l.widthPercent(100).flex(1));
        overviewView.addScrollViewChild(overview);

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

        UIElement side = new UIElement().addClass("panel_bg")
                .layout(l -> l.width(190).heightPercent(100).paddingAll(6).gapAll(5));
        side.addChildren(
                new Label().setText(Component.literal("☉ Logos 术式工坊")),
                new Label().setText(Component.literal("模板")),
                templates,
                row,
                status,
                new Label().setText(Component.literal("☷ 总览")),
                new Label().setText(Component.literal("意图（魔力生命体据此决定何时施放）")),
                intent,
                overviewView,
                inscribe);

        UIElement root = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).heightPercent(100));
        root.addChildren(editor, side);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)));
    }

    private void refresh() {
        GraphCompiler.Result r = graph.result();
        if (r.ok()) {
            var s = r.spell();
            StringBuilder sb = new StringBuilder("✓ 《" + s.name() + "》成立\n");
            sb.append("注入魔力 ").append(Node.fmt(s.mana())).append(" · 稳定度 ").append(Node.fmt(s.stability())).append('\n');
            if (s.mana() > tier.capacity()) {
                sb.append("⚠ 台上的载页只能承载 ").append(tier.capacity()).append(" 魔力：可以照常写入，施放时只注入 ").append(tier.capacity())
                        .append(s.immolate() ? "（持焚式法杖可放大上限，载页焚毁）" : "（已关闭焚式）").append('\n');
            }
            for (String note : s.notes()) sb.append("∴ ").append(note).append('\n');
            status.setText(Component.literal(sb.toString().strip()));
        } else {
            status.setText(Component.literal("✗ " + r.error()));
        }
        overview.setText(Component.literal(r.ok() ? overview(r.spell()) : ""));
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
        ClientPacketDistributor.sendToServer(new InscribePayload(table, r.source(), graphTag));
    }
}
