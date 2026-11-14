package dev.logos.client;

import java.util.function.BooleanSupplier;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

/**
 * The copy table's screen drawn like the table itself: the walnut box with brass corners (shared
 * with the alchemy table), two iron-framed trays with brass clips on its top (the original's page
 * glowing with its runes on the left, the empty paper on the right), the dark banner from its
 * front holding the copy, and the golden thread the spell runs along from the original to the
 * copy while there is one to take. Everything is laid in whole GUI pixels. The screen is also
 * built on the server, where these are only created, never drawn.
 */
public final class CopyTableArt {
    private static final int IRON_DEEP = 0xFF16141A, IRON = 0xFF36323C, IRON_LIT = 0xFF57515F, RECESS = 0xFF221F26;
    private static final int BRASS = 0xFFB8862E, BRASS_LIT = 0xFFE8C46A, BRASS_DARK = 0xFF6E4A16;
    private static final int GLOW = 0xFFF2C25A, GLOW_SOFT = 0x40FFC860, RUNE = 0xFFC9822A;
    private static final int CLOTH = 0xFF29252F, CLOTH_DARK = 0xFF1B1820;
    private static final int THREAD_DIM = 0xFF5A4630;
    /** Brass-coloured writing on the dark wood. */
    public static final int ON_WOOD = BRASS_LIT;

    private CopyTableArt() {}

    /** Two plans for one size: the tray with its paper, and without. */
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

    /** The screen: the table's walnut boards with brass corner brackets. */
    public static IGuiTexture box() {
        return AlchemyArt.bench();
    }

    /** The table's top the trays are sunk into. */
    public static IGuiTexture top() {
        return AlchemyArt.benchTop();
    }

    /** The parchment the hint and the inventory are kept on. */
    public static IGuiTexture ledger() {
        return AlchemyArt.ledger();
    }

    // ---- the trays -------------------------------------------------------------------------------

    /** The left tray: the original's page glowing with its runes while it lies there. */
    public static IGuiTexture originalTray(BooleanSupplier filled) {
        return switched((w, h, on) -> tray(w, h, on, true), filled);
    }

    /** The right tray: a little stack of empty paper while there is some. */
    public static IGuiTexture blankTray(BooleanSupplier filled) {
        return switched((w, h, on) -> tray(w, h, on, false), filled);
    }

    private static Manuscript.Plan tray(float w, float h, boolean filled, boolean runes) {
        Manuscript.Plan p = new Manuscript.Plan(runes ? 57 : 61);
        p.rect(0, 0, w, h, IRON_DEEP);
        p.rect(1, 1, w - 2, h - 2, IRON);
        p.rect(1, 1, w - 2, 1, IRON_LIT);
        p.rect(1, 1, 1, h - 2, IRON_LIT);
        p.rect(3, 3, w - 6, h - 6, RECESS);
        p.rect(3, 3, w - 6, 1, 0xFF141217);
        float px = 6, py = 6, pw = w - 12, ph = h - 12;
        if (!filled) {
            // Where a page would lie: a faint dashed outline.
            for (float x = px; x < px + pw; x += 4) {
                p.rect(x, py, 2, 1, 0xFF4A4452);
                p.rect(x, py + ph - 1, 2, 1, 0xFF4A4452);
            }
            for (float y = py; y < py + ph; y += 4) {
                p.rect(px, y, 1, 2, 0xFF4A4452);
                p.rect(px + pw - 1, y, 1, 2, 0xFF4A4452);
            }
        } else if (runes) {
            p.rect(px - 1, py - 1, pw + 2, ph + 2, GLOW_SOFT);
            p.rect(px, py, pw, ph, 0xFFF5DDA6);
            p.rect(px + 2, py + 2, pw - 4, 1, RUNE);
            p.rect(px + 2, py + ph - 3, pw - 4, 1, RUNE);
            p.rect(px + 2, py + 2, 1, ph - 4, RUNE);
            p.rect(px + pw - 3, py + 2, 1, ph - 4, RUNE);
            // Two rows of runes above and below where the slot's item sits.
            for (float row : new float[]{py + 4, py + ph - 7}) {
                for (float x = px + 4, n = 0; x < px + pw - 5; x += 4, n++) glyph(p, x, row, (int) (n + row), RUNE);
            }
            // The glow leaking out at the corners.
            for (float[] c : new float[][]{{px, py}, {px + pw - 2, py}, {px, py + ph - 2}, {px + pw - 2, py + ph - 2}}) p.rect(c[0], c[1], 2, 2, GLOW);
        } else {
            p.rect(px + 2, py + 2, pw, ph, 0xFFB8AC94);
            p.rect(px + 1, py + 1, pw, ph, 0xFFD6CBB2);
            p.rect(px, py, pw, ph, 0xFFEFE6D2);
            int mark = 0xFFB5A584;
            for (float[] c : new float[][]{{px + 2, py + 2, 1, 1}, {px + pw - 3, py + 2, -1, 1}, {px + 2, py + ph - 3, 1, -1}, {px + pw - 3, py + ph - 3, -1, -1}}) {
                p.rect(c[2] > 0 ? c[0] : c[0] - 2, c[1], 3, 1, mark);
                p.rect(c[0], c[3] > 0 ? c[1] : c[1] - 2, 1, 3, mark);
            }
        }
        // Brass clips holding the page: a bar at the top, two corner clips at the bottom.
        float cx = Math.round(w / 2);
        clip(p, cx - 6, 2, 12, 4);
        clip(p, 2, h - 7, 7, 5);
        clip(p, w - 9, h - 7, 7, 5);
        return p;
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

    private static void clip(Manuscript.Plan p, float x, float y, float w, float h) {
        p.rect(x, y, w, h, BRASS_DARK);
        p.rect(x, y, w - 1, h - 1, BRASS);
        p.rect(x, y, w - 1, 1, BRASS_LIT);
    }

    /** Behind a slot on paper or in the banner: a shallow square sunk into it. */
    public static IGuiTexture socket(boolean onPaper) {
        return (GuiTexture) (context, x, y, w, h) -> {
            int edge = onPaper ? 0x663B2614 : 0xFF0E0C11, fill = onPaper ? 0x223B2614 : 0xFF141217;
            float rx = Math.round(x), ry = Math.round(y), rw = Math.round(w), rh = Math.round(h);
            DrawerHelperClient.drawSolidRect(context, rx, ry, rw, rh, fill);
            DrawerHelperClient.drawSolidRect(context, rx, ry, rw, 1, edge);
            DrawerHelperClient.drawSolidRect(context, rx, ry, 1, rh, edge);
            if (!onPaper) {
                DrawerHelperClient.drawSolidRect(context, rx, ry + rh - 1, rw, 1, BRASS_DARK);
                DrawerHelperClient.drawSolidRect(context, rx + rw - 1, ry, 1, rh, BRASS_DARK);
            }
        };
    }

    // ---- the banner and the thread ---------------------------------------------------------------

    /** The dark banner from the table's front, its brass rod, borders and diamond; the copy is taken from it. */
    public static IGuiTexture banner() {
        Manuscript.Plan[] plan = new Manuscript.Plan[1];
        float[] size = {-1, -1};
        return (GuiTexture) (context, x, y, width, height) -> {
            float w = Math.round(width), h = Math.round(height);
            if (plan[0] == null || w != size[0] || h != size[1]) {
                size[0] = w;
                size[1] = h;
                plan[0] = banner(w, h);
            }
            plan[0].draw(context, Math.round(x), Math.round(y));
        };
    }

    private static Manuscript.Plan banner(float w, float h) {
        Manuscript.Plan p = new Manuscript.Plan(67);
        float body = h - 5;
        p.rect(1, 2, w - 2, body - 2, CLOTH);
        p.rect(1, 2, 1, body - 2, CLOTH_DARK);
        // A notched hem: the cloth runs on down the sides, leaving a V in the middle.
        float cx = Math.round(w / 2);
        for (int i = 0; i < 5; i++) {
            float span = (w - 2) / 2 - (5 - i);
            p.rect(1, body + i - 1, span, 1, CLOTH);
            p.rect(w - 1 - span, body + i - 1, span, 1, CLOTH);
        }
        // Brass borders down both sides and the rod it hangs from.
        p.rect(3, 4, 1, body - 4, BRASS);
        p.rect(w - 4, 4, 1, body - 4, BRASS);
        p.rect(-2, 0, w + 4, 3, BRASS);
        p.rect(-2, 0, w + 4, 1, BRASS_LIT);
        p.rect(-2, 2, w + 4, 1, BRASS_DARK);
        p.rect(-3, -1, 2, 5, BRASS_DARK);
        p.rect(w + 1, -1, 2, 5, BRASS_DARK);
        // The brass diamond under the copy.
        diamondPlan(p, cx, body - 7, 3, BRASS, BRASS_DARK);
        return p;
    }

    private static void diamondPlan(Manuscript.Plan p, float cx, float cy, int r, int fill, int core) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            p.rect(cx - half, cy + i, 2 * half + 1, 1, fill);
        }
        p.rect(cx, cy, 1, 1, core);
    }

    private static void diamond(GUIContext c, float cx, float cy, int r, int fill) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            DrawerHelperClient.drawSolidRect(c, cx - half, cy + i, 2 * half + 1, 1, fill);
        }
    }

    /**
     * The thread a spell is copied along: from each tray to a brass diamond above the banner, and
     * down to the copy. While a copy is ready it glows and motes run along it towards the copy.
     */
    public static GuiTexture thread(UIElement originalTray, UIElement blankTray, UIElement copy, BooleanSupplier ready) {
        return (context, x, y, w, h) -> {
            boolean lit = ready.getAsBoolean();
            float cx = Math.round(copy.getPositionX() + copy.getSizeWidth() / 2);
            float dy = Math.round(copy.getPositionY()) - 10;
            float lx = Math.round(originalTray.getPositionX() + originalTray.getSizeWidth());
            float rx = Math.round(blankTray.getPositionX());
            float by = Math.round(copy.getPositionY());
            int line = lit ? GLOW : THREAD_DIM;
            if (lit) {
                DrawerHelperClient.drawSolidRect(context, lx, dy - 1, cx - lx, 3, GLOW_SOFT);
                DrawerHelperClient.drawSolidRect(context, cx, dy - 1, rx - cx, 3, GLOW_SOFT);
                DrawerHelperClient.drawSolidRect(context, cx - 1, dy, 3, by - dy, GLOW_SOFT);
                DrawerHelperClient.drawSolidRect(context, lx, dy, cx - lx, 1, line);
                DrawerHelperClient.drawSolidRect(context, cx, dy, rx - cx, 1, line);
                DrawerHelperClient.drawSolidRect(context, cx, dy, 1, by - dy, line);
            } else {
                for (float sx = lx + 1; sx < rx; sx += 3) DrawerHelperClient.drawSolidRect(context, sx, dy, 1, 1, line);
                for (float sy = dy + 6; sy < by; sy += 3) DrawerHelperClient.drawSolidRect(context, cx, sy, 1, 1, line);
            }
            diamond(context, cx, dy, 5, lit ? BRASS_LIT : BRASS_DARK);
            diamond(context, cx, dy, 3, lit ? 0xFFFFF0C0 : BRASS);
            if (lit) DrawerHelperClient.drawSolidRect(context, cx - 1, dy - 1, 3, 3, 0xFFFFFFFF);
            if (!lit) return;
            // Motes: in from each tray to the diamond, then down to the copy.
            long now = System.currentTimeMillis();
            float in = cx - lx, down = by - dy;
            for (float offset : new float[]{0, 0.5f}) {
                float t = ((now % 1600) / 1600f + offset) % 1f;
                float a = t * (in + down);
                if (a < in) {
                    DrawerHelperClient.drawSolidRect(context, Math.round(lx + a) - 1, dy - 1, 2, 3, 0xFFFFF4D0);
                    DrawerHelperClient.drawSolidRect(context, Math.round(rx - a), dy - 1, 2, 3, 0xFFFFF4D0);
                } else {
                    DrawerHelperClient.drawSolidRect(context, cx - 1, Math.round(dy + a - in) - 1, 3, 2, 0xFFFFF4D0);
                }
            }
        };
    }

    /** A brass plate the table's name is engraved on. */
    public static IGuiTexture plate() {
        return (GuiTexture) (context, x, y, width, height) -> {
            float rx = Math.round(x), ry = Math.round(y), w = Math.round(width), h = Math.round(height);
            DrawerHelperClient.drawSolidRect(context, rx, ry, w, h, BRASS_DARK);
            DrawerHelperClient.drawSolidRect(context, rx + 1, ry + 1, w - 2, h - 2, BRASS);
            DrawerHelperClient.drawSolidRect(context, rx + 1, ry + 1, w - 2, 1, BRASS_LIT);
            DrawerHelperClient.drawSolidRect(context, rx + 1, ry + h - 2, w - 2, 1, 0xFF8E6420);
            DrawerHelperClient.drawSolidRect(context, rx + 2, ry + h / 2 - 1, 2, 2, BRASS_DARK);
            DrawerHelperClient.drawSolidRect(context, rx + w - 4, ry + h / 2 - 1, 2, 2, BRASS_DARK);
        };
    }

    /** Marks a part of the screen whose text is written in ink on paper. */
    public static void inked(UIElement element) {
        AlchemyArt.inked(element);
    }
}
