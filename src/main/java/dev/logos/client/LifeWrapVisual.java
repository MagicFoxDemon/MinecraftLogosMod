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
 * The 生命 wrap (FORM_VFX.md, 魔力缠绕), in the life family's language: a living vine has grown round the item.
 * <p>On the item or fist: one thin thorny vine winding from the hand up to the tip (the life ray's and beam's
 * {@code life_ray/vines}, a dark green body under its green glow so it reads against a day sky), swaying a little; a
 * pulse of pale sap light runs up it every couple of seconds, a soft heartbeat. Small leaves bud along it
 * ({@code life_impact/leaf}), unfold, stay a while and fold away again, a few at a time. Under it all a faint silky
 * green sheen breathes on the surface (the beam's {@code life_ray/sheath}).
 * <p>In the world: motes of light, mostly green and some gold, rising and swaying off the surface (the beam's and
 * field's motes), now and then a leaf coming loose and drifting down, turning over as it falls (the beam's and
 * charge's leaves), and a swing tears a few leaves and motes off by distance moved. All textures are the life
 * element's existing ones.
 */
final class LifeWrapVisual extends WrapVisual {
    /** The ray's and beam's vine shade and glow, the beam sheath's green, the sap's pale gold-white, the field's gold. */
    private static final int VINE_SHADE = 0x2E9E2C, VINE = 0x8CF04C, SHEEN = 0x8FF06A, SAP = 0xF6FFE0, GOLD = 0xF2D45A;
    /** Mote tints after LifeBeamParts.light: mostly green, some mint, some gold. */
    private static final int MOTE_GREEN = 0x7FF04A, MINT = 0x4BE8A8;
    /** Leaf tints from {@link LifeBeamParts#LEAF}, without the rare brown. */
    private static final int[] LEAF = {0x2FB83A, 0x58E83A, 0x8FE83A, 0xB8F25A, 0x46C95A};

    private static final RenderType SHEEN_TEX = VfxParts.add("life_ray/sheath"), VINES = VfxParts.add("life_ray/vines"),
            VINES_BODY = VfxParts.blend("life_ray/vines"), BUD = VfxParts.blend("life_impact/leaf"), DOT = VfxParts.add("life_ray/dot"),
            LEAVES = VfxParts.blend("life_ray/leaves");

    /** Where the vine starts and ends along the axis (0 hilt, 1 tip), and how many turns it makes between. */
    private static final float FROM = .02f, TO = .93f, TURNS = 1.35f;

    private final List<Bit> bits = new ArrayList<>();

    // ---- clinging to the item ------------------------------------------------------------------

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        float len = surface.length();
        // A fist is short: the vine and leaves keep a size that still shows on it.
        float width = Math.max(.13f * len, 2.2f / 16), sway = .18f * Mth.sin(t * .045f + seed % 7);
        float sap = Mth.frac(t / 48f + (seed & 15) / 16f) * 1.5f - .25f;
        // The sheen and the vine's dark body first (so the vine reads by day), then the leaves, then the glow over them.
        collector.order(1).submitCustomGeometry(stack, VINES_BODY, (pose, buf) ->
                vine(pose, buf, view, surface, sway, width, sap, VfxParts.argb(.62f, VINE_SHADE), 0, 0));
        collector.order(2).submitCustomGeometry(stack, BUD, (pose, buf) -> buds(pose, buf, view, surface, t, seed, sway));
        OrderedSubmitNodeCollector over = collector.order(3);
        // Finer and a little fainter on a fist, so one patch of the sheen does not cover it.
        float breathe = (.2f + .05f * Mth.sin(t * .08f)) * (.7f + .3f * VfxParts.smooth(.35f, .7f, len)), tiles = 1.4f / Math.max(len, .45f);
        over.submitCustomGeometry(stack, SHEEN_TEX, (pose, buf) ->
                SurfaceAuraVisual.shell(pose, buf, surface, t, tiles, .006f, .35f / 16, VfxParts.argb(breathe, SHEEN)));
        over.submitCustomGeometry(stack, VINES, (pose, buf) -> vine(pose, buf, view, surface, sway, width, sap, VfxParts.argb(.5f, VINE), .35f, SAP));
        over.submitCustomGeometry(stack, DOT, (pose, buf) -> sapHead(pose, buf, view, surface, sway, width, sap));
    }

    /** A point on the vine at {@code s} along the axis: wound round it on an ellipse a little wider than the item. */
    private static Vector3f onVine(ItemSurface surface, float s, float sway, Vector3f dest) {
        float len = surface.length();
        float r1 = surface.radius * 1.35f + .04f * len, r2 = r1 * .8f;
        float th = Mth.TWO_PI * TURNS * s + .6f + sway * Mth.sin(s * 5.5f);
        return surface.along(s, dest).fma(Mth.cos(th) * r1, surface.across).fma(Mth.sin(th) * r2, surface.out);
    }

    /**
     * The vine as one camera-facing ribbon wound round the axis, thinner at the hand and tapering to a tip; its
     * texture stays put along it (a vine does not slide). With {@code pulse} &gt; 0 the sap's bright band, centred at
     * {@code sap} along the axis, lightens it to {@code pulseRgb}.
     */
    private static void vine(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float sway, float width,
            float sap, int color, float pulse, int pulseRgb) {
        int m = surface.length() > .6f ? 32 : 14;
        float tile = width * 2.2f, alpha = (color >>> 24) / 255f;
        Vec3 prev = null, prevSide = null;
        float prevU = 0;
        int prevC = 0;
        float arc = 0;
        Vector3f p = new Vector3f(), q = new Vector3f();
        for (int i = 0; i < m; i++) {
            float k = i / (m - 1f), s = Mth.lerp(k, FROM, TO);
            Vec3 at = new Vec3(onVine(surface, s, sway, p));
            Vec3 ahead = new Vec3(onVine(surface, Math.min(TO + .02f, s + .02f), sway, q)).subtract(at);
            if (prev != null) arc += (float) at.distanceTo(prev);
            Vec3 side = ahead.cross(at.subtract(view.cam()));
            side = side.lengthSqr() < 1e-12 ? new Vec3(surface.out) : side.normalize();
            float taper = Math.min(.65f + .35f * k / .15f, 1) * (.35f + .65f * (1 - VfxParts.smooth(.8f, 1, k)));
            side = side.scale(width * .5f * taper);
            float u = arc / tile;
            float bump = pulse <= 0 ? 0 : (float) Math.exp(-(s - sap) * (s - sap) / (2 * .07f * .07f));
            int c = VfxParts.argb(alpha * Math.min(1, .25f + 3 * k) + pulse * bump, VfxParts.lerpColor(color, pulseRgb, bump * .8f));
            if (prev != null) {
                VfxParts.vertex(pose, buf, prev.add(prevSide), prevU, 0, prevC);
                VfxParts.vertex(pose, buf, prev.subtract(prevSide), prevU, 1, prevC);
                VfxParts.vertex(pose, buf, at.subtract(side), u, 1, c);
                VfxParts.vertex(pose, buf, at.add(side), u, 0, c);
            }
            prev = at;
            prevSide = side;
            prevU = u;
            prevC = c;
        }
    }

    /** The sap's bright head: a small soft light riding up the vine with the pulse, gone at either end. */
    private static void sapHead(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float sway, float width, float sap) {
        float a = VfxParts.smooth(FROM, FROM + .12f, sap) * (1 - VfxParts.smooth(TO - .12f, TO, sap));
        if (a <= .01f) return;
        Vec3 at = new Vec3(onVine(surface, sap, sway, new Vector3f()));
        VfxParts.sprite(pose, buf, view, at, width * .9f, 0, VfxParts.argb(.3f * a, GOLD));
        VfxParts.sprite(pose, buf, view, at, width * .4f, 0, VfxParts.argb(.7f * a, SAP));
    }

    /**
     * Leaves budding on the vine, each standing out from it and leaning towards the tip: it unfolds, stays a while,
     * folds away and later buds again somewhere else along the vine. A few at a time; deterministic from the time.
     */
    private static void buds(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float t, long seed, float sway) {
        float len = surface.length();
        int n = len > .6f ? 5 : 2;
        float size = Mth.clamp(.2f * len, 2.6f / 16, 3.6f / 16);
        Vector3f p = new Vector3f(), c = new Vector3f();
        for (int i = 0; i < n; i++) {
            float period = 150 + (i * 47 % 70);
            float a = t / period + i * .37f + (seed & 7) * .11f;
            int cycle = Mth.floor(a);
            float age = a - cycle;
            float grow = VfxParts.smooth(0, .22f, age) * (1 - VfxParts.smooth(.78f, 1, age));
            if (grow <= .02f) continue;
            float h = hash(seed, i, cycle);
            float s = Mth.lerp((i + .2f + .6f * h) / n, .18f, TO - .05f);
            onVine(surface, s, sway, p);
            surface.along(s, c);
            Vector3f outward = new Vector3f(p).sub(c);
            if (outward.lengthSquared() < 1e-10f) outward.set(surface.across);
            outward.normalize().fma(.8f, surface.axis).normalize();
            float l = size * (.75f + .25f * h) * (.45f + .55f * grow);
            // Swaying a little on its stem.
            outward.fma(.15f * Mth.sin(t * .07f + i * 1.9f), surface.across).normalize();
            Vec3 base = new Vec3(p), tip = base.add(outward.x * l, outward.y * l, outward.z * l);
            int rgb = LEAF[(int) (hash(seed ^ 0x51ED, i, cycle) * LEAF.length)];
            leaf(pose, buf, view, base, tip, l * .95f, VfxParts.argb(.95f * Math.min(1, grow * 1.6f), rgb));
        }
    }

    /** The upright leaf texture stood from {@code base} (its stem) to {@code tip}, {@code w} wide and turned to the camera. */
    private static void leaf(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 base, Vec3 tip, float w, int color) {
        Vec3 along = tip.subtract(base);
        Vec3 side = along.cross(base.add(tip).scale(.5).subtract(view.cam()));
        if (side.lengthSqr() < 1e-12) return;
        side = side.normalize().scale(w * .5);
        VfxParts.vertex(pose, buf, base.subtract(side), 0, 1, color);
        VfxParts.vertex(pose, buf, base.add(side), 1, 1, color);
        VfxParts.vertex(pose, buf, tip.add(side), 1, 0, color);
        VfxParts.vertex(pose, buf, tip.subtract(side), 0, 0, color);
    }

    private static float hash(long seed, int i, int cycle) {
        long x = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL + cycle * 0x165667B19E3779F9L;
        x = (x ^ x >>> 31) * 0xBF58476D1CE4E5B9L;
        return ((x ^ x >>> 29) >>> 40) / (float) (1L << 24);
    }

    // ---- into the world ------------------------------------------------------------------------

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource r) {
        float scale = e.scale();
        if (scale < 1e-3f) return;
        ItemSurface s = e.surface;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        if (r.nextFloat() < .45f) {
            s.sample(r, false, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.005 * scale).add(0, .006 * scale, 0).add(jitter(r, .002 * scale));
            bits.add(new Bit(false, e.world(pos, r.nextFloat()), v, 30 + r.nextInt(24), Math.max(.012f, .026f * scale), moteTint(r), r));
        }
        if (r.nextFloat() < .045f) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            bits.add(new Bit(true, e.world(pos, r.nextFloat()), n.scale(.006 * scale), 50 + r.nextInt(30), Math.max(.025f, .055f * scale),
                    LEAF[r.nextInt(LEAF.length)], r));
        }
        float swing = (float) Math.min(3, e.moved() / scale * 2.5);
        int swingCount = (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        for (int i = 0; i < swingCount; i++) {
            s.along(.2f + .8f * r.nextFloat(), pos);
            boolean leaf = r.nextFloat() < .3f;
            bits.add(leaf
                    ? new Bit(true, e.world(pos, r.nextFloat()), jitter(r, .01 * scale).add(0, .006 * scale, 0), 40 + r.nextInt(25),
                            Math.max(.025f, .055f * scale), LEAF[r.nextInt(LEAF.length)], r)
                    : new Bit(false, e.world(pos, r.nextFloat()), jitter(r, .008 * scale).add(0, .004 * scale, 0), 24 + r.nextInt(16),
                            Math.max(.012f, .026f * scale), moteTint(r), r));
        }
    }

    private static int moteTint(RandomSource r) {
        int k = r.nextInt(6);
        return k == 0 ? GOLD : k == 1 ? MINT : MOTE_GREEN;
    }

    private static Vec3 jitter(RandomSource r, double amount) {
        return new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(amount);
    }

    @Override
    void tick() {
        bits.removeIf(b -> !b.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        if (bits.isEmpty()) return;
        // The leaves alpha-blended first, then the motes' glow.
        layers.apply(0).submitCustomGeometry(stack, LEAVES, (pose, buf) -> {
            for (Bit b : bits) {
                if (!b.leaf) continue;
                float k = b.k(pt);
                Vec3 p = b.at(pt);
                float a = VfxParts.smooth(0, .1f, k) * (1 - VfxParts.smooth(.6f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
                // Turning over as it falls.
                float flip = .3f + .7f * Math.abs(Mth.cos((b.age + pt) * .09f + b.phase));
                float u0 = (b.frame & 1) * .5f, v0 = (b.frame >> 1 & 1) * .5f;
                VfxParts.sprite(pose, buf, view, p, b.size, b.size * flip, b.roll + b.spin * (b.age + pt), u0, v0, u0 + .5f, v0 + .5f,
                        VfxParts.argb(.92f * a, b.rgb));
            }
        });
        layers.apply(1).submitCustomGeometry(stack, DOT, (pose, buf) -> {
            for (Bit b : bits) {
                if (b.leaf) continue;
                float k = b.k(pt);
                Vec3 p = b.at(pt);
                float twinkle = .7f + .3f * Mth.sin((b.age + pt) * .45f + b.phase);
                float a = VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.55f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
                VfxParts.sprite(pose, buf, view, p, b.size * 2.4f, 0, VfxParts.argb(.5f * a, b.rgb));
                VfxParts.sprite(pose, buf, view, p, b.size, 0, VfxParts.argb(a * twinkle, SAP));
            }
        });
    }

    @Override
    int particles() { return bits.size(); }

    @Override
    void clear() { bits.clear(); }

    /** A leaf come loose (drifts down swaying, turning over) or a mote of light (rises swaying). */
    private static final class Bit {
        final boolean leaf;
        double x, y, z, px, py, pz, vx, vy, vz;
        final int life, frame, rgb;
        final float size, roll, spin, phase, swayX, swayZ;
        int age;

        Bit(boolean leaf, Vec3 at, Vec3 velocity, int life, float size, int rgb, RandomSource r) {
            this.leaf = leaf;
            x = px = at.x; y = py = at.y; z = pz = at.z;
            vx = velocity.x; vy = velocity.y; vz = velocity.z;
            this.life = life;
            this.size = size;
            this.rgb = rgb;
            frame = r.nextInt(4);
            roll = r.nextFloat() * Mth.TWO_PI;
            spin = (r.nextFloat() - .5f) * (leaf ? .12f : 0);
            phase = r.nextFloat() * Mth.TWO_PI;
            float sway = leaf ? .004f : .0015f;
            swayX = (r.nextFloat() - .5f) * 2 * sway;
            swayZ = (r.nextFloat() - .5f) * 2 * sway;
        }

        boolean tick() {
            px = x; py = y; pz = z;
            // Swaying from side to side as it drifts.
            double s = Math.cos(age * (leaf ? .11 : .15) + phase);
            x += vx + swayX * s; y += vy; z += vz + swayZ * s;
            double drag = leaf ? .9 : .95;
            vx *= drag; vy *= drag; vz *= drag;
            // Leaves settle slowly downward; light rises.
            vy += leaf ? -.0005 : .00035;
            return ++age < life;
        }

        Vec3 at(float pt) { return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z)); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }
}
