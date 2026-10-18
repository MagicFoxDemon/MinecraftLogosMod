package dev.logos.client;

import java.util.function.BooleanSupplier;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.Style;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * The workbench's controls drawn on the same page as the runes: buttons are slips of paper
 * ruled in ink, the context menu is a card laid on the page, and writing the spell into its page
 * is sealed in red wax. Behaviour stays the toolkit's; only the drawing changes.
 */
final class ManuscriptControls {
    /** Marks a control's text for the manuscript stylesheet (ink, no shadow). */
    static final String INKED = "manuscript-control";

    private static final int PAPER = 0xFFF3E3BD, PAPER_LIT = 0xFFF8ECCF, PAPER_PRESSED = 0xFFE6D0A0;
    private static final int GOLD = 0xFF8A6418, GOLD_LIT = 0xFFC8902A;
    private static final int WAX = 0xFFA4302A, WAX_DARK = 0xFF6E1A16, WAX_LIT = 0xFFD0584A, WAX_DEAD = 0xFF8A7F74;

    private ManuscriptControls() {}

    private enum Slip { REST, HOVER, PRESSED }

    /** A slip of paper with an inked edge; the plan is kept for the last size it was drawn at. */
    private static final class Paper implements GuiTexture {
        private final Slip slip;
        private Manuscript.Plan plan;
        private float planW = -1, planH = -1;

        Paper(Slip slip) {
            this.slip = slip;
        }

        @Override
        public void draw(GUIContext context, float x, float y, float width, float height) {
            float w = Math.round(width), h = Math.round(height);
            if (plan == null || w != planW || h != planH) {
                planW = w;
                planH = h;
                plan = slip(w, h, slip);
            }
            plan.draw(context, Math.round(x), Math.round(y) + (slip == Slip.PRESSED ? 1 : 0));
        }
    }

    private static Manuscript.Plan slip(float w, float h, Slip slip) {
        Manuscript.Plan p = new Manuscript.Plan((int) (w * 31 + h));
        int edge = slip == Slip.REST ? Manuscript.INK_FADED : Manuscript.INK;
        p.rect(1, 1, w - 2, h - 2, slip == Slip.HOVER ? PAPER_LIT : slip == Slip.PRESSED ? PAPER_PRESSED : PAPER);
        p.hStroke(1, w - 1, 0, 0, edge, 1);
        p.hStroke(1, w - 1, h - 1, 0, edge, 2);
        p.vStroke(0, 1, h - 1, 0, edge, 3);
        p.vStroke(w - 1, 1, h - 1, 0, edge, 4);
        if (slip != Slip.PRESSED) p.rect(2, h - 2, w - 3, 1, 0x40000000 | (Manuscript.INK & 0xFFFFFF));
        if (slip == Slip.HOVER) p.rect(3, h - 3, w - 6, 1, 0x80000000 | (Manuscript.SANGUINE & 0xFFFFFF));
        return p;
    }

    static void paper(Button button) {
        button.addClass(INKED);
        Style.importantPipeline(button.getButtonStyle(), s -> s.baseTexture(new Paper(Slip.REST))
                .hoverTexture(new Paper(Slip.HOVER)).pressedTexture(new Paper(Slip.PRESSED)));
    }

    /** A selector: a paper slip, and its open list a card whose entries are chosen in red chalk. */
    static void paper(Selector<?> selector) {
        selector.addClass(INKED);
        Style.importantPipeline(selector.getStyle(), s -> s.background(new Paper(Slip.REST)));
        Style.importantPipeline(selector.dialog.getStyle(), s -> s.background(CARD));
        Style.importantPipeline(selector.scrollerView.getStyle(), s -> s.background(IGuiTexture.EMPTY));
        selector.scrollerView.viewPort(v -> Style.importantPipeline(v.getStyle(), s -> s.background(IGuiTexture.EMPTY)));
    }

    /** A selector builds its entries when its list opens; they are inked as they appear. */
    static void dressList(Selector<?> selector) {
        if (selector.dialog.isDisplayed()) dressEntries(selector.scrollerView);
    }

    private static void dressEntries(UIElement list) {
        for (UIElement child : list.getChildren()) {
            if (child.hasClass(INKED)) continue;
            if (child instanceof Button entry) {
                entry.addClass(INKED);
                Style.importantPipeline(entry.getButtonStyle(), s -> s.baseTexture(IGuiTexture.EMPTY)
                        .hoverTexture(CHOSEN).pressedTexture(CHOSEN));
            } else if (child instanceof TextElement text) {
                text.addClass(INKED);
                // Entries set their own white text inline; the stylesheet cannot reach it.
                Style.importantPipeline(text.getTextStyle(), s -> s.textColor(Manuscript.INK).textShadow(false));
            }
            dressEntries(child);
        }
    }

    /** A toolbar switch: a paper slip whose icon is inked when on and faint when off. */
    static void paper(Toggle toggle, IGuiTexture icon) {
        toggle.addClass(INKED);
        Style.importantPipeline(toggle.getToggleStyle(), s -> s.baseTexture(new Paper(Slip.REST))
                .hoverTexture(new Paper(Slip.HOVER))
                .markTexture(icon.copy().setColor(Manuscript.INK))
                .unmarkTexture(icon.copy().setColor(0x807A5A3A)));
    }

    /** The toolbar's icon button (frame the drawing), with its white icon re-inked. */
    static void paperIcon(Button button, IGuiTexture icon) {
        paper(button);
        for (UIElement child : button.getChildren()) {
            if (child.hasClass("__white_icon__")) {
                Style.importantPipeline(child.getStyle(), s -> s.background(icon.copy().setColor(Manuscript.INK)));
            }
        }
    }

    /**
     * Dresses the canvas toolbar: save, undo and redo, the snapping switches and the frame button,
     * and hides the breadcrumb. The toolkit rebuilds parts of the toolbar with each graph, so this
     * runs every frame and only touches controls not yet dressed.
     */
    static void dressToolbar(UIElement header) {
        for (UIElement child : header.getChildren()) dressToolbarPart(child);
    }

    private static void dressToolbarPart(UIElement part) {
        // A spell has no subgraphs, so the breadcrumb could only ever read "root".
        if (part.hasClass("__graph-breadcrumb__")) {
            if (part.isDisplayed()) part.setDisplay(TaffyDisplay.NONE);
            return;
        }
        if (!part.hasClass(INKED)) {
            if (part instanceof Toggle toggle) {
                if (part.hasClass("__node-graph-view_header-snap-toggle__")) paper(toggle, Icons.GRID);
                else if (part.hasClass("__node-graph-view_header-align-toggle__")) paper(toggle, Icons.MAGNET);
            } else if (part instanceof Button button) {
                if (part.hasClass("__node-graph-view_header-fit-button__")) {
                    paperIcon(button, Icons.PAGE_FIT);
                } else {
                    if (part.hasClass("__node-graph-view_header-undo__")) button.setText("logos.editor.undo");
                    if (part.hasClass("__node-graph-view_header-redo__")) button.setText("logos.editor.redo");
                    paper(button);
                }
            }
        }
        for (UIElement child : part.getChildren()) dressToolbarPart(child);
    }

    // ---- the context menu -------------------------------------------------------------------

    private static final GuiTexture CARD = new GuiTexture() {
        private Manuscript.Plan plan;
        private float planW = -1, planH = -1;

        @Override
        public void draw(GUIContext context, float x, float y, float width, float height) {
            float w = Math.round(width), h = Math.round(height);
            if (plan == null || w != planW || h != planH) {
                planW = w;
                planH = h;
                plan = card(w, h);
            }
            plan.draw(context, Math.round(x), Math.round(y));
        }
    };

    /** A card of paper with a ruled edge and the left-handed hatching beneath it. */
    private static Manuscript.Plan card(float w, float h) {
        Manuscript.Plan p = new Manuscript.Plan((int) (w * 17 + h));
        int hatch = 0x50000000 | (Manuscript.INK & 0xFFFFFF);
        for (float y = 3; y < h; y += 3) p.hatch(w + 1, y, (int) Math.min(3, h + 2 - y), hatch);
        for (float x = 4; x < w + 1; x += 3) p.hatch(x, h + 1, 3, hatch);
        p.rect(1, 1, w - 2, h - 2, 0xFFF6E8C6);
        p.hStroke(0, w, 0, -1, Manuscript.INK, 1);
        p.hStroke(0, w, h - 1, 1, Manuscript.INK, 2);
        p.vStroke(0, 0, h, -1, Manuscript.INK, 3);
        p.vStroke(w - 1, 0, h, 1, Manuscript.INK, 4);
        return p;
    }

    /** The entry under the cursor: a stroke of red chalk down its left and a pale wash across it. */
    private static final GuiTexture CHOSEN = (context, x, y, w, h) -> {
        DrawerHelperClient.drawSolidRect(context, x, y, w, h, 0x66EBB99A);
        DrawerHelperClient.drawSolidRect(context, x, y + 1, 1, h - 2, Manuscript.SANGUINE);
    };

    /**
     * Dresses any context menu (and the submenus it opens) found among {@code root}'s children.
     * The toolkit builds these on the fly with its own colours set inline, so they are found and
     * redrawn before their first frame rather than styled by the stylesheet.
     */
    static void dressMenus(UIElement root) {
        for (UIElement child : root.getChildren()) {
            if (child instanceof Menu<?, ?> menu) dressMenu(menu);
        }
    }

    private static void dressMenu(Menu<?, ?> menu) {
        if (!menu.hasClass(INKED)) {
            menu.addClass(INKED);
            Style.importantPipeline(menu.getStyle(), s -> s.background(CARD));
            menu.setHoverTextureProvider(node -> CHOSEN);
        }
        for (UIElement item : menu.getChildren()) {
            for (UIElement inner : item.getChildren()) {
                if (inner instanceof Menu<?, ?> submenu) dressMenu(submenu);
            }
        }
    }

    // ---- writing the spell into its page ----------------------------------------------------

    /**
     * Writing the spell into its page is sealed: a band of gilt-ruled parchment with a red wax
     * seal at its head, pressed when clicked. While the drawing does not hold, the wax is grey;
     * after a successful click the seal rings outward for a moment.
     */
    static final class Seal {
        private final BooleanSupplier holds;
        private long stampedAt = Long.MIN_VALUE;

        Seal(BooleanSupplier holds) {
            this.holds = holds;
        }

        void stamp() {
            stampedAt = System.currentTimeMillis();
        }

        void apply(Button button) {
            button.addClass(INKED);
            button.addClass("manuscript-seal");
            Style.importantPipeline(button.getButtonStyle(), s -> s.baseTexture(texture(Slip.REST))
                    .hoverTexture(texture(Slip.HOVER)).pressedTexture(texture(Slip.PRESSED)));
            button.layout(l -> l.height(26).paddingLeft(24));
        }

        private GuiTexture texture(Slip slip) {
            Manuscript.Plan[] cache = new Manuscript.Plan[3];
            float[] size = {-1, -1};
            return (context, x, y, width, height) -> {
                float w = Math.round(width), h = Math.round(height);
                if (cache[0] == null || w != size[0] || h != size[1]) {
                    size[0] = w;
                    size[1] = h;
                    cache[0] = band(w, h, slip);
                    cache[1] = wax(h, slip, true);
                    cache[2] = wax(h, slip, false);
                }
                float ox = Math.round(x), oy = Math.round(y);
                cache[0].draw(context, ox, oy);
                Manuscript.Plan wax = holds.getAsBoolean() ? cache[1] : cache[2];
                wax.draw(context, ox, oy + (slip == Slip.PRESSED ? 1 : 0));
                long since = System.currentTimeMillis() - stampedAt;
                if (since >= 0 && since < 700) ring(context, ox + 13, oy + h / 2, since / 700f);
            };
        }

        private static Manuscript.Plan band(float w, float h, Slip slip) {
            Manuscript.Plan p = new Manuscript.Plan((int) (w * 13 + h));
            int gilt = slip == Slip.REST ? GOLD : GOLD_LIT;
            p.rect(1, 1, w - 2, h - 2, slip == Slip.PRESSED ? PAPER_PRESSED : slip == Slip.HOVER ? PAPER_LIT : PAPER);
            p.rect(2, 2, w - 4, (h - 4) / 2, 0x30C8902A);
            p.hStroke(0, w, 0, 0, gilt, 1);
            p.hStroke(0, w, h - 1, 0, gilt, 2);
            p.vStroke(0, 0, h, 0, gilt, 3);
            p.vStroke(w - 1, 0, h, 0, gilt, 4);
            int inner = 0x99000000 | (gilt & 0xFFFFFF);
            p.hStroke(3, w - 3, 2, 0, inner, 5);
            p.hStroke(3, w - 3, h - 3, 0, inner, 6);
            p.vStroke(2, 3, h - 3, 0, inner, 7);
            p.vStroke(w - 3, 3, h - 3, 0, inner, 8);
            // Small lozenges at the corners, as a gilded title page has.
            for (float[] c : new float[][]{{2, 2}, {w - 3, 2}, {2, h - 3}, {w - 3, h - 3}}) {
                p.rect(c[0], c[1] - 1, 1, 3, gilt);
                p.rect(c[0] - 1, c[1], 3, 1, gilt);
            }
            if (slip == Slip.HOVER) {
                p.rect(w - 9, 4, 1, 1, GOLD_LIT);
                p.rect(w - 6, h - 5, 1, 1, GOLD_LIT);
                p.rect(w / 2, 3, 1, 1, GOLD_LIT);
            }
            return p;
        }

        /** The seal: ribbon tails, a wax disc with a rim, and the sun pressed into it. */
        private static Manuscript.Plan wax(float h, Slip slip, boolean live) {
            Manuscript.Plan p = new Manuscript.Plan(7);
            float cx = 13, cy = Math.round(h / 2);
            int body = live ? (slip == Slip.HOVER ? WAX_LIT : WAX) : WAX_DEAD;
            int dark = live ? WAX_DARK : 0xFF5A524A;
            p.rect(cx - 4, cy + 4, 2, 6, live ? 0xFF8C2A24 : 0xFF6F665E);
            p.rect(cx + 2, cy + 4, 2, 5, live ? 0xFF8C2A24 : 0xFF6F665E);
            p.disc(cx, cy, 8, body);
            p.ring(cx, cy, 8, dark);
            p.ring(cx, cy, 4, dark);
            p.rect(cx - 1, cy - 1, 2, 2, dark);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                p.rect(cx + (float) Math.round(Math.cos(a) * 6) - 1, cy + (float) Math.round(Math.sin(a) * 6) - 1, 1, 1, dark);
            }
            if (live) p.rect(cx - 5, cy - 6, 3, 1, 0xB0FFFFFF);
            if (!live) {
                // A cracked seal: the spell does not yet hold.
                p.rect(cx + 1, cy - 7, 1, 3, dark);
                p.rect(cx, cy - 4, 1, 3, dark);
                p.rect(cx + 1, cy - 1, 1, 4, dark);
            }
            return p;
        }

        /** The seal's impression spreading out across the page. */
        private static void ring(GUIContext context, float cx, float cy, float t) {
            Manuscript.Plan p = new Manuscript.Plan(3);
            int alpha = (int) ((1 - t) * 200);
            p.ring(0, 0, 9 + Math.round(t * 18), (alpha << 24) | (Manuscript.SANGUINE & 0xFFFFFF));
            p.draw(context, cx, cy);
        }
    }
}
