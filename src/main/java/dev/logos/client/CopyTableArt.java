package dev.logos.client;

import java.util.function.BooleanSupplier;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

/**
 * The copy table's screen drawn like the table itself, in the inscription table's red walnut, gilt
 * and navy: the desk with gilt corner brackets, the slanted copy stand with its gilt ledge on the
 * left (the original's page glowing with its runes while it lies there), the navy blotter with gilt
 * corners on the right (the empty paper), and between them the inkwell and quill over a gilt-framed
 * navy panel that holds the copy. A golden thread runs from the stand and the blotter to the quill
 * and down to the copy while there is one to take. Everything is laid in whole GUI pixels. The
 * screen is also built on the server, where these are only created, never drawn.
 */
public final class CopyTableArt {
    private static final int WOOD_DEEP = 0xFF1E0F0A, WOOD = 0xFF462419, WOOD_LIT = 0xFF522A1D, WOOD_HI = 0xFF673520;
    private static final int GRAIN = 0x70261208, SEAM = 0xC0120804;
    private static final int GILT = 0xFFDCA73B, GILT_LIT = 0xFFFDDE6A, GILT_DARK = 0xFF8A5C17;
    private static final int NAVY_DEEP = 0xFF060E1C, NAVY = 0xFF0D172C, NAVY_LIT = 0xFF1C2D51, NAVY_EDGE = 0xFF2E4774;
    private static final int GLOW = 0xFFF2C25A, GLOW_SOFT = 0x40FFC860, RUNE = 0xFFC9822A;
    private static final int THREAD_DIM = 0xFF6A4320;
    /** Gilt writing on the wood. */
    public static final int ON_WOOD = GILT_LIT;

    private CopyTableArt() {}

    private interface Sized {
        Manuscript.Plan plan(float w, float h);
    }

    private static GuiTexture cached(Sized planner) {
        Manuscript.Plan[] plan = new Manuscript.Plan[1];
        float[] size = {-1, -1};
        return (context, x, y, width, height) -> {
            float w = Math.round(width), h = Math.round(height);
            if (plan[0] == null || w != size[0] || h != size[1]) {
                size[0] = w;
                size[1] = h;
                plan[0] = planner.plan(w, h);
            }
            plan[0].draw(context, Math.round(x), Math.round(y));
        };
    }

    /** Two plans for one size: with what the slot holds, and without. */
    private interface Planner {
        Manuscript.Plan plan(float w, float h, boolean filled);
    }

    private static GuiTexture switched(Planner planner, BooleanSupplier filled) {
        Manuscript.Plan[] plans = new Manuscript.Plan[2];
        float[] size = {-1, -1};
        return (context, x, y, width, height) -> {
            float w = Math.round(width), h = Math.round(height);
            if (w != size[0] || h != size[1]) {
                size[0] = w;
                size[1] = h;
                plans[0] = plans[1] = null;
            }
            int i = filled.getAsBoolean() ? 1 : 0;
            if (plans[i] == null) plans[i] = planner.plan(w, h, i == 1);
            plans[i].draw(context, Math.round(x), Math.round(y));
        };
    }

    // ---- the desk --------------------------------------------------------------------------------

    /** The screen: red walnut boards in a dark frame with a gilt line inside it and gilt brackets at the corners. */
    public static IGuiTexture box() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(11);
            boards(p, 0, 0, w, h, WOOD, WOOD_LIT);
            int frame = 6;
            p.rect(0, 0, w, frame, WOOD_DEEP);
            p.rect(0, h - frame, w, frame, WOOD_DEEP);
            p.rect(0, frame, frame, h - 2 * frame, WOOD_DEEP);
            p.rect(w - frame, frame, frame, h - 2 * frame, WOOD_DEEP);
            // The gilt line round the inside of the frame, lit on its top and left.
            p.rect(frame - 2, frame - 2, w - 2 * frame + 4, 1, GILT);
            p.rect(frame - 2, h - frame + 1, w - 2 * frame + 4, 1, GILT_DARK);
            p.rect(frame - 2, frame - 2, 1, h - 2 * frame + 4, GILT);
            p.rect(w - frame + 1, frame - 2, 1, h - 2 * frame + 4, GILT_DARK);
            for (float[] c : new float[][]{{0, 0, 1, 1}, {w, 0, -1, 1}, {0, h, 1, -1}, {w, h, -1, -1}}) bracket(p, c[0], c[1], (int) c[2], (int) c[3]);
            return p;
        });
    }

    /** The desk top the stand and the blotter lie on: lighter boards, sunk a little into the frame. */
    public static IGuiTexture top() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(23);
            boards(p, 0, 0, w, h, 0xFF5A2E1D, 0xFF63331F);
            p.rect(0, 0, w, 2, 0x80000000);
            p.rect(0, 0, 2, h, 0x80000000);
            p.rect(0, h - 1, w, 1, 0x40673520);
            return p;
        });
    }

    /** Horizontal boards with seams, staggered joints and grain. */
    private static void boards(Manuscript.Plan p, float x0, float y0, float w, float h, int dark, int lit) {
        int board = 22, n = 0;
        for (float y = y0; y < y0 + h; y += board, n++) {
            float bh = Math.min(board, y0 + h - y);
            p.rect(x0, y, w, bh, n % 2 == 0 ? dark : lit);
            p.rect(x0, y, w, 1, SEAM);
            p.rect(x0, y + 1, w, 1, 0x30FFB070);
            for (int g = 0; g < w / 24; g++) {
                float gx = x0 + p.roll(n * 97 + g * 7, (int) Math.max(1, w - 20));
                float gy = y + 3 + p.roll(n * 97 + g * 7 + 1, (int) Math.max(1, bh - 5));
                p.rect(gx, gy, 6 + p.roll(n * 97 + g * 7 + 2, 22), 1, GRAIN);
            }
            for (float jx = x0 + 40 + p.roll(n * 13, 90); jx < x0 + w - 10; jx += 110 + p.roll(n * 13 + (int) jx, 70)) {
                p.rect(jx, y + 1, 1, bh - 1, SEAM);
            }
        }
    }

    /** A gilt L-bracket with rivets, its corner at (x, y), its arms running along (dx, dy). */
    private static void bracket(Manuscript.Plan p, float x, float y, int dx, int dy) {
        float bx = dx > 0 ? x : x - 14, by = dy > 0 ? y : y - 14;
        float ax = dx > 0 ? x : x - 4, ay = dy > 0 ? y : y - 4;
        p.rect(bx, ay, 14, 4, GILT);
        p.rect(ax, by, 4, 14, GILT);
        p.rect(bx, dy > 0 ? ay : ay + 3, 14, 1, dy > 0 ? GILT_LIT : GILT_DARK);
        p.rect(dx > 0 ? ax : ax + 3, by, 1, 14, dx > 0 ? GILT_LIT : GILT_DARK);
        p.rect(x + dx * 9 - (dx < 0 ? 1 : 0), y + dy * 2 - (dy < 0 ? 1 : 0), 1, 1, GILT_DARK);
        p.rect(x + dx * 2 - (dx < 0 ? 1 : 0), y + dy * 9 - (dy < 0 ? 1 : 0), 1, 1, GILT_DARK);
    }

    /** The parchment the hint and the inventory are kept on. */
    public static IGuiTexture ledger() {
        return AlchemyArt.ledger();
    }

    // ---- the stand and the blotter ---------------------------------------------------------------

    /** The left: the copy stand, the original's page glowing with its runes while it leans there. */
    public static IGuiTexture originalTray(BooleanSupplier filled) {
        return switched(CopyTableArt::stand, filled);
    }

    /** The right: the navy blotter, a little stack of empty paper on it while there is some. */
    public static IGuiTexture blankTray(BooleanSupplier filled) {
        return switched(CopyTableArt::blotter, filled);
    }

    private static Manuscript.Plan stand(float w, float h, boolean filled) {
        Manuscript.Plan p = new Manuscript.Plan(57);
        // The board, narrower at the top as it leans away, its edge lit and its face grained.
        float top = 2, foot = h - 8;
        for (float y = top; y < foot; y++) {
            float inset = Math.round((foot - y) / (foot - top) * 3) + 2;
            p.rect(inset, y, w - 2 * inset, 1, y == top ? WOOD_HI : (int) y % 9 == 4 ? WOOD : WOOD_LIT);
            p.rect(inset, y, 1, 1, WOOD_DEEP);
            p.rect(w - inset - 1, y, 1, 1, WOOD_DEEP);
        }
        for (int g = 0; g < 5; g++) p.rect(7 + p.roll(g, (int) w - 18), top + 4 + p.roll(g + 9, (int) (foot - top - 8)), 5 + p.roll(g + 19, 6), 1, GRAIN);
        float px = 7, py = 5, pw = w - 14, ph = foot - py - 1;
        if (!filled) dashed(p, px, py, pw, ph, 0xFF8A5A3A);
        else {
            p.rect(px - 1, py - 1, pw + 2, ph + 2, GLOW_SOFT);
            p.rect(px, py, pw, ph, 0xFFF5DDA6);
            p.rect(px + 1, py + ph, pw, 1, 0x60000000);
            p.rect(px + 2, py + 2, pw - 4, 1, RUNE);
            p.rect(px + 2, py + ph - 3, pw - 4, 1, RUNE);
            p.rect(px + 2, py + 2, 1, ph - 4, RUNE);
            p.rect(px + pw - 3, py + 2, 1, ph - 4, RUNE);
            // Two rows of runes above and below where the slot's item sits.
            for (float row : new float[]{py + 4, py + ph - 7}) {
                for (float x = px + 4, n = 0; x < px + pw - 5; x += 4, n++) glyph(p, x, row, (int) (n + row), RUNE);
            }
            for (float[] c : new float[][]{{px, py}, {px + pw - 2, py}, {px, py + ph - 2}, {px + pw - 2, py + ph - 2}}) p.rect(c[0], c[1], 2, 2, GLOW);
        }
        // The gilt ledge the page rests on, wider than the board, and its shadow on the desk.
        p.rect(0, foot, w, 5, GILT_DARK);
        p.rect(0, foot, w - 1, 4, GILT);
        p.rect(0, foot, w - 1, 1, GILT_LIT);
        p.rect(1, foot + 5, w - 1, 2, 0x50000000);
        p.rect(Math.round(w / 2) - 1, foot + 2, 2, 1, GILT_DARK);
        return p;
    }

    private static Manuscript.Plan blotter(float w, float h, boolean filled) {
        Manuscript.Plan p = new Manuscript.Plan(61);
        p.rect(1, 1, w, h, 0x50000000);
        p.rect(0, 0, w, h, NAVY_DEEP);
        p.rect(1, 1, w - 2, h - 2, NAVY);
        p.rect(1, 1, w - 2, 1, NAVY_EDGE);
        p.rect(1, 1, 1, h - 2, NAVY_EDGE);
        p.rect(3, 3, w - 6, h - 6, NAVY_LIT);
        p.rect(4, 4, w - 8, h - 8, NAVY);
        for (int g = 0; g < 6; g++) p.rect(5 + p.roll(g, (int) w - 12), 5 + p.roll(g + 7, (int) h - 10), 1 + p.roll(g + 3, 3), 1, NAVY_LIT);
        float px = 7, py = 7, pw = w - 14, ph = h - 14;
        if (!filled) dashed(p, px, py, pw, ph, NAVY_EDGE);
        else {
            p.rect(px + 2, py + 2, pw, ph, 0xFFB8AC94);
            p.rect(px + 1, py + 1, pw, ph, 0xFFD6CBB2);
            p.rect(px, py, pw, ph, 0xFFEFE6D2);
            int mark = 0xFFB5A584;
            for (float[] c : new float[][]{{px + 2, py + 2, 1, 1}, {px + pw - 3, py + 2, -1, 1}, {px + 2, py + ph - 3, 1, -1}, {px + pw - 3, py + ph - 3, -1, -1}}) {
                p.rect(c[2] > 0 ? c[0] : c[0] - 2, c[1], 3, 1, mark);
                p.rect(c[0], c[3] > 0 ? c[1] : c[1] - 2, 1, 3, mark);
            }
        }
        // Gilt corner pieces holding the leather.
        for (float[] c : new float[][]{{0, 0, 1, 1}, {w, 0, -1, 1}, {0, h, 1, -1}, {w, h, -1, -1}}) corner(p, c[0], c[1], (int) c[2], (int) c[3]);
        return p;
    }

    /** A gilt triangle over a corner of the blotter, its right angle at (x, y). */
    private static void corner(Manuscript.Plan p, float x, float y, int dx, int dy) {
        for (int i = 0; i < 6; i++) {
            float len = 6 - i;
            float ry = dy > 0 ? y + i : y - 1 - i;
            float rx = dx > 0 ? x : x - len;
            p.rect(rx, ry, len, 1, i == 0 ? (dy > 0 ? GILT_LIT : GILT_DARK) : GILT);
            p.rect(dx > 0 ? rx + len - 1 : rx, ry, 1, 1, GILT_DARK);
        }
    }

    /** Where a page would lie: a faint dashed outline. */
    private static void dashed(Manuscript.Plan p, float px, float py, float pw, float ph, int color) {
        for (float x = px; x < px + pw; x += 4) {
            p.rect(x, py, 2, 1, color);
            p.rect(x, py + ph - 1, 2, 1, color);
        }
        for (float y = py; y < py + ph; y += 4) {
            p.rect(px, y, 1, 2, color);
            p.rect(px + pw - 1, y, 1, 2, color);
        }
    }

    /** One rune of a few pixels, chosen by {@code n}. */
    private static void glyph(Manuscript.Plan p, float x, float y, int n, int ink) {
        switch (Math.floorMod(n * 7 + 3, 5)) {
            case 0 -> { p.rect(x, y, 1, 3, ink); p.rect(x + 1, y, 1, 1, ink); }
            case 1 -> { p.rect(x, y + 1, 3, 1, ink); p.rect(x + 1, y, 1, 3, ink); }
            case 2 -> { p.rect(x, y, 2, 1, ink); p.rect(x + 1, y + 1, 1, 2, ink); }
            case 3 -> { p.rect(x, y, 1, 3, ink); p.rect(x + 2, y, 1, 3, ink); p.rect(x + 1, y + 1, 1, 1, ink); }
            default -> { p.rect(x + 1, y, 1, 1, ink); p.rect(x, y + 1, 3, 1, ink); p.rect(x + 1, y + 2, 1, 1, ink); }
        }
    }

    /** Behind a slot on paper or in the navy panel: a shallow square sunk into it. */
    public static IGuiTexture socket(boolean onPaper) {
        return (GuiTexture) (context, x, y, w, h) -> {
            int edge = onPaper ? 0x663B2614 : NAVY_DEEP, fill = onPaper ? 0x223B2614 : 0xFF09111F;
            float rx = Math.round(x), ry = Math.round(y), rw = Math.round(w), rh = Math.round(h);
            DrawerHelperClient.drawSolidRect(context, rx, ry, rw, rh, fill);
            DrawerHelperClient.drawSolidRect(context, rx, ry, rw, 1, edge);
            DrawerHelperClient.drawSolidRect(context, rx, ry, 1, rh, edge);
            if (!onPaper) {
                DrawerHelperClient.drawSolidRect(context, rx, ry + rh - 1, rw, 1, NAVY_EDGE);
                DrawerHelperClient.drawSolidRect(context, rx + rw - 1, ry, 1, rh, NAVY_EDGE);
            }
        };
    }

    // ---- the copy panel, the inkwell and the thread -----------------------------------------------

    /** The gilt-framed navy panel from the table's front, with its gilt diamond; the copy is taken from it. */
    public static IGuiTexture copyPanel() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(67);
            p.rect(1, 1, w, h, 0x50000000);
            p.rect(0, 0, w, h, GILT_DARK);
            p.rect(0, 0, w - 1, h - 1, GILT);
            p.rect(0, 0, w - 1, 1, GILT_LIT);
            p.rect(0, 0, 1, h - 1, GILT_LIT);
            p.rect(2, 2, w - 4, h - 4, NAVY_DEEP);
            p.rect(3, 3, w - 6, h - 6, NAVY);
            p.rect(3, 3, w - 6, 1, NAVY_LIT);
            diamond(p, Math.round(w / 2), h - 8, 3, GILT, GILT_DARK);
            return p;
        });
    }

    private static void diamond(Manuscript.Plan p, float cx, float cy, int r, int fill, int core) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            p.rect(cx - half, cy + i, 2 * half + 1, 1, fill);
        }
        p.rect(cx - r, cy, 1, 1, GILT_LIT);
        p.rect(cx, cy, 1, 1, core);
    }

    private static void rect(GUIContext c, float x, float y, float w, float h, int color) {
        DrawerHelperClient.drawSolidRect(c, x, y, w, h, color);
    }

    /** The inkwell standing at (cx, base) with the quill in it leaning to the right; the ink glows while a copy is ready. */
    private static void inkwell(GUIContext c, float cx, float base, boolean lit) {
        // The quill: a shaft from the ink up to the right with its vane along it.
        for (int i = 0; i < 12; i++) {
            float qx = cx + 1 + i / 2f, qy = base - 9 - i;
            rect(c, Math.round(qx), qy, 1, 1, 0xFFD8C8A4);
            if (i > 2) rect(c, Math.round(qx) + 1, qy, i < 10 ? 2 : 1, 1, i % 3 == 0 ? 0xFFBCA986 : 0xFFF2E8D2);
        }
        // The glass, its gilt neck and the ink showing through.
        rect(c, cx - 4, base - 7, 9, 7, NAVY_DEEP);
        rect(c, cx - 3, base - 6, 7, 5, lit ? 0xFF3A2A10 : NAVY);
        rect(c, cx - 3, base - 6, 1, 5, NAVY_EDGE);
        rect(c, cx - 3, base - 3, 7, 2, lit ? GLOW : NAVY_LIT);
        rect(c, cx - 2, base - 9, 5, 2, GILT);
        rect(c, cx - 2, base - 9, 5, 1, GILT_LIT);
        rect(c, cx - 4, base, 9, 1, 0x50000000);
    }

    /**
     * The thread a spell is copied along: from the stand and the blotter to the quill, and down
     * to the copy. While a copy is ready it glows and motes run along it towards the copy.
     */
    public static GuiTexture thread(UIElement originalTray, UIElement blankTray, UIElement copy, BooleanSupplier ready) {
        return (context, x, y, w, h) -> {
            boolean lit = ready.getAsBoolean();
            float cx = Math.round(copy.getPositionX() + copy.getSizeWidth() / 2);
            float base = Math.round(copy.getPositionY()) - 9;
            float dy = base - 4;
            float lx = Math.round(originalTray.getPositionX() + originalTray.getSizeWidth());
            float rx = Math.round(blankTray.getPositionX());
            float by = Math.round(copy.getPositionY());
            int line = lit ? GLOW : THREAD_DIM;
            if (lit) {
                rect(context, lx, dy - 1, cx - 4 - lx, 3, GLOW_SOFT);
                rect(context, cx + 5, dy - 1, rx - cx - 5, 3, GLOW_SOFT);
                rect(context, cx - 1, base, 3, by - base, GLOW_SOFT);
                rect(context, lx, dy, cx - 4 - lx, 1, line);
                rect(context, cx + 5, dy, rx - cx - 5, 1, line);
                rect(context, cx, base, 1, by - base, line);
            } else {
                for (float sx = lx + 1; sx < cx - 4; sx += 3) rect(context, sx, dy, 1, 1, line);
                for (float sx = rx - 2; sx > cx + 4; sx -= 3) rect(context, sx, dy, 1, 1, line);
            }
            inkwell(context, cx, base, lit);
            if (!lit) return;
            // Motes: in from each side to the inkwell, then down to the copy.
            long now = System.currentTimeMillis();
            float in = cx - 4 - lx, down = by - base;
            for (float offset : new float[]{0, 0.5f}) {
                float t = ((now % 1600) / 1600f + offset) % 1f;
                float a = t * (in + down);
                if (a < in) {
                    rect(context, Math.round(lx + a) - 1, dy - 1, 2, 3, 0xFFFFF4D0);
                    rect(context, Math.round(rx - a), dy - 1, 2, 3, 0xFFFFF4D0);
                } else {
                    rect(context, cx - 1, Math.round(base + a - in) - 1, 3, 2, 0xFFFFF4D0);
                }
            }
        };
    }

    /** A gilt plate the table's name is engraved on. */
    public static IGuiTexture plate() {
        return (GuiTexture) (context, x, y, width, height) -> {
            float rx = Math.round(x), ry = Math.round(y), w = Math.round(width), h = Math.round(height);
            rect(context, rx, ry, w, h, GILT_DARK);
            rect(context, rx + 1, ry + 1, w - 2, h - 2, GILT);
            rect(context, rx + 1, ry + 1, w - 2, 1, GILT_LIT);
            rect(context, rx + 1, ry + h - 2, w - 2, 1, 0xFFB98A2C);
            rect(context, rx + 2, ry + h / 2 - 1, 2, 2, GILT_DARK);
            rect(context, rx + w - 4, ry + h / 2 - 1, 2, 2, GILT_DARK);
        };
    }

    /** Marks a part of the screen whose text is written in ink on paper. */
    public static void inked(UIElement element) {
        AlchemyArt.inked(element);
    }
}
