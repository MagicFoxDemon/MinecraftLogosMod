package dev.logos.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

import dev.logos.guide.GuideBook;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 逻各斯手记 on screen, on the workbench's parchment: back and forward, the title and a search field along the top;
 * the chapters down the left; the page on the right; and on the page's edge a bookmark ribbon for each chapter. The
 * page last read is kept for the session, and the book opens there again.
 */
public final class GuideScreen {
    /** The entry last read this session. */
    private static @Nullable String last;
    /** Chapters the reader folded away in the contents, kept for the session. */
    private static final Set<String> FOLDED = new HashSet<>();

    private final GuideBook book;
    private final List<String> history = new ArrayList<>();
    private int at = -1;
    private @Nullable String current;
    private final ScrollerView contents = new ScrollerView();
    private final UIElement toc = new UIElement();
    private final ScrollerView pageView = new ScrollerView();
    private final UIElement page = new UIElement();
    private final TextField search = new TextField();
    private final Button back = new Button();
    private final Button forward = new Button();

    private GuideScreen(GuideBook book) {
        this.book = book;
    }

    /** Opens the book where it was last read this session, or at its first page. */
    public static void open() {
        open(null);
    }

    /** Opens the book at the entry {@code entry}, or where it was last read when null. */
    public static void open(@Nullable String entry) {
        GuideBook book = GuideLoader.book();
        GuideScreen screen = new GuideScreen(book);
        ModularUI ui = screen.createUI();
        String start = entry != null ? entry : last != null && book.entry(last) != null ? last : book.first() == null ? null : book.first().id();
        screen.go(start, true);
        Minecraft.getInstance().setScreen(new ModularUIScreen(ui, Component.translatable("logos.guide.title")));
    }

    /** The entry shown, for tests. */
    public static @Nullable String lastRead() {
        return last;
    }

    private ModularUI createUI() {
        back.setText("logos.guide.back");
        back.setId("guide_back");
        back.setOnClick(e -> step(-1));
        back.style(s -> s.tooltips(Component.translatable("logos.guide.back.tooltip")));
        forward.setText("logos.guide.forward");
        forward.setId("guide_forward");
        forward.setOnClick(e -> step(1));
        forward.style(s -> s.tooltips(Component.translatable("logos.guide.forward.tooltip")));
        for (Button b : List.of(back, forward)) {
            b.layout(l -> l.width(18).height(16));
            ManuscriptControls.paper(b);
        }
        Label title = new Label();
        title.setText(Component.literal("☉ ").append(Component.translatable("logos.guide.title")));
        title.textStyle(s -> s.textColor(Manuscript.INK).textShadow(false));
        title.layout(l -> l.flex(1));
        search.setId("guide_search");
        search.layout(l -> l.width(120).height(14));
        search.setTextResponder(q -> fillContents());
        search.style(s -> s.tooltips(Component.translatable("logos.guide.search.tooltip")));
        search.textFieldStyle(s -> s.placeholder(Component.translatable("logos.guide.search").withColor(0xFF9A8466)));
        ManuscriptControls.blank(search);
        UIElement top = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)
                .widthPercent(100).height(18).gapAll(4).flexShrink(0));
        top.addChildren(back, forward, title, search);

        contents.setId("guide_contents");
        contents.layout(l -> l.widthPercent(28).maxWidth(170).minWidth(90).heightPercent(100));
        contents.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        contents.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        toc.layout(l -> l.widthPercent(100).gapAll(1).paddingRight(4));
        contents.addScrollViewChild(toc);

        pageView.setId("guide_page");
        pageView.layout(l -> l.flex(1).minWidth(0).heightPercent(100));
        pageView.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        pageView.viewPort(v -> v.style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        page.layout(l -> l.widthPercent(100).gapAll(4).paddingAll(6).paddingRight(10));
        pageView.addScrollViewChild(page);

        // A ruled line between the contents and the page, in faded ink.
        UIElement gutter = new UIElement().layout(l -> l.width(1).heightPercent(100).flexShrink(0));
        gutter.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> DrawerHelperClient.drawSolidRect(context, x, y, w, h, 0x667A5A3A)));
        UIElement body = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).flex(1).minHeight(0).gapAll(6));
        body.addChildren(contents, gutter, pageView);

        UIElement main = new UIElement().layout(l -> l.flex(1).minWidth(0).heightPercent(100).gapAll(6));
        main.addChildren(top, body);

        UIElement ribbons = new UIElement().layout(l -> l.width(16).heightPercent(100).gapAll(4).paddingTop(16));
        ribbons.setId("guide_ribbons");
        for (GuideBook.Chapter chapter : book.chapters()) {
            UIElement ribbon = ManuscriptControls.ribbon(chapter.ribbon(), () -> current != null && current.startsWith(chapter.id() + "/"),
                    () -> go(chapter.entries().getFirst().id(), true));
            ribbon.setId("ribbon_" + safe(chapter.id()));
            ribbons.addChild(ribbon);
        }

        UIElement root = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).widthPercent(100).heightPercent(100)
                .paddingAll(28).gapAll(8));
        root.setId("guide_background");
        root.style(s -> s.backgroundTexture(new SpellEditorBackground()));
        root.addChildren(main, ribbons);
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP),
                StylesheetManager.INSTANCE.getStylesheetSafe(SpellEditor.MANUSCRIPT)));
    }

    /** An id written so a stylesheet selector can name it: {@code logos:basics/first} becomes {@code logos_basics_first}. */
    public static String safe(String id) {
        return id.replace(':', '_').replace('/', '_').replace('.', '_');
    }

    /** Shows the entry {@code id}; {@code remember} puts it in the history (back and forward do not). */
    void go(@Nullable String id, boolean remember) {
        if (remember && id != null && !id.equals(current)) {
            while (history.size() > at + 1) history.removeLast();
            history.add(id);
            at = history.size() - 1;
        }
        current = id;
        if (id != null && book.entry(id) != null) {
            last = id;
            FOLDED.remove(book.entry(id).chapter());
        }
        page.clearAllChildren();
        GuidePage.render(book, id, this::follow, page);
        if (!page.getChildren().isEmpty()) pageView.scrollToChildDelayed(page.getChildren().getFirst());
        back.setActive(at > 0);
        forward.setActive(at < history.size() - 1);
        fillContents();
    }

    /** Follows a link written on the page shown. */
    private void follow(String link) {
        if (GuideBook.isItem(link)) return;
        go(current == null ? link : GuideBook.resolve(current, link), true);
    }

    private void step(int by) {
        int to = at + by;
        if (to < 0 || to >= history.size()) return;
        at = to;
        go(history.get(at), false);
    }

    /** The left column: every chapter with its entries, or what the search finds. */
    private void fillContents() {
        toc.clearAllChildren();
        if (book.isEmpty()) {
            toc.addChild(faded(Component.translatable("logos.guide.empty")));
            return;
        }
        String query = search.getValue();
        if (query != null && !query.isBlank()) {
            List<GuideBook.Entry> found = book.search(query);
            toc.addChild(heading(Component.translatable("logos.guide.results", found.size()), null));
            if (found.isEmpty()) toc.addChild(faded(Component.translatable("logos.guide.no_results")));
            for (GuideBook.Entry e : found) toc.addChild(row(e, true));
            return;
        }
        for (GuideBook.Chapter chapter : book.chapters()) {
            boolean folded = FOLDED.contains(chapter.id());
            toc.addChild(heading(Component.literal((folded ? "▸ " : "▾ ") + chapter.title()), chapter.id()));
            if (!folded) for (GuideBook.Entry e : chapter.entries()) toc.addChild(row(e, false));
        }
    }

    /** A chapter's heading in a pale wash; clicking folds the chapter away or opens it again. */
    private UIElement heading(Component text, @Nullable String chapter) {
        UIElement heading = new UIElement().layout(l -> l.widthPercent(100).height(13).flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER).paddingLeft(2).marginTop(3));
        heading.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> {
            DrawerHelperClient.drawSolidRect(context, x, y, w, h, 0x33C8902A);
            DrawerHelperClient.drawSolidRect(context, x, y + h - 1, w, 1, 0x993B2614);
        }));
        Label label = new Label();
        label.setText(text);
        label.textStyle(s -> s.textColor(Manuscript.INK).textShadow(false));
        label.layout(l -> l.flex(1));
        heading.addChild(label);
        if (chapter != null) {
            heading.setId("chapter_" + safe(chapter));
            heading.addEventListener(UIEvents.MOUSE_DOWN, e -> {
                if (e.button != 0) return;
                if (!FOLDED.remove(chapter)) FOLDED.add(chapter);
                fillContents();
            });
        }
        return heading;
    }

    /** An entry in the contents; the one shown is marked in red chalk. With {@code chapter}, its chapter is named after it. */
    private UIElement row(GuideBook.Entry entry, boolean chapter) {
        boolean shown = entry.id().equals(current);
        UIElement row = new UIElement().layout(l -> l.widthPercent(100).paddingLeft(6).paddingVertical(1));
        row.setId("toc_" + safe(entry.id()));
        row.addClass("guide-toc-entry");
        if (shown) {
            row.style(s -> s.backgroundTexture((GuiTexture) (context, x, y, w, h) -> {
                DrawerHelperClient.drawSolidRect(context, x, y, w, h, 0x66EBB99A);
                DrawerHelperClient.drawSolidRect(context, x + 1, y + 1, 1, h - 2, Manuscript.SANGUINE);
            }));
        }
        Label label = new Label();
        Component text = Component.literal(entry.title());
        if (chapter) {
            GuideBook.Chapter c = book.chapter(entry.chapter());
            if (c != null) text = text.copy().append(Component.literal("  · " + c.title()).withColor(Manuscript.INK_FADED));
        }
        label.setText(text);
        label.textStyle(s -> s.textColor(shown ? Manuscript.SANGUINE : Manuscript.INK).textShadow(false).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        label.layout(l -> l.widthPercent(100));
        row.addChild(label);
        row.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) go(entry.id(), true);
        });
        return row;
    }

    private static Label faded(Component text) {
        Label label = new Label();
        label.setText(text);
        label.textStyle(s -> s.textColor(Manuscript.INK_FADED).textShadow(false).textWrap(TextWrap.WRAP).adaptiveHeight(true));
        label.layout(l -> l.widthPercent(100).paddingLeft(6).marginTop(2));
        return label;
    }
}
