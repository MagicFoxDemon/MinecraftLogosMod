package dev.logos.client;

import static dev.logos.client.ChargeAuraEvokeParts.curve;
import static dev.logos.client.ChargeAuraEvokeParts.param;
import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeAuraEvokeParts.Accent;
import dev.logos.client.ChargeAuraEvokeParts.Mesh;
import dev.logos.client.ChargeAuraEvokeParts.Ribbon;
import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The colossal aura physical, frost, lightning and void draw in place of the shared pillar (CHARGE_VFX.md), after
 * the Niagara system NS_Charge_EvokeEnergy_Root (broken down in {@code art/charge/gather/evoke/notes.md}), in the element's
 * colours and with its accent. Its emitters, with the reference's spawn rates, lifetimes, speeds and materials:
 * <ul>
 * <li>Wind: a broad ribbon of the energy-wind flipbook thrown up from the feet, the flame wrapping the caster;</li>
 * <li>two sine-line strips (SinCosStrip): pairs of thin lines weaving up through jagged ribbons, the arcs;</li>
 * <li>a soft flame ribbon (SimpleMaskRibbon) and a ghost-hair ribbon (three wavy strands of the flow texture);</li>
 * <li>two spiral meshes standing round the caster, turning, their noise flowing (they appear once, early);</li>
 * <li>thin rays flickering in a cone-shaped volume widening upwards (Rock, on the cone mesh);</li>
 * <li>bright sparks rising in a curling stream (noise_burst), dark smoke rising behind the caster (Black),
 * a glow at the feet (Root) and the fist circle flashing on the ground (Decal);</li>
 * <li>let go: a ring flash and thirty radial streaks round the caster (FinalBoost).</li>
 * </ul>
 * Particles are stepped in {@link #tick} and capped; everything follows {@link ChargeBody#aura} for how strongly
 * it shows, its size the caster's height and its place the feet. In first person the layers that wrap the caster
 * are left out; the glow and circle on the ground stay, and sparks fade close to the camera.
 */
final class ChargeAuraEvoke {
    /**
     * The element's aura: colours ({@code light} the white-hot lines and sparks, {@code main} the flame,
     * {@code deep} where the reference's hue shifts on towards the tips), its accent, and the material of the
     * smoke rising behind ({@code opaqueSmoke}: a solid one that erodes by its vertex alpha, otherwise darkening);
     * {@code body} scales how much what lies over the body shows (the two broad flames, the wind and the soft flame, and
     * the glow and circle at the feet), for a palette so pale that at full strength they would bury the caster in
     * daylight.
     */
    record Look(int light, int main, int deep, Accent accent, RenderType smoke, boolean opaqueSmoke, float body) {
        Look(int light, int main, int deep, Accent accent, RenderType smoke, boolean opaqueSmoke) {
            this(light, main, deep, accent, smoke, opaqueSmoke, 1);
        }
    }

    /**
     * How much bigger than the reference (at its system size of 0.9, for a caster 1.8 tall) the whole aura is drawn:
     * every position, size, width, height, radius and distance thrown, all through the one scale, so the shape is
     * the reference's. Particles are stepped in units of the reference ({@link #tick}) and scaled only when drawn.
     * Shared with {@link VoidChargeAura}.
     */
    static final float AURA_SCALE = 1.5f;
    /** One Niagara centimetre in blocks, with the reference's system size of 0.9, for a caster 1.8 tall. */
    static final float CM = .009f;
    /** Ticks after the charge starts that the two spirals appear (their bursts at 0.3 and 0.5 seconds). */
    private static final int SPIRAL_IN = 6, SPIRAL_LATE_IN = 10;
    private static final int BOOST = 14;
    /**
     * The flame ribbons' cross-sections lie level: metres wide on points a few centimetres apart, square to their own
     * bends they fold over at every turn into hard-edged sheets.
     */
    private static final Vec3 UP = new Vec3(0, 1, 0);

    /** A point of a ribbon: thrown from the feet along {@code dir} at {@code speed} blocks a tick (for a caster 1.8 tall). */
    private record Point(double born, float life, Vec3 dir, float speed) {
        /** Where it is {@code age} ticks on, before scaling: Niagara's linear drag of 0.25 a second. */
        double distance(double age) {
            return speed * (1 - Math.exp(-.0125 * age)) / .0125;
        }
    }

    /** A sprite particle, simulated in caster-relative units (scaled by the caster's height when drawn). */
    private static final class Mote {
        final double born;
        final float life, size, rnd;
        Vec3 pos, vel;

        Mote(double born, float life, Vec3 pos, Vec3 vel, float size, float rnd) {
            this.born = born;
            this.life = life;
            this.pos = pos;
            this.vel = vel;
            this.size = size;
            this.rnd = rnd;
        }

        float k(double now) { return (float) ((now - born) / life); }
    }

    /** One ribbon emitter of the reference. */
    private static final class Stream {
        final Ribbon kind;
        final float rate, life, speedLo, speedHi, width, cameraOffset;
        final int variant;
        /** Where this ribbon's slowly wandering direction starts (see {@link #spawn}). */
        final float phase;
        final ArrayDeque<Point> points = new ArrayDeque<>();
        float owed;

        Stream(Ribbon kind, float rate, float life, float speedLo, float speedHi, float width, float cameraOffset, int variant) {
            this.kind = kind;
            this.rate = rate;
            this.life = life;
            this.speedLo = speedLo;
            this.speedHi = speedHi;
            this.width = width;
            this.cameraOffset = cameraOffset;
            this.variant = variant;
            this.phase = kind.ordinal() * 2.3f + variant * 5.1f;
        }
    }

    private final Look look;
    // Wind (60/s, 0.35 s, 500-850 x 1.4 cm/s, 3 m wide, offset 80 cm towards the camera), the two strips
    // (30/s and 60/s, 0.4 s, 1.6 m and 2.7 m wide), the soft flame (60/s, 0.35 s, 1.5 m) and the ghost hair (60/s, 0.4 s, 2 m).
    private final Stream wind = new Stream(Ribbon.FLAME, 3, 7, 500 * 1.4f, 850 * 1.4f, 340, 80, 0);
    private final Stream stripA = new Stream(Ribbon.STRIP, 1.5f, 8, 500, 850, 180, 0, 0);
    private final Stream stripB = new Stream(Ribbon.STRIP, 3, 8, 500, 850, 300, 0, 1);
    private final Stream glow = new Stream(Ribbon.GLOW, 3, 7, 500, 850, 166, 0, 0);
    private final Stream hair = new Stream(Ribbon.HAIR, 3, 8, 500, 850, 222, 0, 0);
    private final List<Stream> streams = List.of(wind, glow, hair, stripA, stripB);
    private final List<Mote> sparks = new ArrayList<>(), smoke = new ArrayList<>(), roots = new ArrayList<>(), decals = new ArrayList<>(),
            rays = new ArrayList<>(), boost = new ArrayList<>();
    private float owedSparks, owedSmoke, owedRoots, owedDecals, owedRays;
    private double boostAt = -1e9;

    ChargeAuraEvoke(Look look) {
        this.look = look;
    }

    /** One client tick of a colossal charge. False once everything it threw has played out. */
    boolean tick(RandomSource r, LiveCharge c) {
        double now = c.age(0);
        for (Stream s : streams) s.points.removeIf(p -> now - p.born > p.life);
        for (List<Mote> l : List.of(sparks, smoke, roots, decals, rays, boost)) l.removeIf(m -> now - m.born > m.life);
        // noise_burst has a drag of 1 a second and curl noise; Black neither.
        step(sparks, now, 1 / 20f, .03f);
        step(smoke, now, 0, 0);
        LiveCharge.Phase phase = c.phase();
        if (!phase.over()) {
            float seconds = (float) now / 20;
            for (Stream s : streams) spawn(r, s, now, seconds);
            for (owedSparks += 7.5f; owedSparks >= 1; owedSparks--) if (sparks.size() < 90) spark(r, now);
            for (owedSmoke += 1.5f; owedSmoke >= 1; owedSmoke--) if (smoke.size() < 60) smoke(r, now);
            for (owedRoots += 1.5f; owedRoots >= 1; owedRoots--) if (roots.size() < 12) roots.add(new Mote(now + r.nextFloat(),
                    4 + r.nextInt(5), Vec3.ZERO, Vec3.ZERO, 3 + 2 * r.nextFloat(), r.nextFloat()));
            for (owedDecals += .25f; owedDecals >= 1; owedDecals--) decals.add(new Mote(now + r.nextFloat(), 6 + r.nextInt(3), Vec3.ZERO,
                    Vec3.ZERO, 1 + r.nextFloat(), r.nextFloat()));
            for (owedRays += .25f; owedRays >= 1; owedRays--) if (rays.size() < 8) ray(r, now);
        }
        if (phase == LiveCharge.Phase.RELEASED && c.entered()) burst(r, now);
        boolean any = now - boostAt < BOOST;
        for (Stream s : streams) any |= !s.points.isEmpty();
        return any || !sparks.isEmpty() || !smoke.isEmpty() || !roots.isEmpty() || !decals.isEmpty() || !rays.isEmpty();
    }

    /** Drops whatever is still in flight (the charge is gone). */
    void end() {
        for (Stream s : streams) s.points.clear();
        for (List<Mote> l : List.of(sparks, smoke, roots, decals, rays, boost)) l.clear();
        boostAt = -1e9;
    }

    // ---- emitters ----------------------------------------------------------------------------------

    private static void spawn(RandomSource r, Stream s, double now, float seconds) {
        s.owed += s.rate;
        int n = (int) s.owed;
        s.owed -= n;
        for (int i = 0; i < n && s.points.size() < 32; i++) {
            // Spread through the tick, in order, so the ribbon runs from the newest to the oldest.
            double born = now + (i + .5) / n;
            // AddVelocity in a 32 degree cone, half the speed at its edge. Drawn independently for every point, the
            // ribbon folds back and forth on itself into hard-edged fans; here the direction wanders smoothly
            // through the cone with the time each point leaves (two slow sines each), a little jitter on top, so
            // every ribbon is a flame tongue swaying round the caster.
            double t = born / 20 + s.phase;
            double edge = Mth.clamp(.5 + .32 * Math.sin(t * 2.3) + .18 * Math.sin(t * 5.7 + 1.1) + (r.nextDouble() - .5) * .12, 0, 1);
            double around = t * 1.9 + 1.4 * Math.sin(t * 1.3 + s.phase) + (r.nextDouble() - .5) * .35;
            double angle = Math.toRadians(32) * Math.sqrt(edge);
            Vec3 dir = new Vec3(Math.sin(angle) * Math.cos(around), Math.cos(angle), Math.sin(angle) * Math.sin(around));
            float cmPerSecond = Mth.lerp(r.nextFloat(), s.speedLo, s.speedHi) * (1 - .5f * (float) Math.sqrt(edge));
            // The first strip grows over the first second from half height, the soft flame from nothing.
            cmPerSecond *= switch (s.kind) {
                case STRIP -> s.variant == 0 ? curve(seconds, 0, .5f, 1, 1) : 1;
                case GLOW -> curve(seconds, 0, 0, .2f, .62f, 1, 1) * (.9f + .2f * r.nextFloat());
                default -> 1;
            };
            s.points.add(new Point(born, s.life, dir, cmPerSecond * CM / 20));
        }
    }

    /** noise_burst: a sphere of 50 cm at 30 cm up, thrown up at 400-500 cm/s in a 30 degree cone, 0.5-0.6 s. */
    private void spark(RandomSource r, double now) {
        Vec3 at = ChargeParts.randomUnit(r).scale(50 * CM * Math.cbrt(r.nextDouble())).add(0, 30 * CM, 0);
        double angle = Math.toRadians(30) * Math.sqrt(r.nextDouble()), around = r.nextDouble() * Mth.TWO_PI;
        Vec3 dir = new Vec3(Math.sin(angle) * Math.cos(around), Math.cos(angle), Math.sin(angle) * Math.sin(around));
        float speed = (400 + 100 * r.nextFloat()) * CM / 20;
        sparks.add(new Mote(now + r.nextFloat(), 10 + r.nextInt(3), at, dir.scale(speed), .5f + 1.5f * r.nextFloat(), r.nextFloat()));
    }

    /** Black: rising from the feet at 200-220 cm/s for 1.5-1.8 s. */
    private void smoke(RandomSource r, double now) {
        smoke.add(new Mote(now + r.nextFloat(), 30 + r.nextInt(7), Vec3.ZERO, new Vec3(0, (200 + 20 * r.nextFloat()) * CM / 20, 0),
                1 + 1.5f * r.nextFloat(), r.nextFloat()));
    }

    /** Rock: in a cone-shaped volume (the lathe profile, 0.2 to 3 times 72 cm) widening up to 5.85 m, for 0.6-1 s. */
    private void ray(RandomSource r, double now) {
        float h = r.nextFloat();
        double around = r.nextDouble() * Mth.TWO_PI;
        float radius = 72 * CM / .9f * (.2f + 2.8f * h) * (.6f + .4f * r.nextFloat());
        Vec3 at = new Vec3(Math.cos(around) * radius, h * 585 * CM * .5f, Math.sin(around) * radius);
        rays.add(new Mote(now + r.nextFloat(), 12 + r.nextInt(9), at, Vec3.ZERO, .5f + .5f * r.nextFloat(), r.nextFloat()));
    }

    /** FinalBoost: let go, thirty streaks in a sphere of 4.5 m round the caster pointing at its middle. */
    private void burst(RandomSource r, double now) {
        boostAt = now;
        for (int i = 0; i < 30; i++) {
            Vec3 at = ChargeParts.randomUnit(r).scale(500 * CM * Math.cbrt(.15 + .85 * r.nextDouble()));
            boost.add(new Mote(now + r.nextFloat() * 2, 6 + r.nextInt(6), at, Vec3.ZERO, .5f + .5f * r.nextFloat(), r.nextFloat()));
        }
    }

    /** Moves motes along: their drag, and for the sparks the curl noise, here a slowly turning field of sines. */
    private static void step(List<Mote> motes, double now, float dragPerTick, float curl) {
        for (Mote m : motes) {
            if (now < m.born) continue;
            if (curl > 0) {
                Vec3 p = m.pos.scale(3.5);
                double t = now * .08 + m.rnd * 6;
                m.vel = m.vel.add(Math.sin(p.y + t) * curl, Math.sin(p.z * 1.3 + t * 1.1) * curl * .5, Math.sin(p.x * 1.2 - t) * curl);
            }
            m.vel = m.vel.scale(1 - dragPerTick);
            m.pos = m.pos.add(m.vel);
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        double now = c.age(pt);
        Vec3 feet = c.feet(pt);
        float scale = c.height() / 1.8f * AURA_SCALE;
        boolean fp = c.firstPerson();
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        if (aura != null) {
            float s = aura.strength(), widen = aura.widen();
            if (!fp) {
                smoke(stack, out, view, feet, now, scale, s);
                rays(stack, out, feet, now, scale, s, widen);
                spirals(stack, out, c, feet, now, scale, s, widen);
                for (Stream st : streams) stream(st, stack, out, view, feet, now, scale, s, widen);
            }
            ground(stack, out, view, feet, now, scale, s, fp);
            sparks(stack, out, view, feet, now, scale, s, widen);
        }
        if (now - boostAt < BOOST) boost(stack, out, view, c, feet, now, scale);
    }

    private void stream(Stream st, PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float s, float widen) {
        int n = st.points.size();
        if (n < 2) return;
        Vec3[] pts = new Vec3[n];
        float[] half = new float[n], u = new float[n];
        int[] colors = new int[n], a = new int[n], b = new int[n];
        int count = 0;
        // Newest first: U runs from the feet (0) to the far end (1), as the flipbook's root sits at its left.
        for (Iterator<Point> it = st.points.descendingIterator(); it.hasNext();) {
            Point p = it.next();
            double age = now - p.born;
            if (age < 0) continue;
            float k = Mth.clamp((float) (age / p.life), 0, 1);
            double d = p.distance(age) * scale;
            Vec3 at = feet.add(p.dir.x * d * widen, p.dir.y * d, p.dir.z * d * widen);
            if (st.cameraOffset != 0) at = ChargeAuraEvokeParts.toCamera(view, at, st.cameraOffset * CM * scale);
            pts[count] = at;
            float w = switch (st.kind) {
                case FLAME -> curve(k, 0, .6f, .1f, .78f, .2f, 1);
                default -> 1 - k;
            };
            half[count] = st.width * CM * scale * w * .5f;
            colors[count] = color(st, k, s);
            if (st.kind == Ribbon.STRIP) {
                // Thinner as it ages: band power 60 to 250 (or 280).
                a[count] = param(Mth.lerp(k, 60, st.variant == 0 ? 250 : 280) / 100);
                b[count] = param(st.variant);
            } else {
                a[count] = 0;
                b[count] = param(st.variant * .37f + (st.kind == Ribbon.HAIR ? .5f : 0));
            }
            count++;
        }
        if (count < 2) return;
        for (int i = 0; i < count; i++) u[i] = i / (float) (count - 1);
        int m = count;
        out.submitCustomGeometry(stack, ChargeAuraEvokeParts.ribbon(st.kind, look.accent()), (pose, buf) ->
                ChargeAuraEvokeParts.ribbon(pose, buf, view, pts, half, colors, u, a, b, m, UP));
    }

    /** A ribbon point's colour: the reference's brightness and hue shift for each emitter, its alpha curve over its life. */
    private int color(Stream st, float k, float s) {
        int light = look.light(), main = look.main(), deep = look.deep();
        return switch (st.kind) {
            // Wind: 0.2 alpha at strength 2, in after 5 %, hue + 0.05.
            case FLAME -> argb(s * .4f * look.body() * smooth(0, .05f, k), lerpColor(main, deep, .2f + .4f * k));
            // The soft flame: 0.75 alpha, fading over its life (its brightness 1: the plain colour, a little deep).
            case GLOW -> argb(s * .5f * look.body() * (1 - k), lerpColor(main, deep, .25f + .45f * k));
            // The ghost hair, 15 times as bright, hue + 0.2.
            case HAIR -> argb(s * .9f * curve(k, 0, 0, .05f, 1, 1, 0), lerpColor(lerpColor(main, light, .35f), deep, .4f * k));
            // The strips, 13 and 15 times as bright, hue + 0.1 and + 0.2.
            case STRIP -> st.variant == 0
                    ? argb(s * (1 - k), lerpColor(lerpColor(main, light, .45f), deep, .3f * k))
                    : argb(s * .9f * curve(k, 0, 1, .05f, .975f, 1, 0), lerpColor(lerpColor(main, light, .3f), deep, .5f * k));
        };
    }

    /** NS_Projectile003 and 007: the two spiral meshes standing round the caster, turning 2.5 times a second. */
    private void spirals(PoseStack stack, SubmitNodeCollector out, LiveCharge c, Vec3 feet, double now, float scale, float s, float widen) {
        RenderType type = ChargeAuraEvokeParts.mesh(Mesh.SPIRAL, look.accent());
        float turn = (float) (now / 20 * 2.5 * Mth.TWO_PI);
        // 003: SM_SpiralProjectile1, (2.8, 6, 6) x 2.1 x size x (tall, 1.5, 1.5), its axis pointing down from 1.44 m.
        float in = smooth(SPIRAL_IN, SPIRAL_IN + 2, (float) now);
        if (in > 0) spiral(stack, out, type, "charge_aura/spiral_projectile1", feet, 160 * CM * scale, 2.8f * 2.1f * .9f * scale,
                6 * 2.1f * .9f * 1.5f * scale * widen, turn, in * s, lerpColor(look.main(), look.light(), .3f));
        // 007: SM_SpiralProjectile, (2, 6, 6) x 2.1 x size, growing over half a second, from the feet up.
        float late = smooth(SPIRAL_LATE_IN, SPIRAL_LATE_IN + 2, (float) now);
        float grow = smooth(SPIRAL_LATE_IN, SPIRAL_LATE_IN + 10, (float) now);
        if (late > 0) spiral(stack, out, type, "charge_aura/spiral_projectile", feet, 0, 2 * 2.1f * .9f * scale * grow,
                6 * 2.1f * .9f * scale * widen, -turn * .8f + 1.3f, late * s * .8f, look.main());
    }

    /**
     * A spiral mesh (blocks along its X axis) stood up with X pointing down from {@code up} blocks over the feet, as
     * the reference's initial orientation does, scaled {@code sx} along and {@code sr} round, turned about its axis.
     */
    private static void spiral(PoseStack stack, SubmitNodeCollector out, RenderType type, String mesh, Vec3 feet, float up, float sx,
            float sr, float turn, float alpha, int rgb) {
        if (sx <= 0) return;
        Matrix4f transform = new Matrix4f().translate(0, up, 0).rotateY(turn).rotateZ(-Mth.HALF_PI).scale(sx, sr, sr);
        out.submitCustomGeometry(stack, type, (pose, buf) -> ChargeAuraMesh.get(mesh).draw(pose, buf, feet, transform, true, 0, 0, 1, 1,
                (x, y, z, u, v) -> {
                    // The reference's floor hid what reaches below the feet: faded out there.
                    float h = up - x * sx;
                    return argb(alpha * smooth(-.05f, .3f, h), rgb);
                }));
    }

    /** Rock: thin cone-mesh rays flickering in the volume round the caster, stretching up as they shrink in girth. */
    private void rays(PoseStack stack, SubmitNodeCollector out, Vec3 feet, double now, float scale, float s, float widen) {
        if (rays.isEmpty()) return;
        RenderType type = ChargeAuraEvokeParts.mesh(Mesh.RAY, look.accent());
        ChargeAuraMesh cone = ChargeAuraMesh.get("charge_aura/cyc_taper");
        for (Mote m : rays) {
            float k = m.k(now);
            if (k <= 0 || k >= 1) continue;
            float xy = curve(k, 0, .9f, 1, 0) * .1f * m.size, z = curve(k, 0, .001f, .4f, .75f, .5f, 1.02f, 1, 1.02f) * 3.2f * m.size;
            float flicker = .55f + .45f * Mth.sin((float) now * 9.1f + m.rnd * 40);
            float alpha = s * curve(k, 0, 1, .1f, 1, .5f, 1, 1, 0) * flicker * .7f;
            Vec3 at = feet.add(m.pos.x * scale * widen, m.pos.y * scale, m.pos.z * scale * widen);
            Matrix4f transform = new Matrix4f().scale(xy * scale, z * scale, xy * scale);
            int rgb = lerpColor(look.main(), look.light(), .4f * m.rnd);
            out.submitCustomGeometry(stack, type, (pose, buf) -> cone.draw(pose, buf, at, transform, false, 0, 0, 1, 1,
                    (x, y, zz, u, v) -> argb(alpha, rgb)));
        }
    }

    /** noise_burst: bright velocity-stretched sparks. Faded close to the camera. */
    private void sparks(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float s, float widen) {
        if (sparks.isEmpty()) return;
        int light = lerpColor(look.light(), look.main(), .4f);
        out.submitCustomGeometry(stack, ChargeAuraEvokeParts.sprite("charge_aura/basic_dot", true), (pose, buf) -> {
            for (Mote m : sparks) {
                float k = m.k(now);
                if (k <= 0 || k >= 1) continue;
                Vec3 at = feet.add(m.pos.x * scale * widen, m.pos.y * scale, m.pos.z * scale * widen);
                float a = s * curve(k, 0, 0, .14f, 1, 1, 0) * ChargeParts.fade(view.cam(), at, .6, 1.6);
                if (a <= .01f) continue;
                // 3 x size x (1 to 0 after half its life) cm, stretched by speed: 0.3-0.8 across, 1.5-5 along.
                float size = m.size * 3 * curve(k, 0, 1, .5f, 1, 1, 0) * CM * scale * 1.8f;
                float speed = (float) Mth.clamp(m.vel.length() * 20 / CM / 1000, 0, 1);
                ChargeAuraEvokeParts.aligned(pose, buf, view, at, m.vel, size * Mth.lerp(speed, .3f, .8f), size * Mth.lerp(speed, 1.5f, 5),
                        .5f, argb(a, lerpColor(light, look.main(), k)), 0, 0);
            }
        });
    }

    /** Black: dark smoke rising behind the caster (60 cm further from the camera), darkening or solid by the element's look. */
    private void smoke(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float s) {
        if (smoke.isEmpty()) return;
        float rise = Mth.clamp((float) now / 20, 0, 1);
        out.submitCustomGeometry(stack, look.smoke(), (pose, buf) -> {
            for (Mote m : smoke) {
                float k = m.k(now);
                if (k <= 0 || k >= 1) continue;
                Vec3 at = ChargeAuraEvokeParts.toCamera(view, feet.add(m.pos.scale(scale)), -60 * CM * scale);
                // MySize x (1-2.5) x 60 cm x (2, 2, 1, 1, 0), stretched up by speed.
                // Solid smoke is heavier than darkening: a quarter smaller.
                float size = m.size * 60 * CM * scale * curve(k, 0, 2, .1f, 2, .2f, 1, .7f, 1, 1, 0) * .5f * (look.opaqueSmoke() ? .75f : 1);
                if (size <= .01f) continue;
                float w = size * Mth.lerp(k, 1.1f, .6f), l = size * 1.6f;
                int color = look.opaqueSmoke()
                        // Solid, eroding away from the edges as it rises and as the aura fades.
                        ? argb(Mth.clamp(s, 0, 1) * curve(k, 0, .75f, .6f, .65f, 1, 0) * rise, look.main())
                        : argb(s * .13f * (1 - k) * rise, 0);
                ChargeAuraEvokeParts.aligned(pose, buf, view, at, new Vec3(0, 1, 0), w, l, .5f, color, param(m.rnd * 7), 0);
            }
        });
    }

    /** Root: a glow at the feet (80 cm towards the camera); Decal: the fist circle flashing on the ground. */
    private void ground(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float s, boolean fp) {
        if (!roots.isEmpty()) out.submitCustomGeometry(stack, ChargeAuraEvokeParts.sprite("charge_aura/circle_dot", false), (pose, buf) -> {
            for (Mote m : roots) {
                float k = m.k(now);
                if (k <= 0 || k >= 1) continue;
                // Not pulled up towards a first-person camera, which looks down on its own feet.
                Vec3 at = feet.add(0, .1 * scale, 0);
                if (!fp) at = ChargeAuraEvokeParts.toCamera(view, at, 80 * CM * scale);
                float a = s * look.body() * curve(k, 0, 0, .5f, 1, 1, 0) * ChargeParts.fade(view.cam(), at, 1, 2);
                float r = 7 * m.size * CM * scale * curve(k, 0, 0, .5f, 1, 1, 0) * 2.2f;
                if (a > .01f) ChargeAuraEvokeParts.sprite(pose, buf, view, at, r, r, m.rnd * 6.3f, argb(a, lerpColor(look.main(), look.light(), .5f)),
                        0, 0);
            }
        });
        if (!decals.isEmpty()) out.submitCustomGeometry(stack, ChargeAuraEvokeParts.sprite("charge_aura/circle_fist", false), (pose, buf) -> {
            for (Mote m : decals) {
                float k = m.k(now);
                if (k <= 0 || k >= 1) continue;
                // 226 cm across, opacity 0.315, value 1-2, at a random turn.
                float a = s * look.body() * .4f * m.size * .5f * curve(k, 0, 0, .15f, 1, .7f, 1, 1, 0);
                ChargeAuraEvokeParts.flat(pose, buf, feet.add(0, .04, 0), 226 * CM * scale * .5f, m.rnd * Mth.TWO_PI,
                        argb(a, lerpColor(look.main(), look.deep(), .25f)));
            }
        });
    }

    /** FinalBoost: let go, a ring flashing out round the caster and thirty streaks pointing in at it. */
    private void boost(PoseStack stack, SubmitNodeCollector out, View view, LiveCharge c, Vec3 feet, double now, float scale) {
        float k = (float) ((now - boostAt) / BOOST);
        if (k < 0 || k >= 1) return;
        Vec3 middle = feet.add(0, c.height() * .5, 0);
        float near = c.firstPerson() ? .35f : 1;
        float ringA = Mth.sin(k * Mth.PI) * .8f * near;
        float ring = Mth.lerp(1 - (1 - k) * (1 - k), .3f, 2.4f) * scale;
        out.submitCustomGeometry(stack, ChargeAuraEvokeParts.sprite("charge_aura/center_ring02", false), (pose, buf) ->
                ChargeAuraEvokeParts.sprite(pose, buf, view, middle, ring, ring, k * .6f, argb(ringA, lerpColor(look.main(), look.light(), .3f)), 0, 0));
        out.submitCustomGeometry(stack, ChargeAuraEvokeParts.sprite("charge_aura/basic_dot", false), (pose, buf) -> {
            for (Mote m : boost) {
                float j = m.k(now);
                if (j <= 0 || j >= 1) continue;
                Vec3 at = middle.add(m.pos.scale(scale));
                float a = Mth.sin(j * Mth.PI) * ChargeParts.fade(view.cam(), at, .8, 2) * near;
                float len = (.25f + .35f * m.size) * scale * smooth(0, .5f, j), w = .025f * scale * (1 + m.size);
                if (a > .01f) ChargeAuraEvokeParts.aligned(pose, buf, view, at, m.pos, w, len, .5f, argb(a, look.light()), 0, 0);
            }
        });
    }
}
