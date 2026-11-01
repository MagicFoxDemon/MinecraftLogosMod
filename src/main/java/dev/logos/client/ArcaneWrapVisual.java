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
 * The 奥秘 wrap (FORM_VFX.md, 魔力缠绕), in the arcane charge's and ray's language rather than the mana style's threads
 * and wisps: a ring of runes turns round the hand.
 * <p>On the item or fist: a soft rose glow round it (the arcane projectile's orb glow) with a faint sheen of the ray's
 * silk flowing over the surface; and a slowly turning, gently tilting ring of the ray's runes ({@code arcane_ray/runes})
 * on a fine circlet of light, round the blade (gliding up and down it) or round the knuckles, as the grand arcane
 * charge's runes circle its ball: each glows and dims on its own, the far side dimmer, now and then one rewritten as
 * another glyph, two four-point glints ({@code arcane_ray/glint}) twinkling between them, each flaring in turn. A
 * blended deep copy goes under the glowing runes so they read against a day sky, as the charge's do.
 * <p>In the world: a fine stardust drifting off the surface, now and then a rune knocked loose floating up (as the
 * ray's do), and in a swing a wake of glints and stardust left hanging along the blade's path (the arcane projectile's
 * trail of stars). All textures are the arcane element's.
 */
final class ArcaneWrapVisual extends WrapVisual {
    /** The arcane ray's palette (from the projectile and its impact). */
    private static final int PINK = 0xE668EF, VIOLET = 0xCA38FF, DEEP = 0xA136EF, LILAC = 0xE6A0FF, BLUSH = 0xFBB6F2, HOT = 0xF9DCFF;

    private static final RenderType SHEEN = VfxParts.add("arcane_ray/silk"), GLOW = VfxParts.add("arcane/glow"), FUZZ = VfxParts.add("arcane_ray/fuzz"),
            RUNES = VfxParts.add("arcane_ray/runes"), RUNES_BODY = VfxParts.blend("arcane_ray/runes"),
            GLINT = VfxParts.add("arcane_ray/glint"), DOT = VfxParts.add("arcane_ray/dot");

    private final List<Fleck> flecks = new ArrayList<>();

    // ---- clinging to the item ------------------------------------------------------------------

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        boolean fist = surface.length() < .5f;
        float breathe = .85f + .15f * Mth.sin(t * .23f);
        collector.submitCustomGeometry(stack, GLOW, (pose, buf) -> halo(pose, buf, view, surface, fist, VfxParts.argb(.42f * breathe, PINK)));
        collector.submitCustomGeometry(stack, SHEEN, (pose, buf) ->
                SurfaceAuraVisual.shell(pose, buf, surface, t, 1.4f, .025f, .35f / 16, VfxParts.argb(.16f * breathe, PINK)));
        Ring ring = ring(surface, view, t, seed, fist);
        collector.order(1).submitCustomGeometry(stack, RUNES_BODY, (pose, buf) -> {
            for (int i = 0; i < ring.n; i++) if (ring.alpha[i] > .003f) glyph(pose, buf, view, ring, i, VfxParts.argb(ring.alpha[i] * .5f, DEEP));
        });
        collector.order(2).submitCustomGeometry(stack, FUZZ, (pose, buf) -> {
            for (int i = 0; i < ring.n; i++) if (ring.alpha[i] > .003f)
                VfxParts.sprite(pose, buf, view, ring.at[i], ring.size * 2.4f, 0, VfxParts.argb(ring.alpha[i] * .4f, PINK));
        });
        collector.order(2).submitCustomGeometry(stack, RUNES, (pose, buf) -> {
            for (int i = 0; i < ring.n; i++) if (ring.alpha[i] > .003f)
                glyph(pose, buf, view, ring, i, VfxParts.argb(ring.alpha[i], VfxParts.lerpColor(HOT, PINK, .35f + .3f * (i % 2))));
        });
        collector.order(2).submitCustomGeometry(stack, DOT, (pose, buf) -> circlet(pose, buf, view, ring, t));
        collector.order(2).submitCustomGeometry(stack, GLINT, (pose, buf) -> glints(pose, buf, view, ring, t));
    }

    /** The ring the runes ride on, a fine line of light (the dot's middle column), dimmer on the far side and between the runes. */
    private static void circlet(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Ring r, float t) {
        int m = 36;
        float w = r.size * .2f;
        Vec3 toCam = view.cam().subtract(r.center).normalize();
        Vec3 prev = null;
        for (int j = 0; j <= m; j++) {
            float th = r.turn + j * Mth.TWO_PI / m;
            Vec3 off = r.a.scale(Mth.cos(th)).add(r.b.scale(Mth.sin(th)));
            Vec3 at = r.center.add(off.scale(r.radius));
            if (prev != null) {
                float front = (float) off.dot(toCam) * .5f + .5f;
                // Brighter near each rune: |cos| of n half-turns peaks at the runes.
                float near = .55f + .45f * Math.abs(Mth.cos((j - .5f) * Mth.PI * r.n / m));
                VfxParts.streak(pose, buf, view, prev, at, w, .5f, 0, .5f, 1, VfxParts.argb(.55f * near * (.3f + .7f * front), PINK));
            }
            prev = at;
        }
    }

    /** The soft rose glow round the hand: a round one on a fist, a long one hugging an item from hilt to tip. */
    private static void halo(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface s, boolean fist, int color) {
        if (fist) {
            VfxParts.sprite(pose, buf, view, new Vec3(s.center), s.length() * 1.05f, 0, color);
            return;
        }
        float w = Math.max(.2f, s.radius * 4.5f);
        Vec3 hilt = new Vec3(s.along(-.05f, new Vector3f())), tip = new Vec3(s.along(1.05f, new Vector3f()));
        VfxParts.streak(pose, buf, view, hilt, tip, w, color);
    }

    /** The ring of runes as drawn this frame: where each rune is, its glyph, tilt and alpha; and the ring's frame for the glints. */
    private record Ring(int n, float size, Vec3 center, Vec3 a, Vec3 b, float radius, float turn, Vec3[] at, int[] frame, float[] roll, float[] alpha) {}

    /**
     * Runes evenly round a ring square to the main axis, turning about once every six seconds and tipped a little
     * this way and that so it reads as an orbit from every side. On an item it glides slowly up and down the blade
     * (never onto the hilt); on a fist it rings the knuckles. Each rune twinkles on its own, those behind the item
     * dimmer, and every few seconds one dims, changes to another glyph of the 3×3 sheet and brightens again.
     */
    private static Ring ring(ItemSurface s, VfxParts.View view, float t, long seed, boolean fist) {
        int n = fist ? 4 : 5;
        float len = s.length();
        float size = fist ? .09f : Mth.clamp(.075f * len, .055f, .1f);
        float along = fist ? .6f : .55f + .22f * Mth.sin(t * .025f + rand(seed, 1, 0) * Mth.TWO_PI);
        Vec3 center = new Vec3(s.along(along, new Vector3f()));
        Vec3 axis = new Vec3(s.axis).add(new Vec3(s.across).scale(.3f * Mth.sin(t * .021f))).add(new Vec3(s.out).scale(.3f * Mth.cos(t * .017f))).normalize();
        Vec3 a = axis.cross(new Vec3(s.out));
        if (a.lengthSqr() < 1e-6) a = axis.cross(new Vec3(s.across));
        a = a.normalize();
        Vec3 b = axis.cross(a).normalize();
        float radius = s.radius * 1.25f + size * 1.1f;
        float turn = t * .05f + rand(seed, 2, 0) * Mth.TWO_PI;
        Vec3 toCam = view.cam().subtract(center).normalize();
        Vec3[] at = new Vec3[n];
        int[] frame = new int[n];
        float[] roll = new float[n], alpha = new float[n];
        for (int i = 0; i < n; i++) {
            float th = turn + i * Mth.TWO_PI / n;
            Vec3 off = a.scale(Mth.cos(th)).add(b.scale(Mth.sin(th)));
            at[i] = center.add(off.scale(radius));
            float front = (float) off.dot(toCam) * .5f + .5f;
            float twinkle = .55f + .45f * (float) Math.pow(.5 + .5 * Math.sin(t * .11f + i * 2.4f), 1.5);
            // Rewritten now and then: each rune on its own slow cycle, dimming out and back in round the change.
            float c = t / 70 + i * .37f + rand(seed, i, 3);
            int cycle = Mth.floor(c);
            float swap = Mth.clamp(Math.abs(c - cycle - .5f) * 2, 0, 1);
            float rewrite = 1 - VfxParts.smooth(.82f, 1, swap);
            frame[i] = (int) (rand(seed, i, cycle) * 9) % 9;
            roll[i] = Mth.sin(t * .02f + i) * .3f;
            alpha[i] = twinkle * (.45f + .55f * front) * (.15f + .85f * rewrite);
        }
        return new Ring(n, size, center, a, b, radius, turn, at, frame, roll, alpha);
    }

    private static void glyph(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Ring r, int i, int color) {
        float u0 = r.frame[i] % 3 / 3f, v0 = r.frame[i] / 3 / 3f;
        VfxParts.sprite(pose, buf, view, r.at[i], r.size, r.size, r.roll[i], u0, v0, u0 + 1 / 3f, v0 + 1 / 3f, color);
    }

    /** Two four-point glints twinkling between the runes, each flaring in turn, just outside the ring, as the arcane charge's do. */
    private static void glints(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Ring r, float t) {
        for (int g = 0; g < 2; g++) {
            float th = r.turn + (g * 2 + 1) * Mth.PI / r.n + g * .4f;
            Vec3 p = r.center.add(r.a.scale(Mth.cos(th) * r.radius * 1.15f)).add(r.b.scale(Mth.sin(th) * r.radius * 1.15f));
            float wink = .3f + .7f * (float) Math.pow(.5 + .5 * Math.sin(t * .09f + g * Mth.PI), 3);
            VfxParts.sprite(pose, buf, view, p, r.size * (1.2f + .8f * wink), g * Mth.HALF_PI * .5f, VfxParts.argb(wink, HOT));
        }
    }

    private static float rand(long seed, int k, int cycle) {
        long x = seed * 0x9E3779B97F4A7C15L + k * 0xC2B2AE3D27D4EB4FL + cycle * 0x165667B19E3779F9L;
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
        // Stardust drifting off the surface, a little upwards.
        if (r.nextFloat() < .5f) {
            s.sample(r, false, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.006 * scale).add(0, .004 * scale, 0).add(jitter(r, .003 * scale));
            flecks.add(new Fleck(Fleck.Kind.DUST, e.world(pos, r.nextFloat()), v, 14 + r.nextInt(12), Math.max(.014f, .028f * scale), r));
        }
        // Now and then a rune knocked loose, floating up and out as the ray's do.
        if (r.nextInt(30) == 0) {
            s.sample(r, false, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            flecks.add(new Fleck(Fleck.Kind.RUNE, e.world(pos, 1), n.scale(.008 * scale).add(0, .01 * scale, 0), 26 + r.nextInt(12),
                    Math.max(.04f, .08f * scale), r));
        }
        // In a swing, by distance moved: glints and stardust left hanging along the path the blade has just cut.
        float swing = (float) Math.min(8, e.moved() / scale * 5);
        int count = (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        for (int i = 0; i < count; i++) {
            s.along(.2f + .8f * r.nextFloat(), pos);
            boolean star = r.nextFloat() < .3f;
            flecks.add(new Fleck(star ? Fleck.Kind.STAR : Fleck.Kind.DUST, e.world(pos, r.nextFloat()), jitter(r, .003 * scale),
                    star ? 9 + r.nextInt(8) : 10 + r.nextInt(10), star ? Math.max(.05f, .11f * scale) : Math.max(.012f, .024f * scale), r));
        }
    }

    private static Vec3 jitter(RandomSource r, double amount) {
        return new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(amount);
    }

    @Override
    void tick() {
        flecks.removeIf(f -> !f.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        if (flecks.isEmpty()) return;
        // The runes' deep blended copy first, so they read against a bright sky, then the glow over them.
        layers.apply(0).submitCustomGeometry(stack, RUNES_BODY, (pose, buf) -> {
            for (Fleck f : flecks) if (f.kind == Fleck.Kind.RUNE) rune(pose, buf, view, f, pt, true);
        });
        OrderedSubmitNodeCollector to = layers.apply(1);
        to.submitCustomGeometry(stack, FUZZ, (pose, buf) -> {
            for (Fleck f : flecks) {
                if (f.kind != Fleck.Kind.RUNE) continue;
                Vec3 p = f.at(pt);
                VfxParts.sprite(pose, buf, view, p, f.size * 2.2f, 0, VfxParts.argb(.28f * runeAlpha(f, pt) * VfxParts.fade(view.cam(), p, .3, .9), VIOLET));
            }
        });
        to.submitCustomGeometry(stack, RUNES, (pose, buf) -> {
            for (Fleck f : flecks) if (f.kind == Fleck.Kind.RUNE) rune(pose, buf, view, f, pt, false);
        });
        to.submitCustomGeometry(stack, DOT, (pose, buf) -> {
            for (Fleck f : flecks) {
                if (f.kind != Fleck.Kind.DUST) continue;
                float k = f.k(pt);
                Vec3 p = f.at(pt);
                float twinkle = .7f + .3f * Mth.sin((f.age + pt) * .9f + f.phase);
                float a = VfxParts.smooth(0, .1f, k) * (1 - VfxParts.smooth(.5f, 1, k)) * twinkle * VfxParts.fade(view.cam(), p, .3, .9);
                VfxParts.sprite(pose, buf, view, p, f.size, 0, VfxParts.argb(a, VfxParts.lerpColor(HOT, BLUSH, k)));
                VfxParts.sprite(pose, buf, view, p, f.size * 2.6f, 0, VfxParts.argb(a * .35f, PINK));
            }
        });
        to.submitCustomGeometry(stack, GLINT, (pose, buf) -> {
            for (Fleck f : flecks) {
                if (f.kind != Fleck.Kind.STAR) continue;
                float k = f.k(pt);
                Vec3 p = f.at(pt);
                float open = k < .15f ? k / .15f : 1 - VfxParts.smooth(.15f, 1, k);
                float a = open * VfxParts.fade(view.cam(), p, .3, .9);
                VfxParts.sprite(pose, buf, view, p, f.size * (.4f + .6f * open), 0, VfxParts.argb(a, VfxParts.lerpColor(HOT, LILAC, k)));
            }
        });
    }

    /** A rune knocked loose: in over two ticks, a slow twinkle, out over the second half; white-hot cooling to pink and deep violet. */
    private static float runeAlpha(Fleck f, float pt) {
        float k = f.k(pt);
        return Mth.clamp((f.age + pt) / 2, 0, 1) * (1 - VfxParts.smooth(.45f, 1, k)) * (.8f + .2f * Mth.sin((f.age + pt) * .45f + f.phase));
    }

    private static void rune(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Fleck f, float pt, boolean body) {
        Vec3 p = f.at(pt);
        float k = f.k(pt), life = f.age + pt;
        float a = runeAlpha(f, pt) * VfxParts.fade(view.cam(), p, .3, .9);
        if (a <= .003f) return;
        float u0 = f.frame % 3 / 3f, v0 = f.frame / 3 / 3f;
        float size = f.size * (body ? 1 : 1 + .6f * (float) Math.exp(-life * .5f));
        int color = body ? VfxParts.argb(a * .5f, DEEP)
                : VfxParts.argb(a, VfxParts.lerpColor(HOT, VfxParts.lerpColor(PINK, DEEP, k), Mth.clamp(life / 5, 0, 1)));
        VfxParts.sprite(pose, buf, view, p, size, size, f.roll, u0, v0, u0 + 1 / 3f, v0 + 1 / 3f, color);
    }

    @Override
    int particles() { return flecks.size(); }

    @Override
    void clear() { flecks.clear(); }

    /** A particle left in the world: a mote of stardust (DUST), a four-point STAR left in a swing, a RUNE knocked loose. */
    private static final class Fleck {
        enum Kind { DUST, STAR, RUNE }

        final Kind kind;
        double x, y, z, px, py, pz, vx, vy, vz;
        final int life, frame;
        final float size, phase, roll;
        int age;

        Fleck(Kind kind, Vec3 at, Vec3 velocity, int life, float size, RandomSource r) {
            this.kind = kind;
            x = px = at.x; y = py = at.y; z = pz = at.z;
            vx = velocity.x; vy = velocity.y; vz = velocity.z;
            this.life = life;
            this.size = size;
            frame = r.nextInt(9);
            phase = r.nextFloat() * Mth.TWO_PI;
            roll = (r.nextFloat() - .5f) * .6f;
        }

        boolean tick() {
            px = x; py = y; pz = z;
            x += vx; y += vy; z += vz;
            double drag = kind == Kind.STAR ? .8 : .94;
            vx *= drag; vy *= drag; vz *= drag;
            if (kind != Kind.STAR) vy += .0004;
            return ++age < life;
        }

        Vec3 at(float pt) { return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z)); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }
}
