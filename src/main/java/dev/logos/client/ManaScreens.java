package dev.logos.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.BindableValue;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;

import dev.logos.runtime.ManaField;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * The drawn parts of the mana spreader's and collector's screens, in the bench's pixel style: the map
 * of the chunks around a spreader, and the collector's intake gauge.
 */
public final class ManaScreens {
    private static final int OUTLINE = 0xFF1E1209, BRASS = 0xFFB8862E, BRASS_LIT = 0xFFE8C46A, BRASS_DARK = 0xFF6E4A16;
    private static final int PAPER = 0xFFF1E2BC, PAPER_DIM = 0xFFE2CFA4, MANA = 0x3FA8C8, BEAD = 0xFFDFFBFF, BEAD_EDGE = 0xFF3F93B0;

    private ManaScreens() {}

    /** The survey a spreader's screen was last sent ({@link ManaField#survey}), or nothing yet. */
    private static int[] values(BindableValue<Tag> survey) {
        return survey.getValue() instanceof IntArrayTag t ? t.getAsIntArray() : new int[0];
    }

    /**
     * 5×5 chunks around a spreader, its own in the middle: each tinted by how much mana its air holds,
     * hatched if not loaded; beads run across the borders mana is spreading over, and rise in the
     * middle while the spreader pours. Hovering a chunk tells its mana.
     */
    public static final class ChunkMap extends UIElement {
        public static final int R = 2, SIDE = 2 * R + 1, CELL = 22, GAP = 2, SIZE = SIDE * CELL + (SIDE - 1) * GAP;
        private final BindableValue<Tag> survey;
        private final BindableValue<Float> poured;
        private final int chunkX, chunkZ;
        private int[] shown = new int[0];

        public ChunkMap(BindableValue<Tag> survey, BindableValue<Float> poured, int chunkX, int chunkZ) {
            this.survey = survey;
            this.poured = poured;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            setId("chunk_map");
            layout(l -> l.width(SIZE + 6).height(SIZE + 6));
            style(s -> s.background((GuiTexture) (ctx, x, y, w, h) -> frame(ctx, x, y, w, h)));
            for (int i = 0; i < SIDE * SIDE; i++) {
                int cell = i;
                UIElement e = new UIElement();
                e.setId("chunk_" + i);
                e.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(3 + (cell % SIDE) * (CELL + GAP)).top(3 + (cell / SIDE) * (CELL + GAP))
                        .width(CELL).height(CELL));
                e.style(s -> s.background((GuiTexture) (ctx, x, y, w, h) -> cell(ctx, x, y, cell)));
                addChild(e);
            }
            UIElement beads = new UIElement();
            beads.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(3).top(3).width(SIZE).height(SIZE));
            beads.setAllowHitTest(false);
            beads.style(s -> s.background((GuiTexture) (ctx, x, y, w, h) -> beads(ctx, x, y)));
            addChild(beads);
            addEventListener(UIEvents.TICK, e -> tooltips());
        }

        private void frame(Object ctx, float x, float y, float w, float h) {
            if (!(ctx instanceof GUIContext c)) return;
            DrawerHelperClient.drawSolidRect(c, x, y, w, h, BRASS_DARK);
            DrawerHelperClient.drawSolidRect(c, x + 1, y + 1, w - 2, h - 2, BRASS);
            DrawerHelperClient.drawSolidRect(c, x + 1, y + 1, w - 2, 1, BRASS_LIT);
            DrawerHelperClient.drawSolidRect(c, x + 2, y + 2, w - 4, h - 4, OUTLINE);
        }

        private void cell(Object ctx, float x, float y, int i) {
            if (!(ctx instanceof GUIContext c)) return;
            int[] v = values(survey);
            int tenths = i < v.length ? v[i] : -1;
            boolean centre = i == SIDE * SIDE / 2;
            if (tenths < 0) {
                DrawerHelperClient.drawSolidRect(c, x, y, CELL, CELL, 0xFFA89C82);
                for (int d = 0; d < CELL; d += 3) DrawerHelperClient.drawSolidRect(c, x + d, y + (d * 7) % CELL, 1, 1, 0xFF7C705A);
                return;
            }
            float frac = (float) Math.min(1, tenths / 10.0 / ManaField.CAP);
            DrawerHelperClient.drawSolidRect(c, x, y, CELL, CELL, PAPER_DIM);
            if (frac > 0) DrawerHelperClient.drawSolidRect(c, x, y, CELL, CELL, ((int) (30 + 200 * frac) << 24) | MANA);
            // A level mark along the bottom, so close values can be told apart.
            DrawerHelperClient.drawSolidRect(c, x + 2, y + CELL - 3, Math.round((CELL - 4) * frac), 1, 0xFF2E6E86);
            if (centre) {
                DrawerHelperClient.drawSolidRect(c, x, y, CELL, 1, BRASS_LIT);
                DrawerHelperClient.drawSolidRect(c, x, y + CELL - 1, CELL, 1, BRASS_DARK);
                DrawerHelperClient.drawSolidRect(c, x, y, 1, CELL, BRASS_LIT);
                DrawerHelperClient.drawSolidRect(c, x + CELL - 1, y, 1, CELL, BRASS_DARK);
                // The spreader: a small brass urn.
                float cx = x + CELL / 2f, cy = y + CELL / 2f;
                DrawerHelperClient.drawSolidRect(c, cx - 3, cy - 1, 6, 5, BRASS_DARK);
                DrawerHelperClient.drawSolidRect(c, cx - 2, cy - 1, 4, 4, BRASS);
                DrawerHelperClient.drawSolidRect(c, cx - 2, cy - 3, 4, 1, BRASS_DARK);
                DrawerHelperClient.drawSolidRect(c, cx - 1, cy - 2, 2, 1, BRASS_LIT);
            }
        }

        /** Beads along the borders mana flows over, and, while the spreader pours, rising from it. */
        private void beads(Object ctx, float x, float y) {
            if (!(ctx instanceof GUIContext c)) return;
            int[] v = values(survey);
            int n = SIDE * SIDE;
            long now = Util.getMillis();
            if (v.length == 2 * n) {
                for (int i = 0; i < n; i++) {
                    for (int d = 0; d < 4; d++) {
                        if ((v[n + i] & (1 << d)) == 0) continue;
                        int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
                        float sx = x + (i % SIDE) * (CELL + GAP) + CELL / 2f, sy = y + (i / SIDE) * (CELL + GAP) + CELL / 2f;
                        for (float k : new float[] {0, 0.5f}) {
                            float t = ((now + i * 137L) % 1600) / 1600f + k;
                            t -= (float) Math.floor(t);
                            // Out of the map's edge they fade into its frame.
                            boolean out = (i % SIDE + dx < 0) || (i % SIDE + dx >= SIDE) || (i / SIDE + dz < 0) || (i / SIDE + dz >= SIDE);
                            if (out && t > 0.5f) continue;
                            bead(c, sx + dx * (CELL + GAP) * t, sy + dz * (CELL + GAP) * t);
                        }
                    }
                }
            }
            if (poured.getValue() != null && poured.getValue() > 0) {
                float cx = x + R * (CELL + GAP) + CELL / 2f, cy = y + R * (CELL + GAP) + CELL / 2f;
                for (int k = 0; k < 3; k++) {
                    float t = ((now + k * 400L) % 1200) / 1200f;
                    bead(c, cx - 5 + k * 5, cy - 3 - t * 7);
                }
            }
        }

        private static void bead(GUIContext c, float x, float y) {
            float bx = Math.round(x) - 1, by = Math.round(y) - 1;
            DrawerHelperClient.drawSolidRect(c, bx - 1, by, 4, 2, BEAD_EDGE);
            DrawerHelperClient.drawSolidRect(c, bx, by - 1, 2, 4, BEAD_EDGE);
            DrawerHelperClient.drawSolidRect(c, bx, by, 2, 2, BEAD);
        }

        /** Each chunk's tooltip, rewritten when the survey changes. */
        private void tooltips() {
            int[] v = values(survey);
            if (Arrays.equals(v, shown)) return;
            shown = v;
            int n = SIDE * SIDE;
            for (int i = 0; i < n; i++) {
                List<Component> lines = new ArrayList<>();
                int x = chunkX - R + i % SIDE, z = chunkZ - R + i / SIDE;
                lines.add(Component.translatable("logos.mana_spreader.chunk", x, z).withStyle(ChatFormatting.GOLD));
                if (i == n / 2) lines.add(Component.translatable("logos.mana_spreader.here").withStyle(ChatFormatting.GRAY));
                if (i >= v.length || v[i] < 0) {
                    lines.add(Component.translatable("logos.mana_spreader.unloaded").withStyle(ChatFormatting.GRAY));
                } else {
                    lines.add(Component.translatable("logos.mana_spreader.chunk_mana", String.format("%.1f", v[i] / 10.0), (int) ManaField.CAP)
                            .withStyle(ChatFormatting.AQUA));
                    int out = v.length == 2 * n ? v[n + i] : 0;
                    if (out != 0) {
                        String[] names = {"east", "west", "south", "north"};
                        var ways = Component.empty();
                        boolean first = true;
                        for (int d = 0; d < 4; d++) {
                            if ((out & (1 << d)) == 0) continue;
                            if (!first) ways.append(Component.translatable("logos.mana_spreader.and"));
                            ways.append(Component.translatable("logos.mana_spreader." + names[d]));
                            first = false;
                        }
                        lines.add(Component.translatable("logos.mana_spreader.flows", ways).withStyle(ChatFormatting.DARK_AQUA));
                    }
                }
                getChildren().get(i).style(s -> s.tooltips(lines.toArray(Component[]::new)));
            }
        }
    }

    /**
     * A brass pressure gauge for a collector's intake: the needle shows mana drawn a second, on a
     * square-root scale so the faint ambient intake still moves it; the scale is banded green for the
     * ambient part and blue for the spread part. Like a needle on a gas line, it never quite rests.
     */
    public static final class IntakeGauge extends UIElement {
        public static final int W = 120, H = 70;
        /** The top of the scale: the most a collector draws a second. */
        private static final double TOP = ManaField.DRAW_MAX + ManaField.COLLECTOR_AMBIENT;
        private final BindableValue<Float> ambient, spread;
        private float needle;
        private long last = Util.getMillis();

        public IntakeGauge(BindableValue<Float> ambient, BindableValue<Float> spread) {
            this.ambient = ambient;
            this.spread = spread;
            setId("intake_gauge");
            layout(l -> l.width(W).height(H));
            style(s -> s.background((GuiTexture) (ctx, x, y, w, h) -> draw(ctx, x, y)));
        }

        private static float frac(double rate) {
            return (float) Math.sqrt(Math.clamp(rate / TOP, 0, 1));
        }

        /** The angle along the dial for a fraction of the scale: π at the left stop, 0 at the right. */
        private static double angle(float frac) {
            return Math.PI * (1 - frac);
        }

        private void draw(Object ctx, float x, float y) {
            if (!(ctx instanceof GUIContext c)) return;
            float a = ambient.getValue() == null ? 0 : ambient.getValue(), s = spread.getValue() == null ? 0 : spread.getValue();
            float cx = Math.round(x + W / 2f), cy = Math.round(y + H - 10);
            int r = 52;
            // Brass rim, paper face, a base plate.
            disc(c, cx, cy, r + 4, BRASS_DARK);
            disc(c, cx, cy, r + 3, BRASS);
            disc(c, cx, cy, r + 1, OUTLINE);
            disc(c, cx, cy, r, PAPER);
            DrawerHelperClient.drawSolidRect(c, cx - r - 6, cy, 2 * r + 12, 8, BRASS_DARK);
            DrawerHelperClient.drawSolidRect(c, cx - r - 5, cy + 1, 2 * r + 10, 6, BRASS);
            DrawerHelperClient.drawSolidRect(c, cx - r - 5, cy + 1, 2 * r + 10, 1, BRASS_LIT);
            // Bands: ambient, then spread, along the inside of the rim.
            band(c, cx, cy, r - 3, 0, frac(a), 0xFF6FA85A);
            band(c, cx, cy, r - 3, frac(a), frac(a + s), 0xFF4FA8C8);
            // Ticks at 0, 0.1, 0.5, 1, 2, 5 and 10 a second.
            for (double t : new double[] {0, 0.1, 0.5, 1, 2, 5, 10}) {
                double ang = angle(frac(t));
                for (int k = r - 9; k <= r - 6; k++) dot(c, cx + Math.cos(ang) * k, cy - Math.sin(ang) * k, Manuscript.INK);
            }
            // The needle eases toward the intake and trembles about it, more the more flows.
            long now = Util.getMillis();
            float target = frac(a + s);
            needle += (target - needle) * Math.min(1, (now - last) / 250f);
            last = now;
            double secs = now / 1000.0;
            double shake = target <= 0 ? 0.004 : 0.012 + 0.035 * target;
            double wobble = shake * (0.6 * Math.sin(secs * 13.1) + 0.3 * Math.sin(secs * 29.7 + 1.3) + 0.25 * Math.sin(secs * 47.3 + 2.1));
            double ang = angle(Math.clamp(needle + (float) wobble, 0, 1));
            for (int k = 0; k <= r - 8; k++) {
                double px = cx + Math.cos(ang) * k, py = cy - Math.sin(ang) * k;
                dot(c, px, py, 0xFF8A2A1A);
                if (k < r - 16) dot(c, px + 1, py, 0xFF8A2A1A);
            }
            DrawerHelperClient.drawSolidRect(c, cx - 3, cy - 3, 6, 6, BRASS_DARK);
            DrawerHelperClient.drawSolidRect(c, cx - 2, cy - 2, 4, 4, BRASS);
            DrawerHelperClient.drawSolidRect(c, cx - 2, cy - 2, 2, 1, BRASS_LIT);
        }

        private static void dot(GUIContext c, double x, double y, int colour) {
            DrawerHelperClient.drawSolidRect(c, (float) Math.floor(x), (float) Math.floor(y), 1, 1, colour);
        }

        /** The upper half of a disc, a row of pixels at a time. */
        private static void disc(GUIContext c, float cx, float cy, int r, int colour) {
            for (int dy = 0; dy <= r; dy++) {
                int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
                DrawerHelperClient.drawSolidRect(c, cx - half, cy - dy, 2 * half + 1, 1, colour);
            }
        }

        /** An arc three pixels thick just inside radius {@code r}, from {@code from} to {@code to} of the scale. */
        private static void band(GUIContext c, float cx, float cy, int r, float from, float to, int colour) {
            if (to <= from) return;
            int steps = (int) Math.max(2, (to - from) * Math.PI * r * 1.5);
            for (int k = 0; k <= steps; k++) {
                double ang = angle(from + (to - from) * k / steps);
                for (int d = 0; d < 3; d++) dot(c, cx + Math.cos(ang) * (r - d), cy - Math.sin(ang) * (r - d), colour);
            }
        }
    }
}
