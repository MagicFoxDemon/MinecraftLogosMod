package dev.logos.client;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The surface of an item model (the "mesh" Unity emits particles from): the item's own faces cut into cells one
 * texel each, transparent texels dropped. Vanilla's generated items (swords, tools) are a whole quad front and back
 * hollowed out by the texture, hence the per-texel test; their sides are one-texel strips that trace the outline,
 * called the edge here. Models built from cuboids (wands) are solid on every face. Everything is in model space
 * (0–1 is one block). Also works out the main axis — the blade from hilt to tip, its length and thickness — that the
 * flame sheets and wound mana threads follow.
 */
final class ItemSurface {
    /** 13 floats per cell: centre xyz, two half edges xyz, normal xyz, cumulative area. */
    private static final int STRIDE = 13;
    /** At most 64×64 cells per face, so high-resolution textures do not run away. */
    private static final int MAX_CELLS = 64;

    private final float[] cells, edges;
    private final int count, edgeCount;
    /** The main axis: its centre, unit direction (towards the tip) and half length, and the mean distance from it. */
    final Vector3f center = new Vector3f(), axis = new Vector3f(0, 1, 0);
    final float half, radius;
    /** The two directions square to the axis: {@code across} in the item's plane, {@code out} out of it (generated items face ±Z). */
    final Vector3f across = new Vector3f(), out = new Vector3f();

    private ItemSurface(float[] cells, int count, float[] edges, int edgeCount) {
        this.cells = cells;
        this.count = count;
        this.edges = edges;
        this.edgeCount = edgeCount;
        // Area-weighted covariance; power iteration for its largest eigenvector.
        float total = cells[(count - 1) * STRIDE + 12];
        for (int i = 0; i < count; i++) center.fma(area(cells, i) / total, new Vector3f(cells[i * STRIDE], cells[i * STRIDE + 1], cells[i * STRIDE + 2]));
        float[] cov = new float[9];
        for (int i = 0; i < count; i++) {
            float w = area(cells, i) / total;
            float dx = cells[i * STRIDE] - center.x, dy = cells[i * STRIDE + 1] - center.y, dz = cells[i * STRIDE + 2] - center.z;
            float[] d = {dx, dy, dz};
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) cov[r * 3 + c] += w * d[r] * d[c];
        }
        Vector3f v = new Vector3f(.6f, .7f, .1f);
        for (int it = 0; it < 32; it++) {
            v.set(cov[0] * v.x + cov[1] * v.y + cov[2] * v.z, cov[3] * v.x + cov[4] * v.y + cov[5] * v.z, cov[6] * v.x + cov[7] * v.y + cov[8] * v.z);
            if (v.lengthSquared() < 1e-12f) { v.set(0, 1, 0); break; }
            v.normalize();
        }
        // Vanilla swords and tools point to the upper right, wands up: the end towards (1,1,0) is the tip.
        if (v.x + v.y < 0) v.negate();
        axis.set(v);
        float lo = 0, hi = 0, perp = 0;
        for (int i = 0; i < count; i++) {
            Vector3f p = new Vector3f(cells[i * STRIDE], cells[i * STRIDE + 1], cells[i * STRIDE + 2]).sub(center);
            float along = p.dot(axis);
            lo = Math.min(lo, along);
            hi = Math.max(hi, along);
            perp += area(cells, i) / total * p.fma(-along, axis).length();
        }
        center.fma((lo + hi) / 2, axis);
        half = Math.max(.05f, (hi - lo) / 2);
        radius = Math.max(.02f, perp);
        out.set(0, 0, 1);
        if (Math.abs(out.dot(axis)) > .9f) out.set(1, 0, 0);
        axis.cross(out, across).normalize();
        across.cross(axis, out).normalize();
    }

    private static float area(float[] cells, int i) {
        return cells[i * STRIDE + 12] - (i > 0 ? cells[(i - 1) * STRIDE + 12] : 0);
    }

    float length() { return half * 2; }

    /** A point on the main axis: {@code s} 0 at the hilt, 1 at the tip. */
    Vector3f along(float s, Vector3f dest) {
        return dest.set(center).fma((s * 2 - 1) * half, axis);
    }

    /** A random point on the surface, weighted by area; with {@code edgeOnly} only on the outline (or the whole surface for models with none). */
    void sample(RandomSource random, boolean edgeOnly, Vector3f pos, Vector3f normal) {
        float[] c = edgeOnly && edgeCount > 0 ? edges : cells;
        int n = edgeOnly && edgeCount > 0 ? edgeCount : count;
        float pick = random.nextFloat() * c[(n - 1) * STRIDE + 12];
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c[mid * STRIDE + 12] < pick) lo = mid + 1; else hi = mid;
        }
        int o = lo * STRIDE;
        float a = random.nextFloat() * 2 - 1, b = random.nextFloat() * 2 - 1;
        pos.set(c[o] + a * c[o + 3] + b * c[o + 6], c[o + 1] + a * c[o + 4] + b * c[o + 7], c[o + 2] + a * c[o + 5] + b * c[o + 8]);
        normal.set(c[o + 9], c[o + 10], c[o + 11]);
    }

    /** Each cell: centre, two half edges, normal (for the glowing shell). */
    interface CellVisitor { void cell(float cx, float cy, float cz, float ax, float ay, float az, float bx, float by, float bz, float nx, float ny, float nz); }

    void forEachCell(CellVisitor visitor) {
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            visitor.cell(cells[o], cells[o + 1], cells[o + 2], cells[o + 3], cells[o + 4], cells[o + 5], cells[o + 6], cells[o + 7], cells[o + 8],
                    cells[o + 9], cells[o + 10], cells[o + 11]);
        }
    }

    // ---- cache ---------------------------------------------------------------------------------

    /** Baked quads do not change until resources reload, so a model is known by its first quad; after a reload every quad is new and the old entries go stale. */
    private static final Map<BakedQuad, ItemSurface> CACHE = new IdentityHashMap<>();

    static void clear() { CACHE.clear(); }

    static @Nullable ItemSurface of(List<BakedQuad> quads) {
        if (quads.isEmpty()) return null;
        BakedQuad first = quads.getFirst();
        if (CACHE.containsKey(first)) return CACHE.get(first);
        ItemSurface s = build(quads);
        if (CACHE.size() > 512) CACHE.clear();
        CACHE.put(first, s);
        return s;
    }

    private static @Nullable ItemSurface build(List<BakedQuad> quads) {
        FloatList all = new FloatList(), edge = new FloatList();
        float total = 0, edgeTotal = 0;
        Vector3f e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f(), p0 = new Vector3f();
        for (BakedQuad q : quads) {
            p0.set(q.position0());
            new Vector3f(q.position1()).sub(p0, e1);
            new Vector3f(q.position3()).sub(p0, e2);
            e1.cross(e2, n);
            float area = n.length();
            if (area < 1e-8f) continue;
            n.div(area);
            // Normals point outwards, the way the quad was baked to face.
            var dir = new Vector3f(q.direction().getStepX(), q.direction().getStepY(), q.direction().getStepZ());
            if (n.dot(dir) < 0) n.negate();
            TextureAtlasSprite sprite = q.materialInfo().sprite();
            SpriteContents contents = sprite.contents();
            float su0 = texel(UVPair.unpackU(q.packedUV0()), sprite.getU0(), sprite.getU1(), contents.width());
            float sv0 = texel(UVPair.unpackV(q.packedUV0()), sprite.getV0(), sprite.getV1(), contents.height());
            float su1 = texel(UVPair.unpackU(q.packedUV1()), sprite.getU0(), sprite.getU1(), contents.width());
            float sv1 = texel(UVPair.unpackV(q.packedUV1()), sprite.getV0(), sprite.getV1(), contents.height());
            float su3 = texel(UVPair.unpackU(q.packedUV3()), sprite.getU0(), sprite.getU1(), contents.width());
            float sv3 = texel(UVPair.unpackV(q.packedUV3()), sprite.getV0(), sprite.getV1(), contents.height());
            int ns = Mth.clamp(Math.round(Math.max(Math.abs(su1 - su0), Math.abs(sv1 - sv0))), 1, MAX_CELLS);
            int nt = Mth.clamp(Math.round(Math.max(Math.abs(su3 - su0), Math.abs(sv3 - sv0))), 1, MAX_CELLS);
            boolean isEdge = Math.abs(n.z) < .5f;
            float cellArea = area / (ns * nt);
            for (int i = 0; i < ns; i++) for (int j = 0; j < nt; j++) {
                float s = (i + .5f) / ns, t = (j + .5f) / nt;
                int tx = Mth.clamp((int) Math.floor(su0 + s * (su1 - su0) + t * (su3 - su0)), 0, contents.width() - 1);
                int ty = Mth.clamp((int) Math.floor(sv0 + s * (sv1 - sv0) + t * (sv3 - sv0)), 0, contents.height() - 1);
                if (contents.isTransparent(0, tx, ty)) continue;
                total += cellArea;
                put(all, p0, e1, e2, n, s, t, ns, nt, total);
                if (isEdge) {
                    edgeTotal += cellArea;
                    put(edge, p0, e1, e2, n, s, t, ns, nt, edgeTotal);
                }
            }
        }
        if (all.size == 0) return null;
        return new ItemSurface(all.toArray(), all.size / STRIDE, edge.toArray(), edge.size / STRIDE);
    }

    private static float texel(float u, float u0, float u1, int size) {
        return (u - u0) / (u1 - u0) * size;
    }

    private static void put(FloatList out, Vector3f p0, Vector3f e1, Vector3f e2, Vector3f n, float s, float t, int ns, int nt, float cumulative) {
        out.add(p0.x + s * e1.x + t * e2.x, p0.y + s * e1.y + t * e2.y, p0.z + s * e1.z + t * e2.z);
        out.add(e1.x / (2 * ns), e1.y / (2 * ns), e1.z / (2 * ns));
        out.add(e2.x / (2 * nt), e2.y / (2 * nt), e2.z / (2 * nt));
        out.add(n.x, n.y, n.z);
        out.add(cumulative);
    }

    private static final class FloatList {
        float[] data = new float[STRIDE * 64];
        int size;

        void add(float... v) {
            if (size + v.length > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + v.length));
            System.arraycopy(v, 0, data, size, v.length);
            size += v.length;
        }

        float[] toArray() { return java.util.Arrays.copyOf(data, size); }
    }
}
