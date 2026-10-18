package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

/**
 * The workbench's manuscript hand: iron-gall ink, red chalk and watercolour washes laid down in
 * whole GUI pixels so they sit with the pixel parchment. Strokes are planned once for a size and
 * replayed every frame, and their wobble comes from a seed, so a drawing never shimmers as it pans.
 */
public final class Manuscript {
    /** Iron-gall ink, fresh and faded. */
    static final int INK = 0xFF3B2614;
    static final int INK_FADED = 0xFF7A5A3A;
    /** Leonardo's red chalk, used for marks the reader should notice, such as a selection. */
    static final int SANGUINE = 0xFFA4432A;

    private Manuscript() {}

    /** The frame being drawn, and marks laid in it and in the last finished one: the workbench's draw budget. */
    private static @org.jspecify.annotations.Nullable GUIContext frame;
    private static int counting, lastFrame;

    private static void count(GUIContext context, int marks) {
        if (context != frame) {
            frame = context;
            lastFrame = counting;
            counting = 0;
        }
        counting += marks;
    }

    /** Solid marks the manuscript drawing submitted in the last finished frame. */
    public static int marksLastFrame() {
        return lastFrame;
    }

    /** One solid mark relative to the plan's origin. */
    private record Mark(float x, float y, float w, float h, int color) {}

    /** A drawing kept as solid marks so it can be replayed cheaply at any canvas offset. */
    static final class Plan {
        private final List<Mark> marks = new ArrayList<>();
        private int seed;

        Plan(int seed) {
            this.seed = seed;
        }

        /** A repeatable pseudo-random number in [0, bound) for the n-th decision of this plan. */
        int roll(int n, int bound) {
            int h = seed * 0x9E3779B1 + n * 0x85EBCA6B;
            h ^= h >>> 15;
            h *= 0x2C1B3C6D;
            h ^= h >>> 12;
            return Math.floorMod(h, bound);
        }

        void rect(float x, float y, float w, float h, int color) {
            if (w > 0 && h > 0) marks.add(new Mark(x, y, w, h, color));
        }

        /**
         * A quill stroke from x0 to x1: short segments that now and then lift a pixel off the line
         * (towards {@code out}, so the wobble never eats into the text) and darken where the pen lands.
         */
        void hStroke(float x0, float x1, float y, int out, int color, int salt) {
            float x = x0;
            int n = 0;
            while (x < x1) {
                float len = Math.min(5 + roll(salt + n * 3, 9), x1 - x);
                float dy = roll(salt + n * 3 + 1, 5) == 0 ? out : 0;
                rect(x, y + dy, len, 1, color);
                n++;
                x += len;
            }
            rect(x0, y, 2, 1, color);
            rect(x0, y + out, 1, 1, color);
        }

        void vStroke(float x, float y0, float y1, int out, int color, int salt) {
            float y = y0;
            int n = 0;
            while (y < y1) {
                float len = Math.min(5 + roll(salt + n * 3, 9), y1 - y);
                float dx = roll(salt + n * 3 + 1, 5) == 0 ? out : 0;
                rect(x + dx, y, 1, len, color);
                n++;
                y += len;
            }
            rect(x, y0, 1, 2, color);
            rect(x + out, y0, 1, 1, color);
        }

        /** A diagonal hatch line running down to the right, the way a left hand shades. */
        void hatch(float x, float y, int length, int color) {
            for (int i = 0; i < length; i++) rect(x + i, y + i, 1, 1, color);
        }

        /** A pixel ring, as drawn with dividers. */
        void ring(float cx, float cy, int r, int color) {
            arc(cx, cy, r, color, TOP_RIGHT | TOP_LEFT | BOTTOM_LEFT | BOTTOM_RIGHT);
        }

        static final int TOP_RIGHT = 1, TOP_LEFT = 2, BOTTOM_LEFT = 4, BOTTOM_RIGHT = 8;

        /** The quarters of a pixel ring named by {@code quarters}. */
        void arc(float cx, float cy, int r, int color, int quarters) {
            int x = r, y = 0, err = 1 - r;
            while (x >= y) {
                if ((quarters & BOTTOM_RIGHT) != 0) {
                    rect(cx + x, cy + y, 1, 1, color);
                    rect(cx + y, cy + x, 1, 1, color);
                }
                if ((quarters & BOTTOM_LEFT) != 0) {
                    rect(cx - y - 1, cy + x, 1, 1, color);
                    rect(cx - x - 1, cy + y, 1, 1, color);
                }
                if ((quarters & TOP_LEFT) != 0) {
                    rect(cx - x - 1, cy - y - 1, 1, 1, color);
                    rect(cx - y - 1, cy - x - 1, 1, 1, color);
                }
                if ((quarters & TOP_RIGHT) != 0) {
                    rect(cx + y, cy - x - 1, 1, 1, color);
                    rect(cx + x, cy - y - 1, 1, 1, color);
                }
                y++;
                if (err < 0) {
                    err += 2 * y + 1;
                } else {
                    x--;
                    err += 2 * (y - x) + 1;
                }
            }
        }

        /** A filled triangle, pixel by pixel, one mark per row. */
        void triangle(float ax, float ay, float bx, float by, float cx, float cy, int color) {
            int y0 = (int) Math.floor(Math.min(ay, Math.min(by, cy))), y1 = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
            int x0 = (int) Math.floor(Math.min(ax, Math.min(bx, cx))), x1 = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
            for (int y = y0; y <= y1; y++) {
                int from = Integer.MAX_VALUE, to = Integer.MIN_VALUE;
                for (int x = x0; x <= x1; x++) {
                    float px = x + 0.5f, py = y + 0.5f;
                    float d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
                    float d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
                    float d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
                    boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
                    if (neg && pos) continue;
                    from = Math.min(from, x);
                    to = Math.max(to, x);
                }
                if (from <= to) rect(from, y, to - from + 1, 1, color);
            }
        }

        /** A filled pixel disc. */
        void disc(float cx, float cy, int r, int color) {
            for (int dy = -r; dy < r; dy++) {
                float half = (float) Math.floor(Math.sqrt(r * r - (dy + 0.5) * (dy + 0.5)) + 0.5);
                rect(cx - half, cy + dy, half * 2, 1, color);
            }
        }

        /** The upper half of a filled disc, standing on the row {@code baseY}. */
        void dome(float cx, float baseY, int r, int color) {
            for (int dy = -r; dy < 0; dy++) {
                float half = (float) Math.floor(Math.sqrt(r * r - (dy + 0.5) * (dy + 0.5)) + 0.5);
                rect(cx - half, baseY + dy, half * 2, 1, color);
            }
        }

        /**
         * A small pixel picture: {@code #} in ink, {@code +} in the accent, anything else left bare.
         * Rows are read top to bottom, so the picture can be written out as it looks.
         */
        void picture(float x, float y, String[] rows, int ink, int accent) {
            for (int row = 0; row < rows.length; row++) {
                String line = rows[row];
                for (int col = 0; col < line.length(); col++) {
                    char c = line.charAt(col);
                    if (c == '#') rect(x + col, y + row, 1, 1, ink);
                    else if (c == '+') rect(x + col, y + row, 1, 1, accent);
                }
            }
        }

        void draw(GUIContext context, float x, float y) {
            count(context, marks.size());
            for (Mark m : marks) DrawerHelperClient.drawSolidRect(context, x + m.x, y + m.y, m.w, m.h, m.color);
        }

        /** Replays the drawing in one colour, for strokes whose ink depends on state (hover, selection). */
        void draw(GUIContext context, float x, float y, int color) {
            count(context, marks.size());
            for (Mark m : marks) DrawerHelperClient.drawSolidRect(context, x + m.x, y + m.y, m.w, m.h, color);
        }
    }

    /**
     * Lays pixels along a route with a square nib, joining neighbours on a row into one mark, and
     * lifting the pen in a dash pattern if one is given ({@code on} = 0 for a solid line).
     */
    static final class Pen {
        private final Plan plan;
        private final int nib, on, off;
        private int grid = 1;
        private float travelled;
        private int runY = Integer.MIN_VALUE, runX0, runX1, lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;

        Pen(Plan plan, int nib, int on, int off) {
            this.plan = plan;
            this.nib = nib;
            this.on = on;
            this.off = off;
        }

        /** Snaps the nib to a coarser grid: fewer, larger marks, for routes seen from far away. */
        Pen grid(int cells) {
            grid = cells;
            return this;
        }

        /** Follows {@code points} from the start, stopping {@code trimEnd} short of the last point. */
        void trace(java.util.List<org.joml.Vector2f> points, float trimEnd) {
            float total = 0;
            for (int i = 1; i < points.size(); i++) total += points.get(i - 1).distance(points.get(i));
            float limit = total - trimEnd, walked = 0;
            for (int i = 1; i < points.size() && walked < limit; i++) {
                org.joml.Vector2f a = points.get(i - 1), b = points.get(i);
                float len = a.distance(b);
                for (float t = 0; t < len && walked + t < limit; t += 0.5f) {
                    dot(a.x + (b.x - a.x) * t / len, a.y + (b.y - a.y) * t / len, 0.5f);
                }
                walked += len;
            }
            flush();
        }

        void segment(float x0, float y0, float x1, float y1) {
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            for (float t = 0; t <= len; t += 0.5f) dot(x0 + (x1 - x0) * t / Math.max(len, 1e-3f), y0 + (y1 - y0) * t / Math.max(len, 1e-3f), 0.5f);
            flush();
        }

        private void dot(float fx, float fy, float step) {
            travelled += step;
            if (on > 0 && travelled % (on + off) >= on) return;
            int x = Math.floorDiv((int) Math.floor(fx - nib / 2f + 0.5f), grid) * grid;
            int y = Math.floorDiv((int) Math.floor(fy - nib / 2f + 0.5f), grid) * grid;
            if (x == lastX && y == lastY) return;
            lastX = x;
            lastY = y;
            if (y == runY && x >= runX0 - grid && x <= runX1 + grid) {
                runX0 = Math.min(runX0, x);
                runX1 = Math.max(runX1, x);
                return;
            }
            flush();
            runY = y;
            runX0 = runX1 = x;
        }

        private void flush() {
            if (runY != Integer.MIN_VALUE) plan.rect(runX0, runY, runX1 - runX0 + nib, nib, 0xFF000000);
            runY = Integer.MIN_VALUE;
        }
    }
}
