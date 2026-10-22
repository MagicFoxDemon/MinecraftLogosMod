package dev.logos.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.GraphNodeCreationData;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;

import dev.logos.graph.LogosNode;
import dev.logos.graph.SpecialNodes;
import dev.logos.lang.Category;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import net.minecraft.network.chat.Component;

/**
 * Every rune laid out by family on a page beside the canvas, to be dragged onto the drawing (or
 * double-clicked, to land in the middle of the view). Placing goes through the toolkit's own
 * create-node command, so it can be undone like any other edit.
 */
final class RuneLibrary extends UIElement {
    /** What is carried while a rune is dragged out of the library. */
    record Dragged(NodeModelLibraryItem item) {}

    /** One family on the page: its heading, its runes, and whether it is folded away. */
    private record Family(String label, Category ink, UIElement body, List<Entry> entries, boolean[] folded, UIElement[] heading) {}

    private record Entry(UIElement row, String searchable) {}

    private final TextField search = new TextField();
    private final ScrollerView page = new ScrollerView();
    private final List<Family> families = new ArrayList<>();
    private final Consumer<NodeModelLibraryItem> placeInView;
    private boolean filled;

    RuneLibrary(Consumer<NodeModelLibraryItem> placeInView) {
        this.placeInView = placeInView;
        setId("spell_library");
        addClass(ManuscriptControls.INKED);
        layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(4).paddingAll(4));
        search.setId("spell_library_search");
        search.layout(l -> l.widthPercent(100).height(14));
        search.setTextResponder(this::filter);
        ManuscriptControls.blank(search);
        page.setId("spell_library_page");
        page.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        page.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        page.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        Label title = new Label();
        title.setText(Component.translatable("logos.editor.library"));
        title.layout(l -> l.widthPercent(100));
        addChildren(title, search, page);
    }

    /** Lays out the library from the canvas's registered runes; done once, after names are localized. */
    void fill(GraphView view) {
        if (filled || view.getGraph() == null) return;
        filled = true;
        var graph = view.getGraph().graphModel;
        Map<String, Family> byFamily = new LinkedHashMap<>();
        for (Category c : new Category[]{Category.INVOCATION, Category.FORM, Category.BINDING}) family(byFamily, c.name(), c.zh, c);
        family(byFamily, "CONTEXT", Component.translatable("logos.editor.library.context").getString(), Category.BINDING);
        for (Category c : new Category[]{Category.EFFECT, Category.LOGIC, Category.VALUE}) family(byFamily, c.name(), c.zh, c);
        for (var item : view.itemLibrary.getAllItems().toList()) {
            if (!(item instanceof NodeModelLibraryItem entry)
                    || !(entry.createNode(GraphNodeCreationData.ofOrphan(graph)) instanceof ICustomNodeModel model)
                    || !(model.getNode() instanceof LogosNode rune)) continue;
            if (rune instanceof dev.logos.graph.EnumValues.Value) continue;
            Family family = byFamily.get(rune instanceof SpecialNodes.ContextValue ? "CONTEXT" : rune.category().name());
            if (family == null) continue;
            String name = Component.translatableWithFallback(rune.nameKey(), rune.word()).getString();
            UIElement row = entry(entry, rune, name);
            family.body.addChild(row);
            family.entries.add(new Entry(row, (name + " " + rune.word()).toLowerCase(Locale.ROOT)));
        }
        for (Family family : byFamily.values()) {
            if (family.entries.isEmpty()) continue;
            families.add(family);
            family.heading[0] = heading(family);
            page.addScrollViewChild(family.heading[0]);
            page.addScrollViewChild(family.body);
        }
    }

    private static void family(Map<String, Family> into, String key, String label, Category ink) {
        UIElement body = new UIElement().layout(l -> l.widthPercent(100).gapAll(1).paddingLeft(2));
        into.put(key, new Family(label, ink, body, new ArrayList<>(), new boolean[1], new UIElement[1]));
    }

    /** A family's heading in its wash; clicking folds the family away or opens it again. */
    private UIElement heading(Family family) {
        UIElement heading = new UIElement().layout(l -> l.widthPercent(100).height(13).flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER).gapAll(3).paddingLeft(2).marginTop(3));
        int wash = ManuscriptNode.wash(family.ink);
        heading.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(context, x, y, w, h, wash);
            DrawerHelperClient.drawSolidRect(context, x, y + h - 1, w, 1, 0x993B2614);
        }));
        Label label = new Label();
        label.setText(Component.literal((family.folded[0] ? "▸ " : "▾ ") + family.label + "  " + family.entries.size()));
        label.layout(l -> l.flex(1));
        heading.addChild(label);
        heading.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button != 0) return;
            family.folded[0] = !family.folded[0];
            label.setText(Component.literal((family.folded[0] ? "▸ " : "▾ ") + family.label + "  " + family.entries.size()));
            filter(search.getValue());
        });
        return heading;
    }

    /** A rune on the library page: its drawing and name, to be dragged onto the canvas. */
    private UIElement entry(NodeModelLibraryItem item, LogosNode rune, String name) {
        UIElement row = new UIElement().layout(l -> l.widthPercent(100).height(12).flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER).gapAll(3).paddingLeft(2));
        row.addClass("spell-library-entry");
        GuiTexture emblem = ManuscriptNode.emblem(rune);
        UIElement icon = new UIElement().layout(l -> l.width(9).height(9));
        icon.style(s -> s.backgroundTexture(emblem));
        Label label = new Label();
        label.setText(Component.literal(name));
        label.layout(l -> l.flex(1).minWidth(0));
        label.textStyle(s -> s.textWrap(TextWrap.HOVER_ROLL));
        row.addChildren(icon, label);
        int wash = ManuscriptNode.wash(rune.category());
        GuiTexture hover = (context, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(context, x, y, w, h, (wash & 0x00FFFFFF) | 0xB0000000);
            DrawerHelperClient.drawSolidRect(context, x, y + 1, 1, h - 2, Manuscript.SANGUINE);
        };
        row.addEventListener(UIEvents.MOUSE_ENTER, e -> row.style(s -> s.backgroundTexture(hover)));
        row.addEventListener(UIEvents.MOUSE_LEAVE, e -> row.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) row.startDrag(new Dragged(item), emblem);
        });
        row.addEventListener(UIEvents.DOUBLE_CLICK, e -> placeInView.accept(item));
        String doc = rune.doc();
        String first = doc.isBlank() ? "" : doc.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        row.style(s -> s.tooltips(Component.literal(name + "  " + rune.word()), Component.literal(first)));
        return row;
    }

    /** Shows the runes whose name or word contains {@code query}; while searching, folded families open. */
    private void filter(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        for (Family family : families) {
            boolean any = false;
            for (Entry entry : family.entries) {
                boolean shown = q.isEmpty() ? !family.folded[0] : entry.searchable.contains(q);
                entry.row.setDisplay(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
                any |= entry.searchable.contains(q);
            }
            family.body.setDisplay(q.isEmpty() || any ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
            family.heading[0].setDisplay(q.isEmpty() || any ? TaffyDisplay.FLEX : TaffyDisplay.NONE);
        }
    }
}
