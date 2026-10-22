package dev.logos.client;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.itemlibrary.NodeModelLibraryItem;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;

import dev.logos.graph.EnumValues;
import dev.logos.graph.EnumValues.Kind;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/** The third manuscript bookmark: bounded pages of localized registry cards, using the canvas's undoable placement. */
final class EnumLibrary extends UIElement {
    private static final int PAGE_SIZE = 32;
    private final EnumCatalog catalog = new EnumCatalog();
    private final Consumer<NodeModelLibraryItem> place;
    private final Selector<Kind> category = new Selector<>();
    private final TextField search = new TextField();
    private final ScrollerView rows = new ScrollerView();
    private final Label count = new Label();
    private final UIElement preview = new UIElement();
    private Kind kind = Kind.CREATURE;
    private int page;

    EnumLibrary(Consumer<NodeModelLibraryItem> place) {
        this.place = place;
        EnumValues.names = EnumCatalog::name;
        setId("spell_enum_library");
        addClass(ManuscriptControls.INKED);
        layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(5).paddingAll(4));
        Label title = label(Component.translatable("logos.editor.enums"), false);
        category.setId("enum_category");
        category.setCandidateUIProvider(UIElementProvider.text(k -> Component.translatable((k == null ? kind : k).key())));
        category.setCandidates(List.of(Kind.values()));
        category.setSelected(kind, false);
        category.setOnValueChanged(value -> { if (value != null) { kind = value; page = 0; rebuild(); } });
        category.layout(l -> l.widthPercent(100));
        ManuscriptControls.paper(category);
        search.setId("enum_search");
        search.layout(l -> l.widthPercent(100).height(16));
        search.setTextResponder(value -> { page = 0; rebuild(); });
        search.style(s -> s.tooltips(Component.translatable("logos.enum.search_help")));
        ManuscriptControls.blank(search);
        preview.setId("enum_preview");
        preview.layout(l -> l.widthPercent(100).minHeight(49).flexDirection(FlexDirection.ROW).gapAll(4).paddingAll(3));
        preview.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(context, x, y, w, h, 0x50C2CFE3);
            DrawerHelperClient.drawSolidRect(context, x, y + h - 1, w, 1, 0x887A5A3A);
        }));
        rows.setId("enum_entries");
        rows.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        rows.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        rows.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        UIElement paging = new UIElement().layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(3));
        Button previous = new Button().setText("‹"), next = new Button().setText("›");
        previous.setId("enum_previous"); next.setId("enum_next");
        previous.layout(l -> l.width(20)); next.layout(l -> l.width(20));
        previous.setOnClick(event -> { if (page > 0) { page--; rebuild(); } });
        next.setOnClick(event -> { if ((page + 1) * PAGE_SIZE < filtered().size()) { page++; rebuild(); } });
        ManuscriptControls.paper(previous); ManuscriptControls.paper(next);
        count.layout(l -> l.flex(1).minWidth(0));
        count.textStyle(s -> s.fontSize(8).textColor(Manuscript.INK).textShadow(false));
        paging.addChildren(previous, count, next);
        Label hint = label(Component.translatable("logos.enum.drag_help"), true);
        hint.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        addChildren(title, category, search, preview, rows, paging, hint);
    }

    void open() { rebuild(); }

    private List<EnumCatalog.Entry> filtered() {
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        return catalog.entries(kind).stream().filter(e -> e.matches(query)).toList();
    }

    private void rebuild() {
        List<EnumCatalog.Entry> found = filtered();
        page = Math.min(page, Math.max(0, (found.size() - 1) / PAGE_SIZE));
        rows.clearAllScrollViewChildren();
        int first = page * PAGE_SIZE;
        for (int i = first; i < Math.min(found.size(), first + PAGE_SIZE); i++) rows.addScrollViewChild(row(found.get(i)));
        if (found.isEmpty()) rows.addScrollViewChild(label(Component.translatable("logos.enum.empty"), true));
        count.setText(Component.literal((page + 1) + " / " + Math.max(1, (found.size() + PAGE_SIZE - 1) / PAGE_SIZE) + "  ·  " + found.size()));
        preview.clearAllChildren();
        if (!found.isEmpty()) select(found.get(first));
        ManuscriptControls.dressList(category);
    }

    private UIElement row(EnumCatalog.Entry entry) {
        UIElement row = new UIElement().layout(l -> l.widthPercent(100).minHeight(29).flexShrink(0)
                .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4).paddingAll(2));
        row.setId("enum_" + entry.id().replace(':', '_').replace('/', '_'));
        GuiTexture icon = EnumPreviews.icon(entry.kind(), entry.id());
        UIElement picture = new UIElement().layout(l -> l.width(25).height(25).flexShrink(0));
        picture.style(s -> s.backgroundTexture(icon));
        UIElement text = new UIElement().layout(l -> l.flex(1).minWidth(0).gapAll(2));
        Label name = label(Component.literal(entry.name()), false);
        Label id = label(Component.literal(entry.id()), true);
        if (!entry.usable()) name.textStyle(s -> s.textColor(Manuscript.INK_FADED));
        text.addChildren(name, id); row.addChildren(picture, text);
        row.style(s -> s.tooltips(Component.literal(entry.name()), Component.literal(entry.id()),
                Component.translatable(entry.usable() ? "logos.enum.drag_help" : unavailable(entry.kind()))));
        row.addEventListener(UIEvents.MOUSE_ENTER, event -> {
            select(entry);
            row.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) ->
                    DrawerHelperClient.drawSolidRect(context, x, y, w, h, 0x70C2CFE3)));
        });
        row.addEventListener(UIEvents.MOUSE_LEAVE, event -> row.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        NodeModelLibraryItem item = item(entry);
        row.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 0 && entry.usable()) row.startDrag(new RuneLibrary.Dragged(item), icon);
        });
        row.addEventListener(UIEvents.DOUBLE_CLICK, event -> { if (entry.usable()) place.accept(item); });
        return row;
    }

    private void select(EnumCatalog.Entry entry) {
        preview.clearAllChildren();
        UIElement picture = new UIElement().layout(l -> l.width(43).height(43).flexShrink(0));
        picture.style(s -> s.backgroundTexture(EnumPreviews.icon(entry.kind(), entry.id())));
        UIElement text = new UIElement().layout(l -> l.flex(1).minWidth(0).gapAll(3));
        text.addChildren(label(Component.literal(entry.name()), false), label(Component.literal(entry.id()), true));
        if (!entry.usable()) {
            Label note = label(Component.translatable(unavailable(entry.kind())), true);
            note.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
            text.addChild(note);
        }
        preview.addChildren(picture, text);
    }

    static NodeModelLibraryItem item(EnumCatalog.Entry entry) {
        return new NodeModelLibraryItem(entry.id(), data -> data.createNode(
                com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.CustomNodeModelImpl.class, entry.name(),
                model -> ((ICustomNodeModel) model).initCustomNode(entry.kind().create().selected(entry.id()))));
    }

    private static String unavailable(Kind kind) {
        return "logos.enum.unavailable." + switch (kind) { case PARTICLE -> "particle"; case BLOCK -> "block"; default -> "creature"; };
    }

    private static Label label(Component text, boolean small) {
        Label label = new Label(); label.setText(text);
        label.layout(l -> l.widthPercent(100).minWidth(0));
        label.textStyle(s -> s.fontSize(small ? 7 : 9).textColor(small ? Manuscript.INK_FADED : Manuscript.INK)
                .textShadow(false).textWrap(TextWrap.HOVER_ROLL));
        return label;
    }

    @Override protected void onRemoved() { super.onRemoved(); EnumPreviews.clear(); }
}
