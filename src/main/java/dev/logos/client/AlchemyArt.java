package dev.logos.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import org.joml.Vector2f;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

import dev.logos.alchemy.Process;

/**
 * The alchemy table's screen drawn like the table itself: a walnut bench with brass fittings,
 * each process laid on it as its own apparatus (the fermenting crock, the brass basin, the
 * alembic over its burner, the settling tray), products piped between them and into the product
 * crate. Everything is laid in whole GUI pixels, planned once for a size and replayed. The screen
 * is also built on the server, where these are only created, never drawn.
 */
public final class AlchemyArt {
    private static final int WALNUT_DEEP = 0xFF24160D, WALNUT = 0xFF3F2818, WALNUT_LIT = 0xFF4A3020, GRAIN = 0x603A2416;
    private static final int OAK = 0xFF6E4526, OAK_DARK = 0xFF56351C, OUTLINE = 0xFF1E1209;
    private static final int BRASS = 0xFFB8862E, BRASS_LIT = 0xFFE8C46A, BRASS_DARK = 0xFF6E4A16;
    private static final int GLASS_EDGE = 0xD0D8F0F8, GLASS = 0x389CC9D9;
    /** Ink on brass plates. */
    public static final int ENGRAVED = 0xFF2A1A08;

    private AlchemyArt() {}

    /** The colour of what works inside each apparatus, for its level, its gauge and the tubes it feeds. */
    public static int liquid(Process process) {
        return switch (process) {
            case FERMENT -> 0xFFC98A2E;
            case DISSOLVE -> 0xFF3FA08A;
            case DISTILL -> 0xFFC0504A;
            case SETTLE -> 0xFF8FBFD6;
            case CALCINE -> 0xFFD8743A;
            case PURIFY -> 0xFF6FB8E0;
        };
    }

    /** A texture whose drawing is planned for the size it is first drawn at, and again when that changes. */
    private interface Planner {
        Manuscript.Plan plan(float w, float h);
    }

    private static GuiTexture cached(Planner planner) {
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

    // ---- the bench -------------------------------------------------------------------------------

    /** The screen: walnut boards in a dark frame, brass brackets at the corners. */
    public static IGuiTexture bench() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(11);
            boards(p, 0, 0, w, h, WALNUT, WALNUT_LIT);
            int frame = 6;
            p.rect(0, 0, w, frame, WALNUT_DEEP);
            p.rect(0, h - frame, w, frame, WALNUT_DEEP);
            p.rect(0, frame, frame, h - 2 * frame, WALNUT_DEEP);
            p.rect(w - frame, frame, frame, h - 2 * frame, WALNUT_DEEP);
            p.rect(frame, frame, w - 2 * frame, 1, 0xFF6B4528);
            p.rect(frame, frame, 1, h - 2 * frame, 0xFF6B4528);
            for (float[] c : new float[][]{{0, 0, 1, 1}, {w, 0, -1, 1}, {0, h, 1, -1}, {w, h, -1, -1}}) bracket(p, c[0], c[1], (int) c[2], (int) c[3]);
            return p;
        });
    }

    /** The bench top the apparatus stands on: lighter boards, sunk a little into the bench. */
    public static IGuiTexture benchTop() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(23);
            boards(p, 0, 0, w, h, 0xFF5A3A22, 0xFF63412A);
            p.rect(0, 0, w, 2, 0x80000000);
            p.rect(0, 0, 2, h, 0x80000000);
            p.rect(0, h - 1, w, 1, 0x406B4528);
            return p;
        });
    }

    /** Horizontal boards with seams, staggered joints, grain and a brass nail either side of each joint. */
    private static void boards(Manuscript.Plan p, float x0, float y0, float w, float h, int dark, int lit) {
        int board = 22, n = 0;
        for (float y = y0; y < y0 + h; y += board, n++) {
            float bh = Math.min(board, y0 + h - y);
            p.rect(x0, y, w, bh, n % 2 == 0 ? dark : lit);
            p.rect(x0, y, w, 1, 0xA0000000);
            for (int g = 0; g < w / 30; g++) {
                float gx = x0 + p.roll(n * 97 + g * 7, (int) Math.max(1, w - 20));
                float gy = y + 3 + p.roll(n * 97 + g * 7 + 1, (int) Math.max(1, bh - 5));
                p.rect(gx, gy, 6 + p.roll(n * 97 + g * 7 + 2, 22), 1, GRAIN);
            }
            for (float jx = x0 + 40 + p.roll(n * 13, 90); jx < x0 + w - 10; jx += 110 + p.roll(n * 13 + (int) jx, 70)) {
                p.rect(jx, y + 1, 1, bh - 1, 0xA0000000);
                p.rect(jx - 3, y + bh / 2 - 1, 2, 2, BRASS_DARK);
                p.rect(jx + 2, y + bh / 2 - 1, 2, 2, BRASS_DARK);
            }
        }
    }

    /** A brass L-bracket with rivets, its corner at (x, y), its arms running along (dx, dy). */
    private static void bracket(Manuscript.Plan p, float x, float y, int dx, int dy) {
        float bx = dx > 0 ? x : x - 14, by = dy > 0 ? y : y - 14;
        float ax = dx > 0 ? x : x - 4, ay = dy > 0 ? y : y - 4;
        p.rect(bx, ay, 14, 4, BRASS);
        p.rect(ax, by, 4, 14, BRASS);
        p.rect(bx, dy > 0 ? ay : ay + 3, 14, 1, dy > 0 ? BRASS_LIT : BRASS_DARK);
        p.rect(dx > 0 ? ax : ax + 3, by, 1, 14, dx > 0 ? BRASS_LIT : BRASS_DARK);
        p.rect(x + dx * 9 - (dx < 0 ? 1 : 0), y + dy * 2 - (dy < 0 ? 1 : 0), 1, 1, BRASS_DARK);
        p.rect(x + dx * 2 - (dx < 0 ? 1 : 0), y + dy * 9 - (dy < 0 ? 1 : 0), 1, 1, BRASS_DARK);
    }

    /** The ledger page the table's storage is kept on, clipped to the bench with brass. */
    public static IGuiTexture ledger() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(31);
            for (float y = 4; y < h; y += 3) p.hatch(w + 1, y, (int) Math.min(3, h + 2 - y), 0x60000000);
            p.rect(1, 1, w - 2, h - 2, 0xFFF1E2BC);
            p.hStroke(0, w, 0, 0, Manuscript.INK_FADED, 1);
            p.hStroke(0, w, h - 1, 0, Manuscript.INK_FADED, 2);
            p.vStroke(0, 0, h, 0, Manuscript.INK_FADED, 3);
            p.vStroke(w - 1, 0, h, 0, Manuscript.INK_FADED, 4);
            float cx = Math.round(w / 2);
            p.rect(cx - 12, -3, 24, 7, BRASS);
            p.rect(cx - 12, -3, 24, 1, BRASS_LIT);
            p.rect(cx - 12, 3, 24, 1, BRASS_DARK);
            p.rect(cx - 9, 0, 2, 2, BRASS_DARK);
            p.rect(cx + 7, 0, 2, 2, BRASS_DARK);
            return p;
        });
    }

    // ---- brass plates ----------------------------------------------------------------------------

    /** A brass plate like those on the table's front; the process's sign and name are written on it in dark ink. */
    public static IGuiTexture plate(Process process) {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(41);
            brassPlate(p, 0, 0, w, h);
            return p;
        });
    }

    private static void brassPlate(Manuscript.Plan p, float x, float y, float w, float h) {
        p.rect(x, y, w, h, BRASS_DARK);
        p.rect(x + 1, y + 1, w - 2, h - 2, BRASS);
        p.rect(x + 1, y + 1, w - 2, 1, BRASS_LIT);
        p.rect(x + 1, y + 1, 1, h - 2, BRASS_LIT);
        p.rect(x + 1, y + h - 2, w - 2, 1, 0xFF8E6420);
        p.rect(x + w - 3, y + 2, 1, 1, BRASS_DARK);
        p.rect(x + w - 3, y + h - 3, 1, 1, BRASS_DARK);
    }

    // ---- the apparatus ---------------------------------------------------------------------------

    /**
     * A process card drawn as the apparatus that works it. What it holds rises in it as the
     * current craft progresses ({@code progress} from 0 to 1). A brass nameplate sits under the
     * card's title row ({@code titleHeight} tall).
     */
    public static IGuiTexture vessel(Process process, DoubleSupplier progress, float titleHeight) {
        GuiTexture body = cached((w, h) -> switch (process) {
            case FERMENT -> crock(w, h, titleHeight);
            case DISSOLVE -> basin(w, h, titleHeight);
            case DISTILL -> alembic(w, h, titleHeight);
            case SETTLE -> tray(w, h, titleHeight);
            case CALCINE -> furnace(w, h, titleHeight);
            case PURIFY -> cistern(w, h, titleHeight);
        });
        int level = liquid(process);
        return (GuiTexture) (context, x, y, width, height) -> {
            body.draw(context, x, y, width, height);
            float inside = height - titleHeight - 10;
            float filled = (float) Math.clamp(progress.getAsDouble(), 0, 1) * inside;
            if (filled < 1 || !(context instanceof GUIContext gui)) return;
            float top = y + height - 5 - filled;
            DrawerHelperClient.drawSolidRect(gui, x + 5, top, width - 10, filled, (level & 0x00FFFFFF) | 0x30000000);
            DrawerHelperClient.drawSolidRect(gui, x + 5, top, width - 10, 1, (level & 0x00FFFFFF) | 0x90000000);
        };
    }

    private static void nameplate(Manuscript.Plan p, float w, float titleHeight) {
        brassPlate(p, 3, 3, w - 6, titleHeight);
    }

    /** 🝤 The fermenting crock: oak staves, brass hoops, a lid with its glass airlock and red stopper. */
    private static Manuscript.Plan crock(float w, float h, float title) {
        Manuscript.Plan p = new Manuscript.Plan(51);
        p.rect(0, 0, w, h, OUTLINE);
        p.rect(1, 1, w - 2, h - 2, OAK);
        for (float x = 8; x < w - 4; x += 10) p.rect(x, 1, 1, h - 2, OAK_DARK);
        // Staves bulge a little at the middle.
        p.rect(-1, h * 0.3f, 1, h * 0.4f, OUTLINE);
        p.rect(w, h * 0.3f, 1, h * 0.4f, OUTLINE);
        // Hoops at the rim and the foot, clear of the card's writing.
        for (float y : new float[]{-1, h - 2}) {
            p.rect(-1, y, w + 2, 3, BRASS);
            p.rect(-1, y, w + 2, 1, BRASS_LIT);
            p.rect(-1, y + 2, w + 2, 1, BRASS_DARK);
        }
        // Lid and airlock.
        p.rect(4, -4, w - 8, 4, OAK_DARK);
        p.rect(4, -4, w - 8, 1, 0xFF8A5A33);
        float cx = Math.round(w / 2);
        p.rect(cx - 3, -6, 6, 2, BRASS);
        p.rect(cx - 1, -16, 2, 10, GLASS_EDGE);
        p.rect(cx + 1, -16, 4, 2, GLASS_EDGE);
        p.rect(cx + 3, -20, 2, 4, GLASS_EDGE);
        p.rect(cx + 2, -23, 4, 3, 0xFFB03A2E);
        p.rect(cx + 2, -23, 4, 1, 0xFFD8604E);
        nameplate(p, w, title);
        return p;
    }

    /** 🝡 The brass reaction basin: a deep bowl of dark water inside a riveted brass wall with a rolled lip. */
    private static Manuscript.Plan basin(float w, float h, float title) {
        Manuscript.Plan p = new Manuscript.Plan(61);
        int round = 8;
        for (int i = 0; i < round; i++) {
            float cut = round - (float) Math.floor(Math.sqrt(round * round - (round - i - 0.5) * (round - i - 0.5)));
            p.rect(cut, h - round + i, w - 2 * cut, 1, BRASS_DARK);
        }
        p.rect(0, 0, w, h - round, BRASS_DARK);
        for (int i = 0; i < round - 3; i++) {
            float cut = round - (float) Math.floor(Math.sqrt(round * round - (round - i - 0.5) * (round - i - 0.5)));
            p.rect(3 + cut, h - round + i, w - 6 - 2 * cut, 1, 0xFF23474A);
        }
        p.rect(3, 3, w - 6, h - round - 3, 0xFF23474A);
        p.rect(1, 1, 2, h - round, BRASS);
        p.rect(w - 3, 1, 2, h - round, BRASS);
        p.rect(1, 1, 1, h - round, BRASS_LIT);
        // The rolled lip.
        p.rect(-3, -2, w + 6, 5, BRASS);
        p.rect(-3, -2, w + 6, 1, BRASS_LIT);
        p.rect(-3, 2, w + 6, 1, BRASS_DARK);
        for (float x = 4; x < w - 2; x += 14) {
            p.rect(x, 0, 2, 2, BRASS_DARK);
            p.rect(x, 0, 1, 1, BRASS_LIT);
        }
        for (int i = 0; i < 5; i++) {
            float bx = 10 + p.roll(i * 5, (int) Math.max(1, w - 20)), by = title + 12 + p.roll(i * 5 + 1, 10);
            p.ring(bx, by, 1 + p.roll(i * 5 + 2, 2), 0x70CFF0E8);
        }
        nameplate(p, w, title);
        return p;
    }

    /** 🝠 The alembic: a glass flask with rounded shoulders, its neck and arm running out to the right, a burner beneath. */
    private static Manuscript.Plan alembic(float w, float h, float title) {
        Manuscript.Plan p = new Manuscript.Plan(71);
        int shoulder = 12;
        for (int i = 0; i < shoulder; i++) {
            float cut = shoulder - (float) Math.floor(Math.sqrt(shoulder * shoulder - (shoulder - i - 0.5) * (shoulder - i - 0.5)));
            p.rect(cut, i, w - 2 * cut, 1, GLASS);
            p.rect(cut, i, 1, 1, GLASS_EDGE);
            p.rect(w - cut - 1, i, 1, 1, GLASS_EDGE);
        }
        p.rect(0, shoulder, w, h - shoulder, GLASS);
        p.hStroke(shoulder, w - shoulder, 0, 0, GLASS_EDGE, 1);
        p.rect(0, shoulder, 1, h - shoulder, GLASS_EDGE);
        p.rect(w - 1, shoulder, 1, h - shoulder, GLASS_EDGE);
        p.rect(0, h - 1, w, 1, GLASS_EDGE);
        p.rect(4, title + 8, 1, h - title - 20, 0x90FFFFFF);
        p.rect(6, title + 10, 1, 10, 0x60FFFFFF);
        // Neck, brass collar and the arm to the condenser.
        float cx = Math.round(w / 2);
        p.rect(cx - 4, -12, 8, 12, GLASS);
        p.rect(cx - 4, -12, 1, 12, GLASS_EDGE);
        p.rect(cx + 3, -12, 1, 12, GLASS_EDGE);
        p.rect(cx - 6, -15, 12, 3, BRASS);
        p.rect(cx - 6, -15, 12, 1, BRASS_LIT);
        p.rect(cx + 6, -14, w / 2 - 2, 2, GLASS_EDGE);
        p.rect(w + 2, -14, 2, 22, GLASS_EDGE);
        p.rect(w + 1, 8, 4, 2, BRASS);
        p.rect(w + 2, 11, 2, 2, 0xC0C0504A);
        // The burner.
        p.rect(cx - 14, h + 5, 28, 4, BRASS);
        p.rect(cx - 14, h + 5, 28, 1, BRASS_LIT);
        p.rect(cx - 12, h + 9, 2, 4, BRASS_DARK);
        p.rect(cx + 10, h + 9, 2, 4, BRASS_DARK);
        p.rect(cx - 5, h + 1, 10, 4, 0xFFE07A2A);
        p.rect(cx - 3, h, 6, 3, 0xFFF6C24A);
        p.rect(cx - 1, h - 1, 2, 2, 0xFFFFF0B0);
        nameplate(p, w, title);
        return p;
    }

    /**
     * 🜂 The calcining furnace: firebrick walls bound in iron round a dark chamber that glows from
     * below, an iron grate over live coals at its foot, a clay crucible of molten matter on top and
     * a short flue trailing smoke.
     */
    private static Manuscript.Plan furnace(float w, float h, float title) {
        Manuscript.Plan p = new Manuscript.Plan(101);
        int mortar = 0xFF3A2C26, brick = 0xFF7A4434, brickLit = 0xFF8E5440, iron = 0xFF3C3A3E, ironLit = 0xFF6A686E;
        // Firebrick, laid in courses with the joints staggered.
        p.rect(0, 0, w, h, mortar);
        for (float y = 1, row = 0; y < h - 1; y += 5, row++) {
            float rowH = Math.min(4, h - 1 - y);
            for (float x = 1 - (row % 2 == 0 ? 0 : 5); x < w - 1; x += 10) {
                float bx = Math.max(1, x), bw = Math.min(x + 9, w - 1) - bx;
                p.rect(bx, y, bw, rowH, p.roll((int) (x * 7 + row * 13), 3) == 0 ? brickLit : brick);
                p.rect(bx, y, bw, 1, 0x30FFFFFF);
            }
        }
        // The chamber: dark, hottest at the bottom.
        float top = title + 5, bottom = h - 9;
        p.rect(4, top, w - 8, bottom - top, 0xFF221814);
        int[] glow = {0x18FF8A30, 0x28FF8A30, 0x40FF9A3A, 0x60FFA640};
        for (int i = 0; i < glow.length; i++) p.rect(4, bottom - 3 * (glow.length - i), w - 8, 3, glow[i]);
        p.rect(3, top - 1, w - 6, 1, OUTLINE);
        p.rect(3, top, 1, bottom - top, OUTLINE);
        p.rect(w - 4, top, 1, bottom - top, OUTLINE);
        // The grate and the coals under it.
        p.rect(3, bottom, w - 6, 6, 0xFF2A1A12);
        for (float x = 5; x < w - 6; x += 4) {
            int coal = p.roll((int) x, 3) == 0 ? 0xFFFFD27A : p.roll((int) x + 1, 2) == 0 ? 0xFFE8742A : 0xFFB0401E;
            p.rect(x, bottom + 3, 3, 2, coal);
        }
        p.rect(3, bottom, w - 6, 1, iron);
        for (float x = 6; x < w - 6; x += 6) p.rect(x, bottom, 1, 3, iron);
        // Iron bands at the corners, riveted.
        for (float[] c : new float[][]{{0, 0}, {w - 5, 0}, {0, h - 5}, {w - 5, h - 5}}) {
            p.rect(c[0], c[1], 5, 5, iron);
            p.rect(c[0] + 1, c[1] + 1, 3, 1, ironLit);
            p.rect(c[0] + 2, c[1] + 3, 1, 1, ironLit);
        }
        // The firebox under the floor: a brick arch round a mouth of live coals.
        float cx = Math.round(w / 2);
        p.rect(cx - 18, h, 36, 12, mortar);
        p.rect(cx - 17, h, 34, 3, brick);
        p.rect(cx - 17, h + 4, 8, 7, brickLit);
        p.rect(cx + 9, h + 4, 8, 7, brick);
        p.rect(cx - 8, h + 4, 16, 7, 0xFF1A100C);
        p.rect(cx - 7, h + 5, 14, 1, OUTLINE);
        p.rect(cx - 7, h + 8, 14, 3, 0xFFB0401E);
        p.rect(cx - 5, h + 8, 3, 2, 0xFFE8742A);
        p.rect(cx + 1, h + 9, 4, 2, 0xFFFFD27A);
        p.rect(cx - 2, h + 6, 3, 2, 0x90FFB34A);
        p.rect(cx - 19, h + 12, 38, 2, iron);
        p.rect(cx - 19, h + 12, 38, 1, ironLit);
        // A clay crucible of molten matter on the furnace top, a tongs leaning on it.
        p.rect(cx - 11, -10, 22, 10, 0xFF8A5A3A);
        p.rect(cx - 10, -9, 3, 8, 0xFFA87650);
        p.rect(cx - 9, -1, 18, 1, 0xFF5A3A24);
        p.rect(cx - 13, -12, 26, 3, 0xFFB8865A);
        p.rect(cx - 13, -12, 26, 1, 0xFFD0A070);
        p.rect(cx - 10, -11, 20, 1, 0xFFE8742A);
        p.rect(cx - 6, -11, 9, 1, 0xFFFFD27A);
        p.rect(cx - 3, -14, 2, 2, 0x90FFB34A);
        p.rect(cx + 2, -17, 1, 2, 0x60FFB34A);
        for (int i = 0; i < 9; i++) p.rect(cx + 12 + i, -14 + i, 1, 2, i < 2 ? ironLit : iron);
        // The flue, trailing smoke.
        p.rect(w - 22, -16, 12, 16, mortar);
        for (int i = 0; i < 3; i++) p.rect(w - 21 + (i % 2) * 2, -15 + 5 * i, 8, 4, i % 2 == 0 ? brick : brickLit);
        p.rect(w - 24, -18, 16, 3, iron);
        p.rect(w - 24, -18, 16, 1, ironLit);
        p.rect(w - 18, -22, 4, 3, 0x90A8A4A0);
        p.rect(w - 16, -26, 5, 3, 0x70A8A4A0);
        p.rect(w - 12, -30, 4, 3, 0x50A8A4A0);
        p.rect(w - 9, -33, 3, 2, 0x30A8A4A0);
        nameplate(p, w, title);
        return p;
    }

    /**
     * 🝣 The purifying cistern: a well of clear water in pale dressed stone with pewter corners, its
     * floor laid with the filter beds (charcoal, sand, gravel); above it a linen cloth stretched in an
     * oak frame drains through a glass funnel, and a pewter tap at the side lets the clean water out.
     */
    private static Manuscript.Plan cistern(float w, float h, float title) {
        Manuscript.Plan p = new Manuscript.Plan(111);
        int stone = 0xFFB8B0A0, stoneLit = 0xFFD2CBBB, joint = 0xFF8A8274;
        int pewter = 0xFF8C969E, pewterLit = 0xFFD0D8DE, pewterDark = 0xFF525C66;
        // Dressed stone, laid in long courses.
        p.rect(0, 0, w, h, joint);
        for (float y = 1, row = 0; y < h - 1; y += 7, row++) {
            float rowH = Math.min(6, h - 1 - y);
            for (float x = 1 - (row % 2 == 0 ? 0 : 9); x < w - 1; x += 18) {
                float bx = Math.max(1, x), bw = Math.min(x + 17, w - 1) - bx;
                p.rect(bx, y, bw, rowH, p.roll((int) (x * 5 + row * 11), 4) == 0 ? stoneLit : stone);
                p.rect(bx, y, bw, 1, 0x40FFFFFF);
            }
        }
        // The well of clear water, darker with depth.
        p.rect(3, 3, w - 6, h - 6, 0xFF2C5466);
        p.rect(3, 3, w - 6, (h - 6) / 2, 0xFF346274);
        p.rect(3, 3, 1, h - 6, OUTLINE);
        p.rect(w - 4, 3, 1, h - 6, OUTLINE);
        p.rect(5, title + 8, 1, h - title - 26, 0x70FFFFFF);
        // The filter beds on its floor: charcoal over sand over gravel.
        p.rect(3, h - 8, w - 6, 2, 0xFF26262A);
        p.rect(3, h - 6, w - 6, 2, 0xFFD8C890);
        p.rect(3, h - 6, w - 6, 1, 0xFFEADCA8);
        p.rect(3, h - 4, w - 6, 1, 0xFF7E786C);
        for (float x = 5; x < w - 6; x += 5) {
            p.rect(x + p.roll((int) x, 2), h - 4, 2, 1, 0xFFA8A294);
            p.rect(x + 2, h - 8 + p.roll((int) x + 7, 2), 1, 1, 0xFF4A4A50);
        }
        // Pewter corners.
        for (float[] c : new float[][]{{0, 0}, {w - 6, 0}, {0, h - 6}, {w - 6, h - 6}}) {
            p.rect(c[0], c[1], 6, 6, pewter);
            p.rect(c[0] + 1, c[1] + 1, 4, 1, pewterLit);
            p.rect(c[0] + 2, c[1] + 3, 2, 2, pewterDark);
        }
        // The linen filter in its oak frame, draining through a glass funnel into the well.
        float cx = Math.round(w / 2);
        p.rect(cx - 18, -16, 2, 16, OAK_DARK);
        p.rect(cx + 16, -16, 2, 16, OAK_DARK);
        p.rect(cx - 20, -18, 40, 3, OAK);
        p.rect(cx - 20, -18, 40, 1, 0xFF8A5A33);
        p.rect(cx - 15, -15, 30, 2, 0xFFE8E0C8);
        p.rect(cx - 12, -13, 24, 1, 0xFFD8CFB4);
        p.rect(cx - 15, -15, 1, 3, 0xFFC8BEA0);
        p.rect(cx + 14, -15, 1, 3, 0xFFC8BEA0);
        for (int i = 0; i < 6; i++) {
            float half = 11 - 2 * i;
            p.rect(cx - half, -12 + i, 2 * half, 1, GLASS);
            p.rect(cx - half, -12 + i, 1, 1, GLASS_EDGE);
            p.rect(cx + half - 1, -12 + i, 1, 1, GLASS_EDGE);
        }
        p.rect(cx - 1, -6, 2, 5, GLASS_EDGE);
        p.rect(cx - 1, 0, 2, 2, 0xC08FD0EC);
        // The tap at the side, a clean drop falling from it.
        p.rect(-8, h - 24, 8, 4, pewter);
        p.rect(-8, h - 24, 8, 1, pewterLit);
        p.rect(-8, h - 20, 3, 3, pewterDark);
        p.rect(-4, h - 28, 2, 4, pewterDark);
        p.rect(-6, h - 29, 6, 1, pewter);
        p.rect(-7, h - 15, 2, 3, 0xC08FD0EC);
        p.rect(-7, h - 10, 2, 2, 0x808FD0EC);
        nameplate(p, w, title);
        return p;
    }

    /** 🝟 The settling tray: a glass tray in an oak frame with brass corners, sediment on its floor and crystals growing in the corners. */
    private static Manuscript.Plan tray(float w, float h, float title) {
        Manuscript.Plan p = new Manuscript.Plan(81);
        p.rect(0, 0, w, h, OAK_DARK);
        p.rect(1, 1, w - 2, 1, 0xFF8A5A33);
        p.rect(3, 3, w - 6, h - 6, 0xFF33424A);
        p.rect(3, h - 7, w - 6, 4, 0xFF9A8A6A);
        p.rect(3, h - 7, w - 6, 1, 0xFFB8A880);
        for (float[] c : new float[][]{{0, 0}, {w - 6, 0}, {0, h - 6}, {w - 6, h - 6}}) {
            p.rect(c[0], c[1], 6, 6, BRASS);
            p.rect(c[0] + 1, c[1] + 1, 4, 1, BRASS_LIT);
            p.rect(c[0] + 2, c[1] + 3, 2, 2, BRASS_DARK);
        }
        crystals(p, 6, h - 7);
        crystals(p, w - 15, h - 7);
        nameplate(p, w, title);
        return p;
    }

    private static void crystals(Manuscript.Plan p, float x, float floor) {
        int light = 0xFFCFE8F2, mid = 0xFF8FBFD6, dark = 0xFF4F7F96;
        float[][] spires = {{0, 5, 2}, {3, 9, 3}, {7, 6, 2}};
        for (float[] s : spires) {
            p.rect(x + s[0], floor - s[1], s[2], s[1], mid);
            p.rect(x + s[0], floor - s[1], 1, s[1], light);
            p.rect(x + s[0] + s[2] - 1, floor - s[1] + 1, 1, s[1] - 1, dark);
            p.rect(x + s[0] + (s[2] - 1) / 2f, floor - s[1] - 1, 1, 1, light);
        }
    }

    /** Something drawn on the screen's own terms, from code that must also load on a server. */
    public interface Drawing {
        void draw(IGUIContext ctx);
    }

    /**
     * The product crate, with {@code feeds} drawn just before it: the stretch of each pipe from the
     * board's edge to the crate, which the board's own drawing cannot reach (it is clipped to the board).
     */
    public static IGuiTexture crate(Drawing feeds) {
        GuiTexture crate = crate();
        return (GuiTexture) (context, x, y, width, height) -> {
            feeds.draw(context);
            crate.draw(context, x, y, width, height);
        };
    }

    /** Draws {@code drawing} only within the given rectangle of the screen. */
    public static void clipped(IGUIContext ctx, float x, float y, float w, float h, Drawing drawing) {
        if (!(ctx instanceof GUIContext context) || w <= 0 || h <= 0) return;
        context.enableScissor(x, y, w, h);
        try {
            drawing.draw(ctx);
        } finally {
            context.disableScissor();
        }
    }

    /** The product crate: oak boards in a brass-cornered frame, a funnel on its side where products are piped in. */
    public static GuiTexture crate() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(91);
            p.rect(0, 0, w, h, OUTLINE);
            for (float y = 1; y < h - 1; y += 8) {
                p.rect(1, y, w - 2, Math.min(7, h - 1 - y), OAK);
                p.rect(1, y + 7, w - 2, 1, OAK_DARK);
            }
            p.rect(3, 3, w - 6, 1, 0xFF8A5A33);
            for (float[] c : new float[][]{{0, 0}, {w - 6, 0}, {0, h - 6}, {w - 6, h - 6}}) {
                p.rect(c[0], c[1], 6, 6, BRASS);
                p.rect(c[0] + 1, c[1] + 1, 4, 1, BRASS_LIT);
            }
            float cy = Math.round(h / 2);
            p.rect(-5, cy - 6, 5, 12, BRASS);
            p.rect(-5, cy - 6, 5, 1, BRASS_LIT);
            p.rect(-5, cy + 5, 5, 1, BRASS_DARK);
            p.rect(-2, cy - 3, 2, 6, OUTLINE);
            return p;
        });
    }

    /** The little brass valve a product's pipe is drawn from. */
    public static IGuiTexture valve() {
        return cached((w, h) -> {
            Manuscript.Plan p = new Manuscript.Plan(3);
            p.disc(Math.round(w / 2), Math.round(h / 2), (int) Math.max(2, Math.min(w, h) / 2), BRASS);
            p.ring(Math.round(w / 2), Math.round(h / 2), (int) Math.max(2, Math.min(w, h) / 2), BRASS_DARK);
            p.rect(Math.round(w / 2) - 1, Math.round(h / 2) - 1, 1, 1, BRASS_LIT);
            return p;
        });
    }

    // ---- pipes -----------------------------------------------------------------------------------

    /** What a pipe is made of: brass into the product crate, glass between apparatus, an empty glass tube while being laid. */
    public enum Pipe { BRASS, GLASS, LAYING }

    private record Laid(Manuscript.Plan edge, Manuscript.Plan body, Manuscript.Plan shine, List<Vector2f> route) {}

    /** Pipes are laid once for their ends; a board holds only a handful, so a small cache suffices. */
    private static final Map<String, Laid> LAID = new HashMap<>();

    /**
     * A pipe leaving (sx, sy) to the right and entering (tx, ty) from the left, with flanged ends;
     * what it carries runs along it as beads of {@code carried} (none while {@code now} is below 0).
     */
    public static void pipe(IGUIContext ctx, float sx, float sy, float tx, float ty, Pipe kind, int carried, long now) {
        if (!(ctx instanceof GUIContext context)) return;
        String key = Math.round(sx) + "," + Math.round(sy) + "," + Math.round(tx) + "," + Math.round(ty) + "," + kind;
        Laid laid = LAID.get(key);
        if (laid == null) {
            if (LAID.size() > 64) LAID.clear();
            laid = lay(sx, sy, tx, ty, kind);
            LAID.put(key, laid);
        }
        int edge = kind == Pipe.BRASS ? BRASS_DARK : 0xB0A8D0E0;
        int body = kind == Pipe.BRASS ? BRASS : kind == Pipe.GLASS ? (carried & 0x00FFFFFF) | 0x90000000 : 0x40D8F0F8;
        int shine = kind == Pipe.BRASS ? BRASS_LIT : 0xB0FFFFFF;
        laid.edge.draw(context, 0, 0, edge);
        laid.body.draw(context, 0, 0, body);
        laid.shine.draw(context, 0, 0, shine);
        if (kind != Pipe.LAYING) {
            flange(context, sx, sy, 1);
            flange(context, tx, ty, -1);
        }
        if (now < 0) return;
        int bead = kind == Pipe.BRASS ? (carried & 0x00FFFFFF) | 0xFF000000 : 0xE0FFFFFF;
        for (float offset : new float[]{0, 0.33f, 0.66f}) {
            float t = ((now % 1400) / 1400f + offset) % 1f;
            Vector2f at = laid.route.get(Math.min(laid.route.size() - 1, (int) (t * (laid.route.size() - 1))));
            DrawerHelperClient.drawSolidRect(context, Math.round(at.x) - 1, Math.round(at.y) - 1, 2, 2, bead);
        }
    }

    private static Laid lay(float sx, float sy, float tx, float ty, Pipe kind) {
        float bend = Math.max(30, Math.abs(tx - sx) / 2);
        int steps = (int) Math.max(12, (Math.abs(tx - sx) + Math.abs(ty - sy)) / 1.5f);
        List<Vector2f> route = new ArrayList<>(), lit = new ArrayList<>();
        for (int k = 0; k <= steps; k++) {
            float t = k / (float) steps, u = 1 - t, a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
            route.add(new Vector2f(a * sx + b * (sx + bend) + c * (tx - bend) + d * tx, a * sy + b * sy + c * ty + d * ty));
            lit.add(new Vector2f(route.getLast()).sub(0, 1));
        }
        int bore = kind == Pipe.LAYING ? 3 : 6;
        Manuscript.Plan edge = new Manuscript.Plan(0), body = new Manuscript.Plan(0), shine = new Manuscript.Plan(0);
        new Manuscript.Pen(edge, bore, 0, 0).trace(route, 0);
        new Manuscript.Pen(body, bore - 2, 0, 0).trace(route, 0);
        new Manuscript.Pen(shine, 1, 0, 0).trace(lit, 0);
        return new Laid(edge, body, shine, route);
    }

    /** A brass flange where a pipe meets an apparatus, its face towards {@code facing} (1 right, -1 left). */
    private static void flange(GUIContext context, float x, float y, int facing) {
        float fx = Math.round(x) - (facing > 0 ? 0 : 3), fy = Math.round(y) - 4;
        DrawerHelperClient.drawSolidRect(context, fx, fy, 3, 8, BRASS_DARK);
        DrawerHelperClient.drawSolidRect(context, fx + (facing > 0 ? 0 : 1), fy + 1, 2, 6, BRASS);
        DrawerHelperClient.drawSolidRect(context, fx + 1, fy + 1, 1, 1, BRASS_LIT);
    }

    // ---- controls shared with the spell workbench ------------------------------------------------

    public static void paper(Button button) {
        ManuscriptControls.paper(button);
    }

    public static void paper(Selector<?> selector) {
        ManuscriptControls.paper(selector);
    }

    /** Marks a part of the screen whose text is written in ink on paper. */
    public static void inked(UIElement element) {
        element.addClass(ManuscriptControls.INKED);
    }

    public static UIElement ribbon(String key, BooleanSupplier open, Runnable onClick) {
        return ManuscriptControls.ribbon(key, open, onClick);
    }

    /** A selector builds its entries when its list opens; they are inked as they appear. */
    public static void dressList(Selector<?> selector) {
        ManuscriptControls.dressList(selector);
    }
}
