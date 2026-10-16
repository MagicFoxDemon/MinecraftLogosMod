package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import static dev.logos.client.FrostBeamDraw.*;

/**
 * What a frost beam leaves on its last line, matching the beam: the ice body and starry band fade within
 * half a second while a rime line freezes on the core and slowly cracks apart; the cold haze widens and
 * thins; frost mist puffs swell, sink and drift off; the beam's ice crystals tumble down glinting, flecks
 * and snow drift down, a few starry glints keep twinkling on the line; at a hit the frost sigil and a cloud
 * of mist linger at the end. About six and a half seconds in all, well inside {@code LogosFx}'s 200 ticks.
 */
final class FrostBeamWake implements RayVisual {
    /** Nothing is left after this many ticks. */
    static final int LIFE = 130;

    private Line line = new Line(Vec3.ZERO, new Vec3(0, 0, 1));
    private long born;
    private float k = 1;
    private boolean blocked;
    private final List<Puff> puffs = new ArrayList<>();
    private final List<Shard> shards = new ArrayList<>();
    private final List<Mote> motes = new ArrayList<>();

    /** A frost mist puff: where it starts, where it drifts, and when it comes and goes. */
    private record Puff(Vec3 at, Vec3 drift, float size, float roll, float spin, int frame, float appear, float fade, float gone) {}
    /** A tumbling ice crystal: thrown off at {@code velocity}, slowed by the air, then falling. */
    private record Shard(Vec3 at, Vec3 velocity, Vec3 spin, float rate, float size, float appear, float gone, boolean glints) {}
    /** A small sprite drifting down: a snowflake, an ice fleck or a starry glint ({@code kind} 0, 1, 2). */
    private record Mote(int kind, Vec3 at, Vec3 drift, float size, float roll, float spin, int frame, float appear, float gone) {}

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        line = new Line(from, to);
        born = level.getGameTime();
        k = Math.max(.4f, Math.min(3, FrostBeamVisual.lastRadius / .35f));
        blocked = line.length > .5 && blocked(level, line);
        Random r = new Random(level.getRandom().nextLong());
        scatter(r);
    }

    @Override
    public boolean tick(Level level) {
        return level.getGameTime() - born < LIFE;
    }

    private void scatter(Random r) {
        double length = line.length, rr = .35 * k;
        int mist = clamp((int) (length * 1.6), 5, 64);
        for (int i = 0; i < mist; i++) {
            double s = (i + r.nextDouble()) / mist;
            Vec3 lateral = lateral(r, .15 * k);
            // Cold mist spreads out from the line and sinks a little.
            Vec3 drift = lateral.normalize().scale(.012 + r.nextDouble() * .014).add(0, -.005 - r.nextDouble() * .004, 0)
                    .add(line.axis.scale((r.nextDouble() - .5) * .01));
            float gone = 88 + r.nextFloat() * 40;
            puffs.add(new Puff(along(s).add(lateral), drift, (.6f + r.nextFloat() * .5f) * k, r.nextFloat() * 6.283f,
                    (r.nextFloat() - .5f) * .03f, r.nextInt(4), r.nextFloat() * 4, gone - 45, gone));
        }
        int crystals = clamp((int) (length * 1.6), 8, 64);
        for (int i = 0; i < crystals; i++) {
            double s = (i + r.nextDouble()) / crystals;
            Vec3 lateral = lateral(r, rr);
            Vec3 velocity = lateral.normalize().scale(.01 + r.nextDouble() * .03).add(0, .004 + r.nextDouble() * .016, 0);
            shards.add(new Shard(along(s).add(lateral), velocity, randomUnit(r), .08f + r.nextFloat() * .2f,
                    (.045f + r.nextFloat() * .05f) * k, r.nextFloat() * 3, 60 + r.nextFloat() * 62, r.nextFloat() < .5f));
        }
        int flakes = clamp((int) (length * .7), 4, 32);
        for (int i = 0; i < flakes; i++) {
            double s = (i + r.nextDouble()) / flakes;
            Vec3 drift = lateral(r, 1).scale(.01).add(0, -.006 - r.nextDouble() * .006, 0);
            motes.add(new Mote(0, along(s).add(lateral(r, rr)), drift, (.07f + r.nextFloat() * .06f) * k,
                    r.nextFloat() * 6.283f, (r.nextFloat() - .5f) * .08f, 0, 3 + r.nextFloat() * 10, 80 + r.nextFloat() * 45));
        }
        int flecks = clamp((int) (length * 1.2), 6, 48);
        for (int i = 0; i < flecks; i++) {
            double s = (i + r.nextDouble()) / flecks;
            Vec3 drift = lateral(r, 1).scale(.006).add(0, -.004 - r.nextDouble() * .004, 0);
            motes.add(new Mote(1, along(s).add(lateral(r, rr * 1.2)), drift, (.08f + r.nextFloat() * .06f) * k,
                    r.nextFloat() * 6.283f, (r.nextFloat() - .5f) * .06f, r.nextInt(4), r.nextFloat() * 4, 40 + r.nextFloat() * 50));
        }
        int glints = clamp((int) (length * 1.5), 6, 64);
        for (int i = 0; i < glints; i++) {
            double s = (i + r.nextDouble()) / glints;
            Vec3 drift = lateral(r, 1).scale(.003).add(0, -.002, 0);
            motes.add(new Mote(2, along(s).add(lateral(r, rr)), drift, (.1f + r.nextFloat() * .12f) * k,
                    r.nextFloat() * 20, .25f + r.nextFloat() * .3f, r.nextInt(9), r.nextFloat() * 6, 50 + r.nextFloat() * 45));
        }
        if (blocked) {
            Vec3 at = line.to.subtract(line.axis.scale(.2));
            for (int i = 0; i < 5; i++) {
                Vec3 drift = randomUnit(r).add(line.axis.scale(-1)).normalize().scale(.015 + r.nextDouble() * .012);
                puffs.add(new Puff(at.add(randomUnit(r).scale(.15)), drift, (.45f + r.nextFloat() * .3f) * k, r.nextFloat() * 6.283f,
                        (r.nextFloat() - .5f) * .04f, r.nextInt(4), 0, 50 + r.nextFloat() * 20, 95 + r.nextFloat() * 25));
            }
            for (int i = 0; i < 8; i++) {
                Vec3 dir = line.axis.scale(-.6 - r.nextDouble() * .6).add(randomUnit(r)).normalize();
                shards.add(new Shard(at.add(randomUnit(r).scale(.12)), dir.scale(.06 + r.nextDouble() * .08).add(0, .03, 0),
                        randomUnit(r), .15f + r.nextFloat() * .3f, (.06f + r.nextFloat() * .06f) * k, 0, 45 + r.nextFloat() * 35, r.nextFloat() < .5f));
            }
        }
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        double length = line.length;
        if (length < .05) return;
        float age = (float) (level.getGameTime() - born) + partialTick;
        if (age > LIFE) return;
        Vec3 eye = camera.pos;
        Vec3 camRight = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 camUp = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        double r = .35 * k;
        float t = (float) ((level.getGameTime() + partialTick) % 24000);
        Width taper = s -> {
            double head = Math.min(1, s / 1.4);
            return .28 + .72 * head * head * (3 - 2 * head);
        };
        // Beside a first-person camera (the caster still standing there) the first blocks fade out entirely.
        boolean close = eye.distanceTo(line.from) < 1.6;
        double near = close ? 1.1 : .5, far = close ? 4.5 : 2.4;
        float floor = close ? 0 : .25f;

        // ---- the beam itself lets go: body, band and core fade and thin within half a second ----
        if (age < 12) {
            float w = 1 - smooth(age / 12);
            float thin = .55f + .45f * w;
            out.submitCustomGeometry(pose, ICE, (p, buf) -> ribbon(p, buf, eye, line, 0, length,
                    s -> 1.05 * r * thin * taper.at(s), s -> argb(.72f * w, mix(0x7FD3F2, 0x3479E0, (float) (s / Math.max(6, length)))),
                    -t * .11f, 1 / 7f, near, far, floor));
            out.submitCustomGeometry(pose, STARRY, (p, buf) -> ribbon(p, buf, eye, line, 0, length,
                    s -> .95 * r * taper.at(s), s -> argb(.6f * w, 0xB8E4FF), -t * .06f, 1 / 3.5f, near, far, floor));
            out.submitCustomGeometry(pose, LASER, (p, buf) -> ribbon(p, buf, eye, line, 0, length,
                    s -> .55 * r * thin * w * taper.at(s), s -> argb(.7f * w, DEEP), 0, 1, near, far, floor));
        }

        // ---- the cold haze stays on the line, widening and thinning away ----
        if (age < 120) {
            float h = age / 120;
            float a = .3f * (age < 6 ? 1 : 1 - smooth((age - 6) / 114));
            out.submitCustomGeometry(pose, HAZE, (p, buf) -> ribbon(p, buf, eye, line, 0, length,
                    s -> (1.9 + 1.4 * Math.sqrt(h)) * r * taper.at(s), s -> argb(a, mix(0xCFE6FF, 0x98B4D6, h)),
                    -t * .02f, 1 / 6f, near, far, 0));
        }

        // ---- the rime line: the frozen core, cracking apart ----
        if (age < 95) {
            float left = age < 12 ? 1 : 1 - (age - 12) / 83;
            float settle = Math.min(1, age / 6);
            out.submitCustomGeometry(pose, RIME, (p, buf) -> ribbon(p, buf, eye, line, 0, length,
                    s -> .12 * k * taper.at(s), s -> argb(left, mix(0xFFFFFF, 0x9CD2FF, settle)), 0, 1 / 2.5f, near * .8, far * .8, floor));
        }

        // ---- at a hit: the frost sigil fades out at the end ----
        if (blocked && age < 60) {
            float fade = 1 - smooth(age / 60);
            float glow = 1 - smooth(age / 25);
            Vec3 at = line.to.subtract(line.axis.scale(Math.min(.15, length * .2)));
            out.submitCustomGeometry(pose, RUNE, (p, buf) -> disc(p, buf, eye, at, line.right, line.up, line.axis,
                    (.55 + .25 * age / 60) * k, -t * .03, argb(.8f * fade, BLUE)));
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> sprite(p, buf, eye, camRight, camUp, at, k * (.6 + .5 * glow), 0, 0, 0, 1, 1,
                    argb(.6f * glow, BLUE)));
        }

        // ---- the lingering frost ----
        out.submitCustomGeometry(pose, MIST, (p, buf) -> {
            for (Puff puff : puffs) puff(p, buf, eye, camRight, camUp, puff, age);
        });
        out.submitCustomGeometry(pose, CRYSTAL, (p, buf) -> {
            for (Shard shard : shards) shard(p, buf, eye, shard, age);
        });
        out.submitCustomGeometry(pose, FLECKS, (p, buf) -> {
            for (Mote m : motes) if (m.kind == 1) mote(p, buf, eye, camRight, camUp, m, age, .85f, 2);
        });
        out.submitCustomGeometry(pose, GLINTS, (p, buf) -> {
            for (Shard shard : shards) if (shard.glints) glint(p, buf, eye, camRight, camUp, shard, age);
            for (Mote m : motes) if (m.kind == 2) mote(p, buf, eye, camRight, camUp, m, age, 1, 3);
        });
        out.submitCustomGeometry(pose, SNOW, (p, buf) -> {
            for (Mote m : motes) if (m.kind == 0) mote(p, buf, eye, camRight, camUp, m, age, .85f, 1);
        });
    }

    // ---- pieces ----------------------------------------------------------------------------------

    private void puff(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Puff p, float age) {
        float t = age - p.appear;
        if (t < 0 || age > p.gone) return;
        float in = smooth(Math.min(1, t / 8));
        float out = age < p.fade ? 1 : 1 - smooth((age - p.fade) / (p.gone - p.fade));
        // Drifts out fast at first and settles: the integral of an exponentially slowing drift.
        double travel = 18 * (1 - Math.exp(-t / 30));
        Vec3 at = p.at.add(p.drift.scale(travel));
        double size = p.size * (.55 + .7 * (1 - Math.exp(-t / 24)));
        int color = fadeNear(argb(.42f * in * out, mix(0xBCD5F0, 0x93AED0, Math.min(1, t / 70))), at, eye, 1.8, 4, 0);
        cell(pose, buf, eye, camRight, camUp, at, size, p.roll + p.spin * t, p.frame, 2, 2, color);
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

    private void shard(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Vec3 eye, Shard s, float age) {
        float t = age - s.appear;
        if (t < 0 || age > s.gone) return;
        float shrink = shrink(s, t);
        double size = s.size * Math.min(1, t / 2) * shrink;
        Vec3 centre = shardAt(s, t);
        float near = nearFactor(centre, eye, 1.6, 3.5);
        double angle = s.rate * t;
        Vec3 base = s.spin.cross(new Vec3(0, 1, 0));
        if (base.lengthSqr() < 1e-6) base = new Vec3(1, 0, 0);
        base = base.normalize();
        Vec3 other = s.spin.cross(base);
        Vec3 dir = base.scale(Math.cos(angle)).add(other.scale(Math.sin(angle))).normalize();
        crystal(pose, buf, eye, centre, dir, size, angle * .5, (int) (215 * near * Math.min(1, shrink * 1.5)));
    }

    /** Some crystals catch the light: a twinkle that comes and goes on its own rhythm. */
    private void glint(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Shard s, float age) {
        float t = age - s.appear;
        if (t < 0 || age > s.gone) return;
        float twinkle = (float) Math.max(0, Math.sin(t * (.35 + s.rate) + s.size * 400));
        twinkle *= twinkle * twinkle;
        Vec3 at = shardAt(s, t);
        int color = fadeNear(argb(twinkle * shrink(s, t), 0xE8F8FF), at, eye, 1.6, 3.5, 0);
        cell(pose, buf, eye, camRight, camUp, at, s.size * 9, 0, (int) (s.size * 1000) % 9, 3, 3, color);
    }

    /** A drifting sprite; glints ({@code columns} 3) twinkle, the others turn as they fall. */
    private void mote(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Mote m,
            float age, float alpha, int columns) {
        float t = age - m.appear;
        if (t < 0 || age > m.gone) return;
        float k = t / (m.gone - m.appear);
        float a = alpha * smooth(t / 5) * (1 - smooth((k - .55f) / .45f));
        if (m.kind == 2) {
            float tw = (float) Math.max(0, Math.sin(t * m.spin + m.roll));
            a *= .25f + .75f * tw * tw;
        }
        Vec3 at = m.at.add(m.drift.scale(t)).add(line.right.scale(Math.sin(t * .15 + m.roll) * (m.kind == 2 ? .01 : .07)));
        int color = fadeNear(argb(a, m.kind == 2 ? PALE : 0xE6F6FF), at, eye, 1.6, 3.8, 0);
        double roll = m.kind == 2 ? 0 : m.roll + m.spin * t;
        cell(pose, buf, eye, camRight, camUp, at, m.size, roll, m.frame, columns, columns, color);
    }

    /** A point on the line, {@code s} from 0 to 1; the wand head itself is left clear. */
    private Vec3 along(double s) {
        double start = Math.min(.9, line.length * .15);
        return line.at(start + (line.length - start) * s);
    }

    private Vec3 lateral(Random r, double radius) {
        return line.radial(r.nextDouble() * Math.PI * 2).scale(radius * Math.sqrt(r.nextDouble()) + 1e-3);
    }

    private static Vec3 randomUnit(Random r) {
        Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
        return v.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : v.normalize();
    }
}
