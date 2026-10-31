package dev.logos.client;

import java.util.List;
import java.util.function.IntFunction;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * How a surface aura looks (after KriptoFX Weapon Effects 2; FORM_VFX.md).
 * <p>Fire (Effect1): two or three camera-facing flame flipbooks stretched along the blade fade in and out in turn
 * over a blue-violet "fuel" strip and a hot glow on the surface; in the world, small flames licking up from the
 * outline and rising embers, and in a swing turning balls of flame scattered along the blade by distance moved
 * (the original's FireVelocity).
 * <p>Mana (Effects 2, 11 and 12 together): a few thin threads of light spiralling up the item to its tip over a
 * flowing glow of mana on the surface; in the world, motes with trails drifting off the surface and circling the
 * item, now and then a rune floating up, and a string of motes thrown out in a swing.
 * <p>What clings to the item is drawn in model space (0–1 is one block, the camera moved into model space too); the
 * particles left in the world are {@link Mote}s.
 */
final class SurfaceAuraVisual {
    private SurfaceAuraVisual() {}

    private static final int HEAT = 0xFF6A20, EMBER_HOT = 0xFFD890, EMBER = 0xFF7A2A, EMBER_COOL = 0xA8261A, FIRE_TINT = 0xFF9A50, BODY = 0xE2481A;
    private static final int MANA = 0x5FA8FF, MANA_CORE = 0xD4E8FF;

    private static final RenderType FIRE = VfxParts.add("surface/fire_loop"), FIRE_GRAY = VfxParts.add("surface/fire_gray"),
            FIRE_BODY = VfxParts.blend("surface/fire_gray"), FUEL = VfxParts.add("surface/fire_fuel"), TRAIL = VfxParts.add("surface/trail"), RUNES = VfxParts.add("surface/runes"),
            NOISE = VfxParts.add("fire/ares_noise"), EMBER_TEX = VfxParts.add("fire_impact/ember"), SPARK = VfxParts.add("arcane/spark");

    /** The flame flipbook: 4 columns by 16 rows, 64 frames, each a blade's worth of flame lying sideways (the left quarter or so is the hilt, left empty). */
    private static final int COLS = 4, FRAMES = 64;

    // ---- clinging to the item ------------------------------------------------------------------

    static void local(SurfaceAura aura, ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        float h = aura.hue();
        switch (aura.style()) {
            case FIRE -> {
                collector.submitCustomGeometry(stack, NOISE, (pose, buf) ->
                        shell(pose, buf, surface, t, 1.6f, .035f, .5f / 16, VfxParts.argb(.32f + .06f * Mth.sin(t * .9f), VfxParts.hue(HEAT, h))));
                // The translucent body of the flame first (so its shape shows against a bright day), then the same
                // frames again as additive glow.
                collector.order(1).submitCustomGeometry(stack, FIRE_BODY, (pose, buf) ->
                        sheets(pose, buf, view, surface, t, seed, 3, 56, 1.3f, .42f, .75f, VfxParts.hue(BODY, h)));
                collector.order(2).submitCustomGeometry(stack, FUEL, (pose, buf) -> sheets(pose, buf, view, surface, t, seed ^ 0x5EED, 2, 60, .95f, .3f, .9f, 0xFFFFFF));
                collector.order(2).submitCustomGeometry(stack, h == 0 ? FIRE : FIRE_GRAY, (pose, buf) ->
                        sheets(pose, buf, view, surface, t, seed, 3, 56, 1.3f, .42f, 1, h == 0 ? 0xFFFFFF : VfxParts.hue(FIRE_TINT, h)));
            }
            case MANA -> {
                int mana = VfxParts.hue(MANA, h), core = VfxParts.hue(MANA_CORE, h);
                collector.submitCustomGeometry(stack, NOISE, (pose, buf) ->
                        shell(pose, buf, surface, t, 2.2f, .06f, .45f / 16, VfxParts.argb(.3f + .08f * Mth.sin(t * .35f), mana)));
                collector.submitCustomGeometry(stack, TRAIL, (pose, buf) -> {
                    for (int j = 0; j < 3; j++) thread(pose, buf, view, surface, t, j, .16f, .4f, mana, false);
                });
                collector.submitCustomGeometry(stack, EMBER_TEX, (pose, buf) -> {
                    for (int j = 0; j < 3; j++) thread(pose, buf, view, surface, t, j, .045f, 1, core, true);
                });
            }
        }
    }

    /**
     * Flame sheets along the blade: {@code n} of them taking turns, each living {@code period} ticks, playing the
     * 64 frames and slowly growing meanwhile (the original's size 0.7 → 1), its alpha fading in, holding a moment and
     * fading out as in the original. A sheet is {@code lengthK} times the blade long and {@code widthK} times its
     * length wide.
     */
    private static void sheets(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float t, long seed,
            int n, int period, float lengthK, float widthK, float alpha, int rgb) {
        Vector3f mid = surface.along(.55f, new Vector3f());
        for (int k = 0; k < n; k++) {
            float a = t / period + (float) k / n;
            int cycle = Mth.floor(a);
            float age = a - cycle;
            float fade = age < .406f ? age / .406f : age < .485f ? 1 : 1 - (age - .485f) / .515f;
            float len = lengthK * surface.length() * (.7f + .3f * age);
            int f = (int) (rand(seed, k, cycle) * FRAMES + age * FRAMES) % FRAMES;
            Vec3 c = new Vec3(mid), along = new Vec3(surface.axis).scale(len / 2);
            VfxParts.streak(pose, buf, view, c.subtract(along), c.add(along), len * widthK,
                    (f % COLS) / (float) COLS, (f / COLS) / 16f, (f % COLS + 1) / (float) COLS, (f / COLS + 1) / 16f, VfxParts.argb(alpha * fade, rgb));
        }
    }

    /** A flowing glow just outside the item's surface (the original's noise decal on the weapon): each cell pushed out and enlarged a little, the noise flowing along the axis. */
    private static void shell(PoseStack.Pose pose, VertexConsumer buf, ItemSurface surface, float t, float tiles, float flow, float offset, int color) {
        Vector3f axis = surface.axis, across = surface.across;
        surface.forEachCell((cx, cy, cz, ax, ay, az, bx, by, bz, nx, ny, nz) -> {
            float ox = cx + nx * offset, oy = cy + ny * offset, oz = cz + nz * offset, g = 1.35f;
            for (int i = 0; i < 4; i++) {
                float sa = i == 0 || i == 1 ? -g : g, sb = i == 0 || i == 3 ? g : -g;
                float x = ox + sa * ax + sb * bx, y = oy + sa * ay + sb * by, z = oz + sa * az + sb * bz;
                float u = (x * axis.x + y * axis.y + z * axis.z) * tiles - t * flow, v = (x * across.x + y * across.y + z * across.z) * tiles + t * flow * .3f;
                VfxParts.vertex(pose, buf, new Vec3(x, y, z), u, v, color);
            }
        });
    }

    /**
     * A thread of mana spiralling up to the tip (the original Effect11's thin trail): its head climbs from beyond the
     * hilt to beyond the tip and starts again, the three a third apart, trailing a fading tail on an ellipse round
     * the axis a little wider than the blade (so it gets round flat items front and back). With {@code line} it uses
     * only the dot texture's middle column, a solid line soft at both sides; otherwise the wispy trail texture flows
     * along it as the glow outside.
     */
    private static void thread(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float t, int j,
            float widthK, float alpha, int rgb, boolean line) {
        int m = 16;
        float len = surface.length(), head = Mth.frac(t * .014f + j / 3f) * 1.6f - .25f, span = .6f;
        float r1 = surface.radius * 1.5f + .05f * len, r2 = r1 * .85f;
        Vec3 prev = null;
        float prevU = 0;
        for (int i = 0; i < m; i++) {
            float k = i / (m - 1f), s = head - span * (1 - k);
            float th = Mth.TWO_PI * 1.4f * s + j * 2.094f + t * .06f;
            Vector3f p = surface.along(s, new Vector3f()).fma(Mth.cos(th) * r1, surface.across).fma(Mth.sin(th) * r2, surface.out);
            Vec3 at = new Vec3(p);
            float u = line ? .5f : k * 2 - t * .03f;
            if (prev != null) {
                float w = (float) Math.pow(k, 1.3) * VfxParts.smooth(-.05f, .12f, s) * (1 - VfxParts.smooth(.92f, 1.08f, s));
                VfxParts.streak(pose, buf, view, prev, at, widthK * len, prevU, 0, u, 1, VfxParts.argb(alpha * w, rgb));
            }
            prev = at;
            prevU = u;
        }
    }

    private static float rand(long seed, int k, int cycle) {
        long x = seed * 0x9E3779B97F4A7C15L + k * 0xC2B2AE3D27D4EB4FL + cycle * 0x165667B19E3779F9L;
        x = (x ^ x >>> 31) * 0xBF58476D1CE4E5B9L;
        return ((x ^ x >>> 29) >>> 40) / (float) (1L << 24);
    }

    // ---- into the world ------------------------------------------------------------------------

    static void emit(SurfaceAuras.Emitter e, RandomSource r, List<Mote> out) {
        float scale = e.scale();
        if (scale < 1e-3f) return;
        ItemSurface s = e.surface;
        float h = e.aura.hue();
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        // More in a swing, by distance moved (the original's rateOverDistance); the fraction rounds up at random.
        float swing = (float) Math.min(12, e.moved() / scale * 7);
        int swingCount = (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        Vec3 axis = e.direction(s.axis).normalize();
        switch (e.aura.style()) {
            case FIRE -> {
                if (r.nextFloat() < .8f) {
                    s.sample(r, true, pos, normal);
                    Vec3 n = e.direction(normal).normalize();
                    out.add(new Mote(Mote.Kind.TONGUE, e.world(pos, r.nextFloat()), n.scale(.004 * scale).add(0, .012 * scale, 0),
                            10 + r.nextInt(7), scale * (.4f + .15f * r.nextFloat()), h, r));
                }
                for (int i = r.nextFloat() < .35f ? 2 : 1; i > 0; i--) {
                    s.sample(r, true, pos, normal);
                    Vec3 n = e.direction(normal).normalize();
                    Vec3 v = n.scale(.012 * scale).add(0, .018 * scale, 0).add(jitter(r, .008 * scale));
                    out.add(new Mote(Mote.Kind.EMBER, e.world(pos, r.nextFloat()), v, 16 + r.nextInt(16), Math.max(.012f, .028f * scale), h, r));
                }
                for (int i = 0; i < swingCount; i++) {
                    s.along(.15f + .85f * r.nextFloat(), pos);
                    out.add(new Mote(Mote.Kind.FLAME, e.world(pos, r.nextFloat()), new Vec3(0, .01 * scale, 0).add(jitter(r, .006 * scale)),
                            10 + r.nextInt(11), scale * (.42f + .16f * r.nextFloat()), h, r));
                }
            }
            case MANA -> {
                for (int i = r.nextFloat() < .4f ? 2 : 1; i > 0; i--) {
                    s.sample(r, false, pos, normal);
                    Vec3 n = e.direction(normal).normalize();
                    Vec3 v = n.scale(.01 * scale).add(axis.cross(n).scale(.014 * scale)).add(jitter(r, .004 * scale));
                    out.add(new Mote(Mote.Kind.WISP, e.world(pos, r.nextFloat()), v, 18 + r.nextInt(15), Math.max(.014f, .036f * scale), h, r));
                }
                for (int i = 0; i < swingCount; i++) {
                    s.along(.1f + .9f * r.nextFloat(), pos);
                    out.add(new Mote(Mote.Kind.WISP, e.world(pos, r.nextFloat()), jitter(r, .01 * scale), 14 + r.nextInt(12),
                            Math.max(.012f, .03f * scale), h, r));
                }
                if (r.nextInt(26) == 0) {
                    s.sample(r, false, pos, normal);
                    Vec3 n = e.direction(normal).normalize();
                    out.add(new Mote(Mote.Kind.RUNE, e.world(pos, 1), n.scale(.006 * scale).add(0, .008 * scale, 0), 34 + r.nextInt(10),
                            Math.max(.04f, .1f * scale), h, r));
                }
            }
        }
    }

    private static Vec3 jitter(RandomSource r, double amount) {
        return new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(amount);
    }

    /** A particle left in the world. */
    static final class Mote {
        enum Kind { TONGUE, FLAME, EMBER, WISP, RUNE }

        private static final int TRAIL = 8;
        final Kind kind;
        double x, y, z, px, py, pz, vx, vy, vz;
        final int life, frame0, glyph;
        final float size, hue, spin;
        float roll;
        int age;
        /** Where a wisp has been, newest first. */
        final double[] trail;
        int trailCount;

        Mote(Kind kind, Vec3 at, Vec3 velocity, int life, float size, float hue, RandomSource r) {
            this.kind = kind;
            x = px = at.x; y = py = at.y; z = pz = at.z;
            vx = velocity.x; vy = velocity.y; vz = velocity.z;
            this.life = life;
            this.size = size;
            this.hue = hue;
            frame0 = r.nextInt(FRAMES);
            glyph = r.nextInt(24);
            roll = r.nextFloat() * Mth.TWO_PI;
            spin = (r.nextFloat() - .5f) * (kind == Kind.FLAME ? .25f : .05f);
            trail = kind == Kind.WISP ? new double[TRAIL * 3] : null;
        }

        boolean tick() {
            px = x; py = y; pz = z;
            if (trail != null) {
                System.arraycopy(trail, 0, trail, 3, trail.length - 3);
                trail[0] = x; trail[1] = y; trail[2] = z;
                trailCount = Math.min(TRAIL, trailCount + 1);
            }
            x += vx; y += vy; z += vz;
            double drag = switch (kind) { case FLAME -> .86; case EMBER -> .95; case WISP -> .93; default -> .97; };
            vx *= drag; vy *= drag; vz *= drag;
            vy += switch (kind) { case EMBER -> .0012 * size / .028; case TONGUE -> .0015; case WISP -> .0006; default -> 0; };
            roll += spin;
            return ++age < life;
        }

        Vec3 at(float pt) { return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z)); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }

    // ---- drawing the particles in the world ----------------------------------------------------

    static void world(List<Mote> motes, VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        // Flames: the translucent body first, then the additive glow over it.
        layers.apply(0).submitCustomGeometry(stack, FIRE_BODY, (pose, buf) -> {
            for (Mote m : motes) flame(pose, buf, view, m, pt, VfxParts.hue(BODY, m.hue), .75f);
        });
        OrderedSubmitNodeCollector to = layers.apply(1);
        to.submitCustomGeometry(stack, FIRE, (pose, buf) -> { for (Mote m : motes) if (m.hue == 0) flame(pose, buf, view, m, pt, 0xFFFFFF, 1); });
        to.submitCustomGeometry(stack, FIRE_GRAY, (pose, buf) -> {
            for (Mote m : motes) if (m.hue != 0) flame(pose, buf, view, m, pt, VfxParts.hue(FIRE_TINT, m.hue), 1);
        });
        to.submitCustomGeometry(stack, EMBER_TEX, (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != Mote.Kind.EMBER) continue;
                float k = m.k(pt);
                Vec3 p = m.at(pt), v = new Vec3(m.vx, m.vy, m.vz);
                int rgb = VfxParts.hue(k < .4f ? VfxParts.lerpColor(EMBER_HOT, EMBER, k / .4f) : VfxParts.lerpColor(EMBER, EMBER_COOL, (k - .4f) / .6f), m.hue);
                float a = (1 - VfxParts.smooth(.6f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
                double stretch = Math.max(m.size * 1.2, v.length() * 2.2);
                Vec3 dir = v.lengthSqr() < 1e-10 ? new Vec3(0, 1, 0) : v.normalize();
                VfxParts.streak(pose, buf, view, p.subtract(dir.scale(stretch)), p.add(dir.scale(m.size * .5)), m.size * (1 - .4f * k), VfxParts.argb(a, rgb));
            }
        });
        // Wisp trails: the dot texture's middle column, a line soft at both sides, thinner and fainter towards its end.
        to.submitCustomGeometry(stack, EMBER_TEX, (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != Mote.Kind.WISP || m.trailCount < 1) continue;
                float k = m.k(pt), a = (1 - VfxParts.smooth(.55f, 1, k)) * VfxParts.smooth(0, .1f, k);
                int rgb = VfxParts.hue(MANA, m.hue);
                Vec3 prev = m.at(pt);
                for (int i = 0; i < m.trailCount; i++) {
                    Vec3 at = new Vec3(m.trail[i * 3], m.trail[i * 3 + 1], m.trail[i * 3 + 2]);
                    float f0 = i / (float) m.trailCount;
                    float fade = a * (1 - f0) * VfxParts.fade(view.cam(), at, .3, .9);
                    VfxParts.streak(pose, buf, view, at, prev, m.size * 1.3f * (1 - f0 * .7f), .5f, 0, .5f, 1, VfxParts.argb(fade, rgb));
                    prev = at;
                }
            }
        });
        to.submitCustomGeometry(stack, SPARK, (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != Mote.Kind.WISP) continue;
                float k = m.k(pt);
                Vec3 p = m.at(pt);
                float a = (1 - VfxParts.smooth(.55f, 1, k)) * VfxParts.smooth(0, .1f, k) * VfxParts.fade(view.cam(), p, .3, .9);
                VfxParts.sprite(pose, buf, view, p, m.size * 1.2f, 0, VfxParts.argb(a, VfxParts.hue(MANA_CORE, m.hue)));
                VfxParts.sprite(pose, buf, view, p, m.size * 2.6f, 0, VfxParts.argb(a * .35f, VfxParts.hue(MANA, m.hue)));
            }
        });
        to.submitCustomGeometry(stack, RUNES, (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != Mote.Kind.RUNE) continue;
                float k = m.k(pt);
                Vec3 p = m.at(pt);
                float a = VfxParts.smooth(0, .2f, k) * (1 - VfxParts.smooth(.55f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
                int g = m.glyph;
                VfxParts.sprite(pose, buf, view, p, m.size, m.size, m.roll * .2f, g % 6 / 6f, g / 6 / 4f, (g % 6 + 1) / 6f, (g / 6 + 1) / 4f,
                        VfxParts.argb(a, VfxParts.lerpColor(VfxParts.hue(MANA, m.hue), 0xFFFFFF, .45f)));
            }
        });
    }

    /**
     * Flames: a TONGUE is a small flame licking up from the outline (the flipbook stood upright, hilt end down), a
     * FLAME a turning ball of flame scattered in a swing (the original's FireVelocity: a quad at a random angle,
     * growing through its life, alpha 0 → 1 → 1 → 0.14 → 0).
     */
    private static void flame(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Mote m, float pt, int rgb, float alpha) {
        if (m.kind != Mote.Kind.TONGUE && m.kind != Mote.Kind.FLAME) return;
        float k = m.k(pt);
        Vec3 p = m.at(pt);
        float near = VfxParts.fade(view.cam(), p, .3, .9);
        if (m.kind == Mote.Kind.TONGUE) {
            int f = (m.frame0 + (int) ((m.age + pt) * 3)) % FRAMES;
            float hgt = m.size * (.6f + .4f * Mth.sqrt(k)), a = VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.5f, 1, k)) * near;
            VfxParts.streak(pose, buf, view, p.subtract(0, hgt * .3, 0), p.add(0, hgt * .7, 0), hgt / 2.6f,
                    (f % COLS) / (float) COLS, (f / COLS) / 16f, (f % COLS + 1) / (float) COLS, (f / COLS + 1) / 16f, VfxParts.argb(a * alpha, rgb));
        } else {
            int f = (m.frame0 + (int) (k * (FRAMES - 1))) % FRAMES;
            float grow = k < .216f ? k / .216f * .644f : .644f + (k - .216f) / .784f * .356f;
            float a = k < .18f ? k / .18f : k < .32f ? 1 : k < .53f ? 1 - (k - .32f) / .21f * .86f : .14f * (1 - (k - .53f) / .47f);
            float r = m.size * grow / 2;
            VfxParts.sprite(pose, buf, view, p, r, r, m.roll, (f % COLS) / (float) COLS, (f / COLS) / 16f, (f % COLS + 1) / (float) COLS,
                    (f / COLS + 1) / 16f, VfxParts.argb(a * near * alpha, rgb));
        }
    }
}
