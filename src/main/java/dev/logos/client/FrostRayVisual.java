package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A frost {@code ray}: the release half of Piloto Studio {@code PS_UltimateBeam_Frost_01/_02}. In the first
 * third of a second a white-hot laser in an ice-blue glow, a starry band and a streaming water-noise sheath
 * fire down the whole line while wispy streaks race along it; a frost sigil and a restrained flare mark the
 * wand head. What it leaves is frost's own: a rime line that cracks apart, frost mist that swells and
 * slowly drifts away, and ice crystal debris that glints as it tumbles down. A hit (block or creature)
 * shatters into an ice burst at the end; a ray into the open thins out instead. Everything is custom
 * geometry submitted through {@code out}, so it draws the same under shader packs.
 */
final class FrostRayVisual implements RayVisual {
    /** Nothing is left after this many ticks (well inside {@code LogosFx}'s cap). */
    static final int LIFE = 130;
    private static final RenderType LASER = material("laser", "FROST_RAY_LASER", true),
            STARRY = material("starry", null, true), SHEATH = material("sheath", null, false),
            WISP = material("wisp", null, true), RIME = material("noise", "FROST_RAY_RIME", true),
            GLOW = material("glow", null, true), FLARE = material("flare", null, true),
            RUNE = material("rune", null, true), SNOW = material("snowflake", null, true),
            GLINTS = material("glints", null, true), MIST = material("mist", null, false),
            CRYSTAL = material("noise", "FROST_RAY_SOLID", false);

    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO, axis = new Vec3(0, 0, 1), right = new Vec3(1, 0, 0), up = new Vec3(0, 1, 0);
    private double length;
    private boolean hit, open;
    private long born;
    private final List<Puff> puffs = new ArrayList<>();
    private final List<Shard> shards = new ArrayList<>();
    private final List<Flake> flakes = new ArrayList<>();
    private final List<Streak> streaks = new ArrayList<>();

    /** A frost mist puff: where it starts, where it drifts, and when (in ticks of the ray's age) it comes and goes. */
    private record Puff(Vec3 at, Vec3 drift, float size, float roll, float spin, int frame, float appear, float fade, float gone) {}
    /** A tumbling ice crystal: thrown off at {@code velocity}, slowed by the air, then falling. */
    private record Shard(Vec3 at, Vec3 velocity, Vec3 spin, float rate, float size, float appear, float gone, boolean glints) {}
    /** A snowflake drifting down off the line. */
    private record Flake(Vec3 at, Vec3 drift, float size, float roll, float spin, float appear, float gone) {}
    /** A wispy streak racing down the line during the release. */
    private record Streak(double offset, double speed, double length, Vec3 lateral, float width, float delay) {}

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        this.to = to;
        this.born = level.getGameTime();
        Vec3 delta = to.subtract(from);
        length = delta.length();
        if (length > 1e-4) axis = delta.scale(1 / length);
        Vec3 r = axis.cross(new Vec3(0, 1, 0));
        right = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize();
        up = right.cross(axis).normalize();
        hit = end == Vfx.RayEnd.BLOCK || end == Vfx.RayEnd.CREATURE;
        open = end == Vfx.RayEnd.OPEN;
        Random random = new Random(level.getRandom().nextLong());
        scatter(random);
        // A creature's centre is inside it: the burst goes off on the side facing the caster.
        if (hit) shatter(random, end == Vfx.RayEnd.CREATURE ? .45 : .12);
    }

    @Override
    public boolean tick(Level level) {
        return level.getGameTime() - born < LIFE;
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (length < .05) return;
        float age = (float) (level.getGameTime() - born) + partialTick;
        if (age > LIFE) return;
        Vec3 eye = camera.pos;
        Vec3 camRight = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 camUp = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        // A first-person caster has the wand head right beside the camera: its flashes stay small, dim and ahead.
        boolean close = eye.distanceTo(from) < 1.6;

        // ---- the release: laser, starry band, sheath and streaks, all within the first ~16 ticks ----
        if (age < 8) {
            float w = age < 2.5f ? 1 : 1 - smooth((age - 2.5f) / 5.5f);
            float pop = Math.min(1, .55f + age * .9f);
            out.submitCustomGeometry(pose, LASER, (p, buf) -> {
                ribbon(p, buf, eye, s -> .7 * w * pop * taper(s), s -> argb(.95f * w, 0x5FA8FF), 0, 1);
                ribbon(p, buf, eye, s -> .24 * w * taper(s), s -> argb(w, 0xB4E2FF), 0, 1);
            });
            out.submitCustomGeometry(pose, STARRY, (p, buf) ->
                    ribbon(p, buf, eye, s -> .5 * w * taper(s), s -> argb(.8f * w, 0xB8E4FF), -age * .45f, 1 / 3.5f));
        }
        if (age < 16) {
            float k = age / 16;
            float a = k < .42f ? .55f : .55f * (1 - smooth((k - .42f) / .58f));
            out.submitCustomGeometry(pose, SHEATH, (p, buf) ->
                    ribbon(p, buf, eye, s -> (.2 + .8 * Math.sqrt(k)) * taper(s), s -> argb(a, mix(0xD2E8FF, 0x8FA9D2, k)), -age * .08f, 1 / 4f, 1.5, 5, 0));
        }
        // ---- the frost vapour: a band of cold haze left on the line, widening and thinning away ----
        if (age > 4 && age < 120) {
            float k = (age - 4) / 116;
            float a = .45f * smooth(Math.min(1, (age - 4) / 8)) * (1 - smooth(k));
            out.submitCustomGeometry(pose, SHEATH, (p, buf) ->
                    ribbon(p, buf, eye, s -> (.35 + .75 * Math.sqrt(k)) * taper(s), s -> argb(a, mix(0xB9D3F0, 0x98B4D6, k)), .37f + age * .012f, 1 / 6f, 2, 6, 0));
        }
        if (age < 12) out.submitCustomGeometry(pose, WISP, (p, buf) -> streaks(p, buf, eye, age));

        // ---- the rime line: what stays on the line once the laser is spent, cracking apart ----
        if (age > 3 && age < 90) {
            float left = age < 9 ? 1 : 1 - (age - 9) / 81;
            float settle = Math.min(1, (age - 3) / 4);
            out.submitCustomGeometry(pose, RIME, (p, buf) ->
                    ribbon(p, buf, eye, s -> .09 * taper(s), s -> argb(left, mix(0xFFFFFF, 0x9CD2FF, settle)), 0, 1 / 2.5f));
        }

        // ---- the wand head: a frost sigil around the line and a brief, restrained flare ----
        if (age < 10) {
            float k = age / 10;
            float fade = 1 - smooth(k);
            float scale = close ? .45f : 1;
            Vec3 at = from.add(axis.scale(close ? 1.1 : .3));
            out.submitCustomGeometry(pose, RUNE, (p, buf) ->
                    disc(p, buf, eye, at, (.35f + .35f * (float) Math.sqrt(k)) * scale, age * .06f, argb(.85f * fade * (close ? .5f : 1), 0x8CCBFF)));
        }
        if (age < 6) {
            float fade = 1 - smooth(age / 6);
            float scale = close ? .3f : 1;
            Vec3 at = close ? from.add(axis.scale(.9)) : from;
            out.submitCustomGeometry(pose, GLOW, (p, buf) ->
                    sprite(p, buf, eye, camRight, camUp, at, .75f * scale, 0, 0, 0, 1, 1, argb(.8f * fade * (close ? .45f : 1), 0x9CCBFF)));
            out.submitCustomGeometry(pose, FLARE, (p, buf) ->
                    sprite(p, buf, eye, camRight, camUp, at, 1.2f * scale, .3f, 0, 0, 1, 1, argb(fade * (close ? .4f : 1), 0xC4D8FF)));
        }

        // ---- at the end: an ice burst on a hit ----
        if (hit && age < 12) {
            float k = age / 12;
            float fade = 1 - smooth(k);
            Vec3 at = to.subtract(axis.scale(.15));
            out.submitCustomGeometry(pose, GLOW, (p, buf) ->
                    sprite(p, buf, eye, camRight, camUp, at, 1.5f * (.7f + .3f * k), 0, 0, 0, 1, 1, argb(.9f * fade, 0x8CCBFF)));
            out.submitCustomGeometry(pose, FLARE, (p, buf) ->
                    sprite(p, buf, eye, camRight, camUp, at, 2.1f * (1 - .4f * k), -.4f, 0, 0, 1, 1, argb(fade, 0xDCF4FF)));
            out.submitCustomGeometry(pose, RUNE, (p, buf) ->
                    disc(p, buf, eye, at, .6f + .8f * (float) Math.sqrt(k), -age * .05f, argb(.9f * fade, 0x8CCBFF)));
        }

        // ---- the lingering frost: mist, crystal debris and snow ----
        out.submitCustomGeometry(pose, MIST, (p, buf) -> {
            for (Puff puff : puffs) puff(p, buf, eye, camRight, camUp, puff, age);
        });
        out.submitCustomGeometry(pose, CRYSTAL, (p, buf) -> {
            for (Shard shard : shards) shard(p, buf, eye, shard, age);
        });
        out.submitCustomGeometry(pose, GLINTS, (p, buf) -> {
            for (Shard shard : shards) if (shard.glints) glint(p, buf, eye, camRight, camUp, shard, age);
        });
        out.submitCustomGeometry(pose, SNOW, (p, buf) -> {
            for (Flake flake : flakes) flake(p, buf, eye, camRight, camUp, flake, age);
        });
    }

    // ---- what the ray leaves, decided once when it arrives ------------------------------------------

    private void scatter(Random r) {
        int mist = clamp((int) (length * 2), 6, 64);
        for (int i = 0; i < mist; i++) {
            double s = (i + r.nextDouble()) / mist;
            Vec3 lateral = lateral(r, .12);
            // Cold mist spreads out from the line and sinks a little.
            Vec3 drift = lateral.normalize().scale(.012 + r.nextDouble() * .014).add(0, -.004 - r.nextDouble() * .004, 0)
                    .add(axis.scale((r.nextDouble() - .5) * .01));
            float appear = 2 + (float) s * 3;
            float gone = 85 + r.nextFloat() * 37;
            puffs.add(new Puff(along(s).add(lateral), drift, .7f + r.nextFloat() * .5f, r.nextFloat() * 6.283f,
                    (r.nextFloat() - .5f) * .03f, r.nextInt(4), appear, gone - 41, gone));
        }
        int crystals = clamp((int) (length * 2), 10, 64);
        for (int i = 0; i < crystals; i++) {
            double s = (i + r.nextDouble()) / crystals;
            Vec3 lateral = lateral(r, .1);
            Vec3 velocity = lateral.normalize().scale(.015 + r.nextDouble() * .04).add(0, .005 + r.nextDouble() * .02, 0);
            shards.add(new Shard(along(s).add(lateral), velocity, randomUnit(r), .08f + r.nextFloat() * .2f,
                    .045f + r.nextFloat() * .05f, 1 + (float) s * 2, 58 + r.nextFloat() * 61, r.nextFloat() < .45f));
        }
        int snow = clamp((int) (length * .6), 4, 24);
        for (int i = 0; i < snow; i++) {
            double s = (i + r.nextDouble()) / snow;
            Vec3 drift = lateral(r, 1).scale(.01).add(0, -.006 - r.nextDouble() * .006, 0);
            flakes.add(new Flake(along(s).add(lateral(r, .25)), drift, .07f + r.nextFloat() * .07f,
                    r.nextFloat() * 6.283f, (r.nextFloat() - .5f) * .08f, 3 + r.nextFloat() * 8, 75 + r.nextFloat() * 51));
        }
        int count = clamp((int) (6 + length / 3), 6, 20);
        for (int i = 0; i < count; i++)
            streaks.add(new Streak(-r.nextDouble() * 3, Math.max(5, length / 4) * (.8 + r.nextDouble() * .5),
                    1.5 + r.nextDouble() * 3, lateral(r, .3), .05f + r.nextFloat() * .08f, r.nextFloat() * 2.5f));
    }

    /** The ice burst at a hit: big crystals thrown back toward the caster, a cloud of mist and snow. */
    private void shatter(Random r, double back) {
        Vec3 at = to.subtract(axis.scale(back));
        for (int i = 0; i < 16; i++) {
            Vec3 dir = axis.scale(-.6 - r.nextDouble() * .6).add(randomUnit(r)).normalize();
            shards.add(new Shard(at.add(randomUnit(r).scale(.12)), dir.scale(.12 + r.nextDouble() * .16).add(0, .05, 0),
                    randomUnit(r), .15f + r.nextFloat() * .3f, .06f + r.nextFloat() * .07f, 0, 41 + r.nextFloat() * 34, r.nextFloat() < .5f));
        }
        for (int i = 0; i < 5; i++) {
            Vec3 drift = randomUnit(r).add(axis.scale(-1)).normalize().scale(.02 + r.nextDouble() * .015);
            puffs.add(new Puff(at.add(randomUnit(r).scale(.15)), drift, .45f + r.nextFloat() * .3f, r.nextFloat() * 6.283f,
                    (r.nextFloat() - .5f) * .04f, r.nextInt(4), 1, 37 + r.nextFloat() * 17, 78 + r.nextFloat() * 24));
        }
        for (int i = 0; i < 8; i++) {
            Vec3 drift = randomUnit(r).add(axis.scale(-.8)).normalize().scale(.03 + r.nextDouble() * .03).add(0, -.004, 0);
            flakes.add(new Flake(at, drift, .1f + r.nextFloat() * .08f, r.nextFloat() * 6.283f, (r.nextFloat() - .5f) * .12f,
                    0, 51 + r.nextFloat() * 34));
        }
    }

    /** A point on the line, {@code s} from 0 to 1; the wand head itself is left clear. */
    private Vec3 along(double s) {
        double start = Math.min(.9, length * .15);
        return from.add(axis.scale(start + (length - start) * s));
    }

    private Vec3 lateral(Random r, double radius) {
        double angle = r.nextDouble() * Math.PI * 2, d = radius * Math.sqrt(r.nextDouble()) + 1e-3;
        return right.scale(Math.cos(angle) * d).add(up.scale(Math.sin(angle) * d));
    }

    private static Vec3 randomUnit(Random r) {
        Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
        return v.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : v.normalize();
    }

    // ---- geometry --------------------------------------------------------------------------------

    private interface Width { double at(double s); }
    private interface Tint { int at(double s); }

    /** Thin at the wand head (right beside a first-person camera), full by a block and a half; an open ray thins out at its far end. */
    private double taper(double s) {
        double head = Math.min(1, s / 1.5);
        double tail = open ? Math.min(1, (length - s) / Math.min(4, length * .4)) : 1;
        return (.25 + .75 * head * head * (3 - 2 * head)) * Math.max(0, tail);
    }

    /** A camera-facing band along the line; {@code s} is the distance from {@code from}. */
    private void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Width width, Tint tint, float uOffset, float uScale) {
        ribbon(pose, buf, eye, width, tint, uOffset, uScale, .45, 2.2, .25f);
    }

    /** As above; within {@code near} of the camera the band keeps only {@code floor} of its alpha, so wide layers do not fog a first-person view. */
    private void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Width width, Tint tint, float uOffset, float uScale,
            double near, double far, float floor) {
        int segments = clamp((int) (length * 2), 8, 96);
        Vec3 prevA = null, prevB = null;
        int prevColor = 0;
        float prevU = 0;
        for (int i = 0; i <= segments; i++) {
            double s = length * i / segments;
            Vec3 c = from.add(axis.scale(s));
            Vec3 side = axis.cross(eye.subtract(c));
            side = side.lengthSqr() < 1e-8 ? right : side.normalize();
            Vec3 half = side.scale(width.at(s));
            int color = fadeNear(tint.at(s), c, eye, near, far, floor);
            float u = (float) s * uScale + uOffset;
            Vec3 a = c.add(half), b = c.subtract(half);
            if (prevA != null) {
                vertex(pose, buf, eye, prevA, prevU, 0, prevColor);
                vertex(pose, buf, eye, prevB, prevU, 1, prevColor);
                vertex(pose, buf, eye, b, u, 1, color);
                vertex(pose, buf, eye, a, u, 0, color);
            }
            prevA = a;
            prevB = b;
            prevColor = color;
            prevU = u;
        }
    }

    private void streaks(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, float age) {
        for (Streak st : streaks) {
            float t = age - st.delay;
            if (t < 0) continue;
            double head = st.offset + st.speed * t, tail = head - st.length;
            double a = Math.max(.8, tail), b = Math.min(length, head);
            if (b <= a) continue;
            float fade = 1 - smooth(t / 9);
            Vec3 pa = from.add(axis.scale(a)).add(st.lateral), pb = from.add(axis.scale(b)).add(st.lateral);
            Vec3 side = axis.cross(eye.subtract(pa.add(pb).scale(.5)));
            side = side.lengthSqr() < 1e-8 ? right : side.normalize();
            Vec3 half = side.scale(st.width);
            // U runs tail (0) to head (1) of the whole streak, so a clipped streak keeps its shape.
            float ua = (float) ((a - tail) / st.length), ub = (float) ((b - tail) / st.length);
            int ca = fadeNear(argb(.9f * fade, 0xD8F2FF), pa, eye, .8, 2.5, 0), cb = fadeNear(argb(.9f * fade, 0xD8F2FF), pb, eye, .8, 2.5, 0);
            vertex(pose, buf, eye, pa.add(half), ua, 0, ca);
            vertex(pose, buf, eye, pa.subtract(half), ua, 1, ca);
            vertex(pose, buf, eye, pb.subtract(half), ub, 1, cb);
            vertex(pose, buf, eye, pb.add(half), ub, 0, cb);
        }
    }

    private void puff(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Puff p, float age) {
        float t = age - p.appear;
        if (t < 0 || age > p.gone) return;
        float in = smooth(Math.min(1, t / 7));
        float out = age < p.fade ? 1 : 1 - smooth((age - p.fade) / (p.gone - p.fade));
        // Drifts out fast at first and settles: the integral of an exponentially slowing drift.
        double travel = 18 * (1 - Math.exp(-t / 30));
        Vec3 at = p.at.add(p.drift.scale(travel));
        float size = p.size * (.5f + .7f * (float) (1 - Math.exp(-t / 24)));
        int color = argb(.45f * in * out, mix(0xBCD5F0, 0x93AED0, Math.min(1, t / 68)));
        color = fadeNear(color, at, eye, 1.8, 4, 0);
        if (color >>> 24 == 0) return;
        int col = p.frame % 2, row = p.frame / 2;
        sprite(pose, buf, eye, camRight, camUp, at, size, p.roll + p.spin * t, col * .5f, row * .5f, col * .5f + .5f, row * .5f + .5f, color);
    }

    private static Vec3 shardAt(Shard s, float t) {
        // Air drag of 0.9 per tick, then a slow fall.
        double moved = (1 - Math.pow(.9, t)) / .1;
        return s.at.add(s.velocity.scale(moved)).add(0, -.0003 * t * t, 0);
    }

    private static float shrink(Shard s, float t) {
        float life = s.gone - s.appear;
        return t < life * .6f ? 1 : 1 - smooth((t - life * .6f) / (life * .4f));
    }

    /** A six-sided pointed crystal, tumbling in world space. */
    private void shard(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Shard s, float age) {
        float t = age - s.appear;
        if (t < 0 || age > s.gone) return;
        float shrink = shrink(s, t);
        double size = s.size * Math.min(1, t / 2) * shrink;
        if (size < .004) return;
        Vec3 centre = shardAt(s, t);
        float near = nearFactor(centre, eye, 1.6, 3.5);
        if (near <= 0) return;
        double angle = s.rate * t;
        Vec3 base = s.spin.cross(new Vec3(0, 1, 0));
        if (base.lengthSqr() < 1e-6) base = new Vec3(1, 0, 0);
        base = base.normalize();
        Vec3 other = s.spin.cross(base);
        Vec3 dir = base.scale(Math.cos(angle)).add(other.scale(Math.sin(angle))).normalize();
        Vec3 x = dir.cross(s.spin);
        if (x.lengthSqr() < 1e-6) x = base;
        x = x.normalize();
        Vec3 y = dir.cross(x).normalize();
        Vec3 tip = centre.add(dir.scale(size * 2.6)), tail = centre.subtract(dir.scale(size * 1.5));
        int alpha = (int) (215 * near * Math.min(1, shrink * 1.5));
        for (int face = 0; face < 6; face++) {
            double a = face * Math.PI / 3, b = (face + 1) * Math.PI / 3;
            Vec3 p = centre.add(x.scale(Math.cos(a) * size)).add(y.scale(Math.sin(a) * size));
            Vec3 q = centre.add(x.scale(Math.cos(b) * size)).add(y.scale(Math.sin(b) * size));
            int rgb = face % 3 == 0 ? 0xF0FCFF : face % 3 == 1 ? 0x82B9E2 : 0xB8E8FF;
            triangle(pose, buf, eye, tip, p, q, alpha << 24 | rgb);
            triangle(pose, buf, eye, tail, q, p, alpha << 24 | rgb);
        }
    }

    /** Some crystals catch the light: a twinkle that comes and goes on its own rhythm. */
    private void glint(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Shard s, float age) {
        float t = age - s.appear;
        if (t < 0 || age > s.gone) return;
        float twinkle = (float) Math.max(0, Math.sin(t * (.35 + s.rate) + s.size * 400));
        twinkle *= twinkle * twinkle;
        Vec3 at = shardAt(s, t);
        int color = fadeNear(argb(twinkle * shrink(s, t), 0xE8F8FF), at, eye, 1.6, 3.5, 0);
        if (color >>> 24 == 0) return;
        int frame = (int) (s.size * 1000) % 9;
        float u = frame % 3 / 3f, v = frame / 3 / 3f;
        sprite(pose, buf, eye, camRight, camUp, at, s.size * 9, 0, u, v, u + 1 / 3f, v + 1 / 3f, color);
    }

    private void flake(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Flake f, float age) {
        float t = age - f.appear;
        if (t < 0 || age > f.gone) return;
        float in = Math.min(1, t / 5), out = 1 - smooth(Math.max(0, (age - f.gone + 30) / 30));
        Vec3 at = f.at.add(f.drift.scale(t)).add(right.scale(Math.sin(t * .15 + f.roll) * .08));
        int color = fadeNear(argb(.85f * in * out, 0xE6F6FF), at, eye, 1.8, 4, 0);
        if (color >>> 24 == 0) return;
        sprite(pose, buf, eye, camRight, camUp, at, f.size, f.roll + f.spin * t, 0, 0, 1, 1, color);
    }

    /** A square facing the camera, rolled by {@code roll}, showing the UV rectangle given. */
    private static void sprite(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Vec3 at, float size,
            float roll, float u0, float v0, float u1, float v1, int color) {
        double c = Math.cos(roll) * size, s = Math.sin(roll) * size;
        Vec3 x = camRight.scale(c).add(camUp.scale(s)), y = camUp.scale(c).subtract(camRight.scale(s));
        vertex(pose, buf, eye, at.subtract(x).add(y), u0, v0, color);
        vertex(pose, buf, eye, at.subtract(x).subtract(y), u0, v1, color);
        vertex(pose, buf, eye, at.add(x).subtract(y), u1, v1, color);
        vertex(pose, buf, eye, at.add(x).add(y), u1, v0, color);
    }

    /** The frost sigil, lying across the line (facing along it) and slowly turning. */
    private void disc(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 at, float size, float roll, int color) {
        Vec3 x = right.scale(Math.cos(roll) * size).add(up.scale(Math.sin(roll) * size));
        Vec3 y = up.scale(Math.cos(roll) * size).subtract(right.scale(Math.sin(roll) * size));
        // Seen edge-on it would be a bright bar: it dims as it turns away from the camera.
        Vec3 view = eye.subtract(at);
        double facing = view.lengthSqr() < 1e-6 ? 1 : Math.abs(view.normalize().dot(axis));
        int c = fadeNear(scaleAlpha(color, (float) (.25 + .75 * facing)), at, eye, .5, 1.5, 0);
        vertex(pose, buf, eye, at.subtract(x).add(y), 0, 0, c);
        vertex(pose, buf, eye, at.subtract(x).subtract(y), 0, 1, c);
        vertex(pose, buf, eye, at.add(x).subtract(y), 1, 1, c);
        vertex(pose, buf, eye, at.add(x).add(y), 1, 0, c);
    }

    private static void triangle(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 a, Vec3 b, Vec3 c, int color) {
        Vec3 n = b.subtract(a).cross(c.subtract(a));
        n = n.lengthSqr() < 1e-12 ? new Vec3(0, 1, 0) : n.normalize();
        for (Vec3 p : new Vec3[] {a, b, c, c}) {
            Vec3 rel = p.subtract(eye);
            buf.addVertex(pose, (float) rel.x, (float) rel.y, (float) rel.z).setColor(color).setUv(0, 0)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    /** Positions are written relative to the camera, so rays far from the world origin keep their precision. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 at, float u, float v, int color) {
        Vec3 rel = at.subtract(eye);
        buf.addVertex(pose, (float) rel.x, (float) rel.y, (float) rel.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    // ---- small helpers ---------------------------------------------------------------------------

    /** 0 inside {@code near}, 1 beyond {@code far}: nothing piles up right in front of the camera. */
    private static float nearFactor(Vec3 at, Vec3 eye, double near, double far) {
        return smooth((float) ((at.distanceTo(eye) - near) / (far - near)));
    }

    private static int fadeNear(int color, Vec3 at, Vec3 eye, double near, double far, float floor) {
        return scaleAlpha(color, floor + (1 - floor) * nearFactor(at, eye, near, far));
    }

    private static int scaleAlpha(int color, float k) {
        int a = (int) ((color >>> 24) * Math.max(0, Math.min(1, k)));
        return a << 24 | color & 0xFFFFFF;
    }

    private static int argb(float alpha, int rgb) {
        return (int) (255 * Math.max(0, Math.min(1, alpha))) << 24 | rgb & 0xFFFFFF;
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /** One layer: additive (glows) or alpha-blended (mist, crystals), never writing depth, so blocks still hide it. */
    private static RenderType material(String texture, String define, boolean additive) {
        String name = define == null ? texture : define.toLowerCase(java.util.Locale.ROOT);
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/frost_ray_" + name))
                .withFragmentShader(id("core/frost_ray_geometry")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (define != null) pipeline.withShaderDefine(define);
        return RenderType.create("logos_frost_ray_" + name, RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", id("textures/vfx/frost_ray/" + texture + ".png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
