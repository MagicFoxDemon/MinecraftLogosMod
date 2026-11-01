package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The 雷霆 wrap (FORM_VFX.md, 魔力缠绕), in the lightning family's language: the item holds a faint blue charge, the
 * projectile wake's cellular veins ({@code lightning/trail_noise}) lying on its surface and jumping a little every
 * few ticks; over it a few short arcs strike between two points of its outline, re-jag every tick, blink and go out,
 * each a saturated blue stroke (so it reads against a day sky, as the damage burst's arcs) under a white-hot core,
 * a blue glow and a soft halo, the ray's jagged-polyline bolts drawn with its {@code line} texture. Every second and
 * a half or so a longer arc snaps across the item with a fork, a flash at both ends and the charge flaring for a
 * moment. Off it fly a few tiny sparks and now and then a stray arc flashes just off the surface (the charge's
 * blinking arcs round the caster), more of both in a swing. What clings to the item is deterministic from the time;
 * only the sparks and stray arcs are kept. All textures are the lightning element's existing ones.
 */
final class LightningWrapVisual extends WrapVisual {
    /** The lightning ray and charge palette: the saturated stroke, the bolt's blue, the deep glow, the pale halo, the hot core. */
    private static final int STROKE = 0x4A9BFF, BOLT = 0x7CC4FF, DEEP = 0x2F78FF, PALE = 0xBCEBFF, HOT = 0xE4F5FF;

    private static final RenderType CHARGE = VfxParts.add("lightning/trail_noise"), LINE = VfxParts.add("lightning_ray/line"),
            LINE_BODY = VfxParts.blend("lightning_ray/line"), DOT = VfxParts.add("lightning/spark"), HALO = VfxParts.add("lightning_ray/glow"),
            SPARK = VfxParts.add("lightning_impact/spark"), SPARK_BODY = VfxParts.blend("lightning_impact/spark"),
            ARCS = VfxParts.add("lightning_impact/arcs"), ARCS_BODY = VfxParts.blend("lightning_impact/arcs");

    /** A model pixel. */
    private static final float PX = 1 / 16f;
    /** The snap: one every this many ticks, lasting this long. */
    private static final int SNAP_PERIOD = 33, SNAP_LIFE = 4;

    private final List<Spark> sparks = new ArrayList<>();

    // ---- clinging to the item ------------------------------------------------------------------

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        List<Arc> arcs = arcs(surface, view, t, seed);
        float snap = snapGlow(t, seed);
        // A slow drift with a jump every three ticks, so the veins twitch rather than flow; brighter while an arc snaps.
        int tick = Mth.floor(t);
        float flicker = .82f + .18f * (float) unit(seed, 7, tick);
        float charge = (.3f + .05f * Mth.sin(t * .9f)) * flicker + .25f * snap;
        float jump = t + Mth.floor(t / 3) * 9.7f;
        collector.submitCustomGeometry(stack, CHARGE, (pose, buf) ->
                SurfaceAuraVisual.shell(pose, buf, surface, jump, 1.4f, .035f, .35f / 16, VfxParts.argb(charge, VfxParts.lerpColor(STROKE, BOLT, snap))));
        if (arcs.isEmpty()) return;
        // The blue stroke first, translucent, then the halo, the glow and the hot core over it.
        collector.order(1).submitCustomGeometry(stack, LINE_BODY, (pose, buf) -> {
            for (Arc a : arcs) ribbon(pose, buf, view, a.pts, a.reach, 1.3f * PX * a.weight, a.alpha * .9f, STROKE);
        });
        collector.order(2).submitCustomGeometry(stack, HALO, (pose, buf) -> {
            // A soft halo round each lit arc, as on the ray's afterglow arcs: what still reads a few blocks away.
            for (Arc a : arcs) {
                if (a.reach < .5f) continue;
                Vec3 mid = a.pts[a.pts.length / 2];
                float r = (float) a.pts[0].distanceTo(a.pts[a.pts.length - 1]) * .6f + 1.5f * PX;
                VfxParts.sprite(pose, buf, view, mid, r * a.weight, 0, VfxParts.argb(a.alpha * .4f, DEEP));
            }
        });
        collector.order(2).submitCustomGeometry(stack, LINE, (pose, buf) -> {
            for (Arc a : arcs) {
                ribbon(pose, buf, view, a.pts, a.reach, 2.4f * PX * a.weight, a.alpha * .45f, BOLT);
                ribbon(pose, buf, view, a.pts, a.reach, .5f * PX * a.weight, a.alpha, HOT);
            }
        });
        collector.order(2).submitCustomGeometry(stack, DOT, (pose, buf) -> {
            for (Arc a : arcs) {
                if (a.weight < 1.1f) continue;
                for (Vec3 end : new Vec3[] {a.pts[0], a.pts[a.pts.length - 1]}) {
                    VfxParts.sprite(pose, buf, view, end, 1.6f * PX, 0, VfxParts.argb(a.alpha * .5f, STROKE));
                    VfxParts.sprite(pose, buf, view, end, .6f * PX, 0, VfxParts.argb(a.alpha, HOT));
                }
            }
        });
    }

    /** One arc as drawn this frame: its joints, how much of it has struck (0–1), its alpha and its weight (1, more for a snap). */
    private record Arc(Vec3[] pts, float reach, float alpha, float weight) {}

    /**
     * The arcs on the surface now. A few slots, each striking again every 7–12 ticks between two points of the outline
     * a few pixels apart (the whole surface on a fist, which has none): it reaches across in a tick, holds for a few
     * ticks taking a new jagged shape each tick and dimming on some, then is dark until its next strike. The snap is
     * one more, longer and forked, once every {@link #SNAP_PERIOD} ticks.
     */
    private static List<Arc> arcs(ItemSurface surface, VfxParts.View view, float t, long seed) {
        List<Arc> out = new ArrayList<>(6);
        float len = surface.length();
        int slots = Math.round(Mth.clamp(len * 3.4f, 2, 5));
        int tick = Mth.floor(t);
        for (int i = 0; i < slots; i++) {
            float period = 7 + (i * 5 % 6);
            float a = t / period + i * .37f;
            int cycle = Mth.floor(a);
            float age = (a - cycle) * period;
            long s = seed * 31 + i * 0x9E3779B9L + cycle * 0x632BE5ABL;
            float lit = 4 + 2.5f * (float) unit(s, 1, 0);
            if (age > lit) continue;
            float d = Math.max(len * (.13f + .12f * (float) unit(s, 2, 0)), (2.2f + 1.2f * (float) unit(s, 3, 0)) * PX);
            Vec3[] ends = ends(surface, view, s, d);
            if (ends == null) continue;
            float blink = unit(s, 4, tick) < .25 && age > 1 ? .35f : 1;
            float alpha = blink * (1 - VfxParts.smooth(lit - 1.2f, lit, age));
            Vec3[] pts = bolt(s ^ tick * 0x5851F42DL, ends[0], ends[1], ends[2], .9f * PX, 1.3f * PX, 8);
            out.add(new Arc(pts, Math.min(1, age / .8f + .15f), alpha, 1));
        }
        // The snap.
        float a = t / SNAP_PERIOD + (float) unit(seed, 9, 0);
        int cycle = Mth.floor(a);
        float age = (a - cycle) * SNAP_PERIOD;
        if (age < SNAP_LIFE) {
            long s = seed * 17 + cycle * 0x2545F491L + 0xC0FFEE;
            float d = Math.max(len * (.42f + .2f * (float) unit(s, 2, 0)), 3.6f * PX);
            Vec3[] ends = ends(surface, view, s, d);
            if (ends != null) {
                float alpha = 1 - VfxParts.smooth(1.5f, SNAP_LIFE, age);
                Vec3[] pts = bolt(s ^ tick * 0x5851F42DL, ends[0], ends[1], ends[2], 1.1f * PX, 2 * PX, 12);
                float reach = Math.min(1, age / .7f + .1f);
                out.add(new Arc(pts, reach, alpha, 1.25f));
                // A short fork off a joint near the middle, reaching outwards.
                int j = 4 + (int) (unit(s, 5, tick) * 5);
                Vec3 from = pts[j], dir = from.subtract(ends[0].add(ends[1]).scale(.5)).add(ends[2].scale(PX * 3));
                if (dir.lengthSqr() > 1e-8) {
                    Vec3 to = from.add(dir.normalize().scale(d * .3f));
                    out.add(new Arc(bolt(s ^ tick * 0x1B873593L, from, to, ends[2], .4f * PX, 0, 4), Math.max(0, reach * 2 - 1), alpha * .8f, 1));
                }
            }
        }
        return out;
    }

    /** How bright the snap is now, 0–1: flaring as it strikes and dying down with it. */
    private static float snapGlow(float t, long seed) {
        float a = t / SNAP_PERIOD + (float) unit(seed, 9, 0);
        float age = (a - Mth.floor(a)) * SNAP_PERIOD;
        return age < SNAP_LIFE + 2 ? 1 - VfxParts.smooth(0, SNAP_LIFE + 2, age) : 0;
    }

    /**
     * Two points of the outline about {@code d} apart, lifted a little off it, and the unit direction the arc bows
     * out to: away from the item, and over the face towards the camera when the two lie on opposite edges.
     */
    private static Vec3[] ends(ItemSurface surface, VfxParts.View view, long seed, float d) {
        RandomSource r = RandomSource.create(seed);
        Vector3f p = new Vector3f(), n = new Vector3f(), q = new Vector3f(), m = new Vector3f(), best = new Vector3f(), bestN = new Vector3f();
        surface.sample(r, true, p, n);
        float err = Float.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            surface.sample(r, true, q, m);
            float e = Math.abs(q.distance(p) - d);
            if (e < err) {
                err = e;
                best.set(q);
                bestN.set(m);
            }
        }
        if (best.distance(p) < d * .4f) return null;
        Vec3 a = new Vec3(p.fma(.3f * PX, n)), b = new Vec3(best.fma(.3f * PX, bestN));
        Vec3 out = new Vec3(n.add(bestN, new Vector3f()));
        Vec3 face = new Vec3(surface.out);
        Vec3 mid = a.add(b).scale(.5);
        if (face.dot(view.cam().subtract(mid)) < 0) face = face.scale(-1);
        out = out.add(face.scale(.6));
        Vec3 along = b.subtract(a).normalize();
        out = out.subtract(along.scale(out.dot(along)));
        if (out.lengthSqr() < 1e-8) out = face;
        return new Vec3[] {a, b, out.normalize()};
    }

    /** A jagged line of {@code n} segments from {@code a} to {@code b}, pinned at both ends, bowed out along {@code out} by {@code bow}, each joint pushed off by up to {@code amp}. */
    private static Vec3[] bolt(long seed, Vec3 a, Vec3 b, Vec3 out, float amp, float bow, int n) {
        Vec3 d = b.subtract(a), side = d.cross(out);
        side = side.lengthSqr() < 1e-10 ? Vec3.ZERO : side.normalize();
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            float s = i / (float) n, env = Mth.sin(Mth.PI * s);
            float x = (float) (unit(seed, 11, i) * 2 - 1) * amp, y = (float) (unit(seed, 12, i) * 2 - 1) * amp;
            pts[i] = a.add(d.scale(s)).add(out.scale(bow * env + x * env)).add(side.scale(y * Mth.sqrt(env)));
        }
        pts[0] = a;
        pts[n] = b;
        return pts;
    }

    /**
     * A camera-facing strip {@code w} wide along the first {@code reach} of a polyline (the line texture's soft
     * profile across it), each joint's side shared by the segments meeting there, thinning to the ends.
     */
    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3[] pts, float reach, float w, float alpha, int rgb) {
        if (alpha <= .01f || reach <= 0) return;
        int n = pts.length - 1;
        float last = reach * n;
        Vec3 prevL = null, prevR = null;
        int prevC = 0;
        for (int i = 0; i <= n && i - 1 < last; i++) {
            float f = Math.min(i, last);
            int k = Math.min(n - 1, (int) f);
            Vec3 at = i <= last ? pts[i] : pts[k].lerp(pts[k + 1], f - k);
            Vec3 tangent = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 across = tangent.cross(view.cam().subtract(at));
            if (across.lengthSqr() < 1e-12) continue;
            float s = f / n, thin = .45f + .55f * Mth.sin(Mth.PI * s);
            across = across.normalize().scale(w * .5f * thin);
            Vec3 l = at.add(across), r = at.subtract(across);
            int c = VfxParts.argb(alpha, rgb);
            if (prevL != null) {
                VfxParts.vertex(pose, buf, prevL, 0, 0, prevC);
                VfxParts.vertex(pose, buf, prevR, 0, 1, prevC);
                VfxParts.vertex(pose, buf, r, 1, 1, c);
                VfxParts.vertex(pose, buf, l, 1, 0, c);
            }
            prevL = l;
            prevR = r;
            prevC = c;
        }
    }

    /** A hash of three numbers, in [0, 1) (as the lightning ray's). */
    private static double unit(long seed, long a, long b) {
        long h = seed ^ a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    // ---- into the world ------------------------------------------------------------------------

    /**
     * Left in the world: a tiny spark darting off the surface, slowing quickly and gone in a few ticks; or a stray
     * arc, a frame of the impact's 2×2 arc strokes flashing just off the surface for a few ticks, blinking and
     * jumping to another stroke as the charge's arcs round the caster do. Both stay where they were let off, so a
     * swing leaves them behind.
     */
    private static final class Spark {
        double x, y, z, px, py, pz, vx, vy, vz;
        final boolean arc;
        final int life, frame, seed;
        final float size, roll;
        int age;

        Spark(boolean arc, Vec3 at, Vec3 v, int life, float size, RandomSource r) {
            this.arc = arc;
            x = px = at.x; y = py = at.y; z = pz = at.z;
            vx = v.x; vy = v.y; vz = v.z;
            this.life = life;
            this.size = size;
            frame = r.nextInt(4);
            seed = r.nextInt(1 << 16);
            roll = r.nextFloat() * Mth.TWO_PI;
        }

        boolean tick() {
            px = x; py = y; pz = z;
            x += vx; y += vy; z += vz;
            double drag = arc ? .6 : .78;
            vx *= drag; vy = vy * drag - (arc ? 0 : .003); vz *= drag;
            return ++age < life;
        }

        Vec3 at(float pt) { return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z)); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource r) {
        float scale = e.scale();
        if (scale < 1e-3f) return;
        ItemSurface s = e.surface;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        float swing = (float) Math.min(5, e.moved() / scale * 4);
        int count = (r.nextFloat() < .5f ? r.nextFloat() < .3f ? 2 : 1 : 0) + (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        for (int i = 0; i < count; i++) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.04 * scale).add(jitter(r, .028 * scale));
            sparks.add(new Spark(false, e.world(pos, r.nextFloat()), v, 4 + r.nextInt(4), Math.max(.012f, .024f * scale), r));
        }
        // A stray arc every so often, one more now and then in a swing.
        if (r.nextFloat() < .07f + .05f * swing) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 at = e.world(pos, r.nextFloat()).add(n.scale(.06 * scale));
            sparks.add(new Spark(true, at, n.scale(.01 * scale), 3 + r.nextInt(3), Math.max(.07f, scale * (.2f + .1f * r.nextFloat())), r));
        }
    }

    private static Vec3 jitter(RandomSource r, double amount) {
        return new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(amount);
    }

    @Override
    void tick() {
        sparks.removeIf(sp -> !sp.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        // A saturated blue body first so they show against a day sky, then the white-hot glow over it.
        OrderedSubmitNodeCollector body = layers.apply(0), glow = layers.apply(1);
        body.submitCustomGeometry(stack, SPARK_BODY, (pose, buf) -> { for (Spark sp : sparks) if (!sp.arc) spark(pose, buf, view, sp, pt, true); });
        body.submitCustomGeometry(stack, ARCS_BODY, (pose, buf) -> { for (Spark sp : sparks) if (sp.arc) arc(pose, buf, view, sp, pt, true); });
        glow.submitCustomGeometry(stack, SPARK, (pose, buf) -> { for (Spark sp : sparks) if (!sp.arc) spark(pose, buf, view, sp, pt, false); });
        glow.submitCustomGeometry(stack, ARCS, (pose, buf) -> { for (Spark sp : sparks) if (sp.arc) arc(pose, buf, view, sp, pt, false); });
    }

    /** A spark stretched along its motion, white-hot cooling to blue. */
    private static void spark(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Spark sp, float pt, boolean body) {
        float k = sp.k(pt);
        Vec3 p = sp.at(pt), v = new Vec3(sp.vx, sp.vy, sp.vz);
        float a = (1 - VfxParts.smooth(.4f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
        double stretch = Math.max(sp.size * 1.5, v.length() * 1.6);
        Vec3 dir = v.lengthSqr() < 1e-10 ? new Vec3(0, 1, 0) : v.normalize();
        int rgb = body ? STROKE : VfxParts.lerpColor(HOT, PALE, k);
        VfxParts.streak(pose, buf, view, p.subtract(dir.scale(stretch)), p.add(dir.scale(sp.size * .5)), sp.size * (body ? 1.3f : 1), VfxParts.argb(a * (body ? .6f : 1), rgb));
    }

    /** A stray arc: dark on some ticks, another stroke every two, turning a little each tick. */
    private static void arc(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Spark sp, float pt, boolean body) {
        int tick = sp.age;
        if ((tick * 7 + sp.seed) % 4 == 0 && tick > 0) return;
        float k = sp.k(pt);
        Vec3 p = sp.at(pt);
        float a = (.55f + .45f * (1 - k)) * VfxParts.fade(view.cam(), p, .5, 1.2);
        if (a <= .01f) return;
        int f = (sp.frame + (tick / 2) * (1 + sp.seed % 3)) % 4;
        float u0 = f % 2 * .5f, v0 = f / 2 * .5f, r = sp.size * (body ? 1 : 1.04f);
        VfxParts.sprite(pose, buf, view, p, r, r, sp.roll + tick * .4f, u0, v0, u0 + .5f, v0 + .5f,
                VfxParts.argb(a * (body ? .8f : .7f), body ? STROKE : PALE));
    }

    @Override
    int particles() { return sparks.size(); }

    @Override
    void clear() { sparks.clear(); }
}
