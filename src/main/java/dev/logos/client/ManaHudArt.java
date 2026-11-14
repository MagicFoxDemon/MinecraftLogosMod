package dev.logos.client;

/**
 * The mana HUD's pieces, drawn in whole GUI pixels with no texture, in the brass and iron of the copy and
 * alchemy tables: a mana crystal, a brass-framed glass bar of ten cells, and the thin iron gauge of what a
 * staff holds. Each is planned once for a size and replayed every frame (see {@link Manuscript.Plan}).
 */
final class ManaHudArt {
    static final int BRASS = 0xFFB8862E, BRASS_LIT = 0xFFE8C46A, BRASS_DARK = 0xFF6E4A16;
    static final int IRON_DEEP = 0xFF16141A, IRON = 0xFF36323C, IRON_LIT = 0xFF57515F;
    /** The glass inside the frame where no mana is. */
    static final int GLASS = 0xFF0E1322, GLASS_SHADOW = 0xFF070A13;
    /** Mana: the body, its lit top, its shaded bottom and the bright meniscus at its edge. */
    static final int MANA = 0xFF2C6BDB, MANA_LIT = 0xFF79BDFF, MANA_DEEP = 0xFF1A3C8E, MANA_EDGE = 0xFFD2F0FF;
    /** What a staff holds, in amber. */
    static final int AMBER = 0xFFD99A2B, AMBER_LIT = 0xFFFFD27A, AMBER_DEEP = 0xFF84520F;
    /** The mark of what the selected spell injects: there is enough, or not. */
    static final int MARK = 0xFFF2C25A, MARK_SHORT = 0xFFE0584A;
    /** The plate under it all, so it reads on snow and in caves alike. */
    static final int PLATE = 0x8C0B0912, PLATE_RIM = 0xB0201A16;

    /** The bar: its frame is {@link #BAR_W} by {@link #BAR_H}, holding {@link #CELLS} cells of {@link #CELL} pixels. */
    static final int CELLS = 10, CELL = 8, INNER = CELLS * CELL, BAR_W = INNER + 2, BAR_H = 7;
    /** The staff's gauge under it: the same width, thinner. */
    static final int GAUGE_H = 4;
    static final int GEM_W = 9, GEM_H = 11;

    private ManaHudArt() {}

    /** The dark plate behind the HUD, its corners cut a pixel so it does not look like a box. */
    static Manuscript.Plan plate(int w, int h) {
        Manuscript.Plan p = new Manuscript.Plan(71);
        p.rect(1, 0, w - 2, 1, PLATE_RIM);
        p.rect(1, h - 1, w - 2, 1, PLATE_RIM);
        p.rect(0, 1, 1, h - 2, PLATE_RIM);
        p.rect(w - 1, 1, 1, h - 2, PLATE_RIM);
        p.rect(1, 1, w - 2, h - 2, PLATE);
        return p;
    }

    private static final String[] GEM = {
            "    o    ",
            "   olo   ",
            "  olwbo  ",
            " olwbbdo ",
            "olwbbbddo",
            "olbbbbddo",
            "obbbbbddo",
            " obbbddo ",
            "  obddo  ",
            "   odo   ",
            "    o    ",
    };

    /** The mana crystal, cut with light from the upper left; dimmed when the pool cannot pay for the selected spell. */
    static Manuscript.Plan gem(boolean lit) {
        Manuscript.Plan p = new Manuscript.Plan(73);
        int outline = lit ? 0xFF15204A : 0xFF1A1A24;
        int body = lit ? MANA : 0xFF3A4766, light = lit ? MANA_LIT : 0xFF66718C, white = lit ? 0xFFEAF8FF : 0xFF9AA2B4;
        int dark = lit ? MANA_DEEP : 0xFF262C3E;
        for (int row = 0; row < GEM.length; row++) {
            String line = GEM[row];
            for (int col = 0; col < line.length(); col++) {
                int c = switch (line.charAt(col)) {
                    case 'o' -> outline;
                    case 'b' -> body;
                    case 'l' -> light;
                    case 'w' -> white;
                    case 'd' -> dark;
                    default -> 0;
                };
                if (c != 0) p.rect(col, row, 1, 1, c);
            }
        }
        return p;
    }

    /** The bar's brass frame round its dark glass: lit above and to the left, shaded below and to the right, corners cut. */
    static Manuscript.Plan frame(boolean brass, int h) {
        Manuscript.Plan p = new Manuscript.Plan(79);
        int lit = brass ? BRASS_LIT : IRON_LIT, mid = brass ? BRASS : IRON, dark = brass ? BRASS_DARK : IRON_DEEP;
        p.rect(1, 0, BAR_W - 2, 1, lit);
        p.rect(0, 1, 1, h - 2, lit);
        p.rect(1, h - 1, BAR_W - 2, 1, dark);
        p.rect(BAR_W - 1, 1, 1, h - 2, dark);
        // The brass corner studs, where frame meets frame.
        p.rect(0, 0, 1, 1, mid);
        p.rect(BAR_W - 1, h - 1, 1, 1, mid);
        p.rect(1, 1, INNER, h - 2, GLASS);
        p.rect(1, 1, INNER, 1, GLASS_SHADOW);
        if (brass) {
            // Ten cells: faint rulings in the empty glass, so the bar still reads as tenths when low.
            for (int i = 1; i < CELLS; i++) p.rect(1 + i * CELL, 2, 1, h - 4, 0xFF161D33);
        }
        return p;
    }

    /**
     * {@code px} pixels of mana (or a staff's amber) inside a frame {@code h} tall: lit top row, shaded bottom row,
     * a darker ruling at each cell, and the meniscus at its edge.
     */
    static Manuscript.Plan fill(int px, int h, boolean amber) {
        Manuscript.Plan p = new Manuscript.Plan(83);
        if (px <= 0) return p;
        int body = amber ? AMBER : MANA, lit = amber ? AMBER_LIT : MANA_LIT, deep = amber ? AMBER_DEEP : MANA_DEEP;
        int inner = h - 2;
        p.rect(0, 0, px, inner, body);
        p.rect(0, 0, px, 1, lit);
        if (inner > 2) p.rect(0, inner - 1, px, 1, deep);
        if (!amber) for (int i = 1; i < CELLS && i * CELL < px; i++) p.rect(i * CELL, 1, 1, inner - 2, deep);
        if (px < INNER) p.rect(px - 1, 0, 1, inner, amber ? AMBER_LIT : MANA_EDGE);
        return p;
    }

    /** The mark of what the selected spell injects, standing a pixel proud of the frame, with a small cap. */
    static Manuscript.Plan mark(int h, boolean enough) {
        Manuscript.Plan p = new Manuscript.Plan(89);
        int c = enough ? MARK : MARK_SHORT;
        p.rect(-1, -1, 3, 1, c);
        p.rect(0, 0, 1, h + 1, c);
        return p;
    }
}
