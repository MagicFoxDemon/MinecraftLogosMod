package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A physical vein passing over the ground (see VEIN_VFX.md): a sword's qi skimming along it, after the reference the
 * maintainer picked (the stylized slash pack's {@code GroundSlash06}) in the physical element's pale gold. A tall
 * standing crescent blade rides the head, its leading edge white-hot, kicking up crumbs and dust. Behind it the ground
 * is cut: a glowing line where it passed, and chunks of the ground on both sides pried up and tipped outward, in the
 * ground's own texture; they stay a while, then sink back, the oldest first. Where the head stops on something the
 * blade breaks in a burst.
 */
final class PhysicalSlashVeinVisual implements VeinVisual {
    private static final RenderType BODY = VfxParts.blend("physical_ray/wind"), EDGE = VfxParts.add("physical_ray/wind");
    private static final RenderType HALO = VfxParts.blend("physical/glow"), CORE = VfxParts.add("physical/glow");
    private static final RenderType DUST = VfxParts.blend("physical_ray/smoke");
    private static final int GOLD = 0xEDBE5C, HOT = 0xFFFBEA;
    /** Ticks the cut glows white-hot at first, stays, and then fades. */
    private static final int HOT_TICKS = 10, HOLD = 40, FADE = 20;
    /** Ticks a pried-up chunk takes to come up, and to sink back. */
    private static final float RISE = 3, SINK = 8;
    /** Chunks pried up per block crawled, on each side. */
    private static final double CHUNKS_PER_BLOCK = 2.5;
    /** Ticks the blade takes to break up once the head has stopped. */
    private static final float BREAK = 4;
    /** The most marks (one a tick), chunks and dust puffs kept at once. */
    private static final int MAX_MARKS = 200, MAX_CHUNKS = 160, MAX_DUST = 24;
    /** Points along the blade's crescent. */
    private static final int ARC = 16;

    /** Where the head stood at tick {@code tick}, on the surface facing {@code n}, {@code side} across its way. */
    private record Mark(Vec3 at, Vec3 n, Vec3 side, float width, long tick) {}

    /** A puff of dust at {@code at}, drifting {@code drift} a tick, for {@code life} ticks. */
    private record Puff(Vec3 at, Vec3 drift, float size, float roll, int tint, long born, int life) {}

    /**
     * A chunk of the ground pried up at {@code at} on the surface facing {@code n}, {@code size} half its edge, tipped
     * to {@code turn}, wearing {@code sprite}, shaded {@code tint} by the light there; up at {@code born}, gone at {@code until}.
     */
    private record Chunk(Vec3 at, Vec3 n, float size, Quaternionf turn, TextureAtlasSprite sprite, int tint, long born, long until) {}

    private final List<Mark> marks = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Puff> dust = new ArrayList<>();
    private double carried;

    @Override
    public boolean tick(Level level, LiveVein vein) {
        long now = ClientCharges.ticks();
        RandomSource r = level.getRandom();
        Vec3 n = vein.normal();
        if (!vein.ended()) {
            Vec3 side = vein.dir().cross(n);
            side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
            float width = vein.width();
            if (marks.isEmpty()) marks.add(new Mark(vein.prev(), n, side, width, now));
            if (marks.size() < MAX_MARKS) marks.add(new Mark(vein.pos(), n, side, width, now));
            pry(level, vein, r, side, now);
            kick(level, vein, r, now);
        } else if (vein.sinceEnd() == 1 && vein.struck()) {
            Vec3 at = vein.pos().add(n.scale(vein.height() / 2));
            LogosFx.accents(level, Element.PHYSICAL, at, 12, vein.width() / 2, 0.08);
            for (int k = 0; k < 4; k++) puff(level, vein.pos().add(n.scale(.3)), r.nextGaussian(), r, now, vein, 1.4f);
        }
        marks.removeIf(m -> now - m.tick() >= HOLD + FADE);
        chunks.removeIf(c -> c.until() <= now);
        dust.removeIf(d -> now - d.born() >= d.life());
        return !vein.ended() || !marks.isEmpty() || !chunks.isEmpty() || !dust.isEmpty();
    }

    /** Pries chunks of the ground up on both sides of what the head cut since the last tick. */
    private void pry(Level level, LiveVein vein, RandomSource r, Vec3 side, long now) {
        Vec3 from = vein.prev(), to = vein.pos(), n = vein.normal();
        double length = from.distanceTo(to), every = 1 / CHUNKS_PER_BLOCK;
        Vec3 dir = length > 1e-6 ? to.subtract(from).scale(1 / length) : vein.dir();
        for (double s = every - carried; s <= length; s += every) {
            Vec3 on = from.lerp(to, length < 1e-9 ? 1 : s / length);
            for (int k = -1; k <= 1; k += 2) {
                if (chunks.size() >= MAX_CHUNKS) break;
                Vec3 at = on.add(side.scale(k * vein.width() * (.32 + .28 * r.nextDouble()))).add(dir.scale((r.nextDouble() - .5) * every));
                // The block the chunk is pried out of.
                Vec3 inside = at.subtract(n.scale(.1));
                TextureAtlasSprite sprite = VfxParts.blockSprite(level, inside.x, inside.y + .5, inside.z);
                if (sprite == null) continue;
                float size = vein.width() * (.09f + .08f * r.nextFloat());
                // Tipped outward over the cut, a little askew.
                Quaternionf turn = new Quaternionf()
                        .rotateAxis(k * (float) Math.toRadians(25 + 35 * r.nextDouble()), (float) dir.x, (float) dir.y, (float) dir.z)
                        .rotateAxis(r.nextFloat() * Mth.TWO_PI, (float) n.x, (float) n.y, (float) n.z)
                        .rotateAxis((r.nextFloat() - .5f) * .6f, (float) side.x, (float) side.y, (float) side.z);
                float light = level.getMaxLocalRawBrightness(BlockPos.containing(at.add(n.scale(.5)))) / 15f;
                int tint = VfxParts.lerpColor(0, 0xFFFFFF, .25f + .75f * light);
                long born = now + r.nextInt(2);
                chunks.add(new Chunk(at, n, size, turn, sprite, tint, born, born + HOLD + r.nextInt(12)));
            }
        }
        carried = (carried + length) % every;
    }

    /** Crumbs of the ground and a puff of dust thrown up behind the blade. */
    private void kick(Level level, LiveVein vein, RandomSource r, long now) {
        Vec3 n = vein.normal(), back = vein.dir().scale(-1);
        Vec3 at = vein.pos();
        BlockState ground = level.getBlockState(BlockPos.containing(at.subtract(n.scale(.1))));
        if (!ground.isAir() && ground.getFluidState().isEmpty()) {
            for (int k = 0; k < 3; k++) {
                Vec3 p = at.add(n.scale(.05)).add(back.scale(r.nextDouble() * .4));
                Vec3 v = n.scale(.18 + r.nextDouble() * .15).add(back.scale(.05 + r.nextDouble() * .08))
                        .add(r.nextGaussian() * .04, r.nextGaussian() * .04, r.nextGaussian() * .04);
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y, p.z, v.x, v.y, v.z);
            }
        }
        puff(level, at.add(n.scale(.2)).add(back.scale(.3)), 0, r, now, vein, 1);
    }

    private void puff(Level level, Vec3 at, double spread, RandomSource r, long now, LiveVein vein, float scale) {
        if (dust.size() >= MAX_DUST) return;
        Vec3 n = vein.normal();
        Vec3 drift = n.scale(.02 + .015 * r.nextDouble()).add(r.nextGaussian() * .015, r.nextGaussian() * .015, r.nextGaussian() * .015);
        if (spread != 0) drift = drift.add(new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(.04));
        dust.add(new Puff(at, drift, vein.width() * (.3f + .15f * r.nextFloat()) * scale, r.nextFloat() * 6,
                VfxParts.groundTint(level, at), now, 14 + r.nextInt(8)));
    }

    @Override
    public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        boolean frozen = ClientCharges.frozen();
        double now = ClientCharges.ticks() + (frozen ? 0 : partialTick);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (!chunks.isEmpty()) out.submitCustomGeometry(pose, VfxParts.BLOCKS, (p, buf) -> {
            for (Chunk c : chunks) chunk(p, buf, c, now);
        });
        if (marks.size() > 1) {
            out.submitCustomGeometry(pose, HALO, (p, buf) -> cut(p, buf, now, false));
            out.submitCustomGeometry(pose, CORE, (p, buf) -> cut(p, buf, now, true));
        }
        float broken = vein.ended() ? (float) Mth.clamp((vein.sinceEnd() - 1 + (frozen ? 0 : partialTick)) / BREAK, 0, 1) : 0;
        if (broken < 1) {
            Vec3 at = vein.ended() ? vein.pos() : vein.at(frozen ? 1 : partialTick);
            out.submitCustomGeometry(pose, BODY, (p, buf) -> blade(p, buf, vein, at, now, broken, false));
            out.submitCustomGeometry(pose, EDGE, (p, buf) -> blade(p, buf, vein, at, now, broken, true));
        }
        if (!dust.isEmpty()) out.submitCustomGeometry(pose, DUST, (p, buf) -> {
            for (Puff d : dust) {
                float k = (float) Mth.clamp((now - d.born()) / d.life(), 0, 1);
                if (k >= 1) continue;
                Vec3 c = d.at().add(d.drift().scale(now - d.born()));
                float a = VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.3f, 1, k)) * VfxParts.fade(view.cam(), c, .5, 1.5) * .4f;
                VfxParts.sprite(p, buf, view, c, d.size() * (.6f + .9f * k), d.roll() + k * .5f, VfxParts.argb(a, d.tint()));
            }
        });
        pose.popPose();
    }

    /**
     * A pried-up chunk: it bursts up out of the surface (a little past where it settles), stays, then sinks back in and
     * fades. A corner of its block's texture on every face.
     */
    private static void chunk(PoseStack.Pose pose, VertexConsumer buf, Chunk c, double now) {
        double t = now - c.born();
        if (t <= 0) return;
        float up = t >= RISE ? 1 : backOut((float) (t / RISE));
        double left = c.until() - now;
        float sink = left >= SINK ? 0 : 1 - (float) Math.max(0, left / SINK);
        // Its middle a little above the surface when settled, a full chunk under it when gone.
        float lift = c.size() * (.3f - 2.3f * (1 - up) - 2.3f * sink);
        TextureAtlasSprite s = c.sprite();
        float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
        VfxParts.cube(pose, buf, c.at().add(c.n().scale(lift)), c.size(), c.turn(), s.getU0() + du, s.getV0() + dv,
                s.getU0() + du * 3, s.getV0() + dv * 3, c.tint(), 1 - sink * sink);
    }

    /**
     * The glowing line the blade cut: a soft pale gold band ({@code core} false) with a narrow white-hot line down its
     * middle, lying on the surface along the marks. Each mark fades by its own age, the core cooling first.
     */
    private void cut(PoseStack.Pose pose, VertexConsumer buf, double now, boolean core) {
        for (int i = 1; i < marks.size(); i++) {
            Mark a = marks.get(i - 1), b = marks.get(i);
            // A step round a corner (floor to wall) would cut through the block.
            if (!a.n().equals(b.n()) || a.at().distanceToSqr(b.at()) < 1e-8) continue;
            float ka = glow(now - a.tick(), core), kb = glow(now - b.tick(), core);
            if (ka <= 0 && kb <= 0) continue;
            float half = core ? .09f : .45f;
            Vec3 lift = a.n().scale(core ? .025 : .02);
            Vec3 sa = a.side().scale(a.width() * half), sb = b.side().scale(b.width() * half);
            Vec3 pa = a.at().add(lift), pb = b.at().add(lift);
            int rgb = core ? HOT : GOLD;
            int ca = VfxParts.argb(ka, rgb), cb = VfxParts.argb(kb, rgb);
            // The glow texture's middle column, across the band: bright down the middle, soft to both sides.
            VfxParts.vertex(pose, buf, pa.add(sa), .5f, 0, ca);
            VfxParts.vertex(pose, buf, pa.subtract(sa), .5f, 1, ca);
            VfxParts.vertex(pose, buf, pb.subtract(sb), .5f, 1, cb);
            VfxParts.vertex(pose, buf, pb.add(sb), .5f, 0, cb);
        }
    }

    /** How bright the cut is {@code age} ticks after the blade passed: the core white-hot at first, both fading at the end. */
    private static float glow(double age, boolean core) {
        if (age < 0) return 0;
        float fade = 1 - VfxParts.smooth(HOLD, HOLD + FADE, (float) age);
        return core ? fade * (.6f + .4f * (1 - VfxParts.smooth(0, HOT_TICKS, (float) age))) : fade * .7f;
    }

    /**
     * The crescent blade standing at {@code at} on the head's surface, its convex edge leading and both tips trailing
     * back, the lower one sunk into the ground: the body ({@code edge} false) a pale gold veil fading toward its inner
     * edge, the edge a thin white-hot band along the leading curve. It swells and fades as it breaks.
     */
    private static void blade(PoseStack.Pose pose, VertexConsumer buf, LiveVein vein, Vec3 at, double now, float broken, boolean edge) {
        Vec3 f = vein.dir(), n = vein.normal();
        f = f.subtract(n.scale(f.dot(n)));
        if (f.lengthSqr() < 1e-6) return;
        f = f.normalize();
        float grow = 1 + .25f * broken, fade = 1 - broken;
        double high = Math.max(.8, vein.height()) * 2.4 * grow, length = Math.max(.8, vein.width()) * 2 * grow;
        // Bottom tip, the bulge's control point (the leading edge's and the inner edge's), and the top tip, in (along, up).
        double[] bottom = {-.55, -.12}, top = {-.35, 1}, outer = {1.1, .46}, inner = {.1, .46};
        float pan = (float) (now * .08);
        Vec3 lastO = null, lastM = null, lastI = null;
        float lastU = 0, lastA = 0;
        for (int i = 0; i <= ARC; i++) {
            double t = i / (double) ARC;
            Vec3 o = crescent(at, f, n, length, high, bottom, outer, top, t);
            Vec3 in = crescent(at, f, n, length, high, bottom, inner, top, t);
            if (edge) in = o.lerp(in, .22);
            // Full from the leading edge to the middle, then fading to nothing at the inner edge.
            Vec3 mid = o.lerp(in, .45);
            // Thinning out toward both tips.
            float a = (float) Math.pow(Math.sin(Math.PI * t), .6) * fade;
            float u = (float) t * 1.4f - pan;
            if (lastO != null) {
                int c0 = VfxParts.argb(lastA, edge ? HOT : GOLD), c1 = VfxParts.argb(a, edge ? HOT : GOLD);
                int m0 = VfxParts.argb(lastA * .8f, edge ? HOT : GOLD), m1 = VfxParts.argb(a * .8f, edge ? HOT : GOLD);
                VfxParts.vertex(pose, buf, lastO, lastU, 0, c0);
                VfxParts.vertex(pose, buf, lastM, lastU, .45f, m0);
                VfxParts.vertex(pose, buf, mid, u, .45f, m1);
                VfxParts.vertex(pose, buf, o, u, 0, c1);
                VfxParts.vertex(pose, buf, lastM, lastU, .45f, m0);
                VfxParts.vertex(pose, buf, lastI, lastU, 1, edge ? c0 & 0xFFFFFF : VfxParts.argb(lastA * .2f, GOLD));
                VfxParts.vertex(pose, buf, in, u, 1, edge ? c1 & 0xFFFFFF : VfxParts.argb(a * .2f, GOLD));
                VfxParts.vertex(pose, buf, mid, u, .45f, m1);
            }
            lastO = o;
            lastM = mid;
            lastI = in;
            lastU = u;
            lastA = a;
        }
    }

    /** A point {@code t} along one of the crescent's curves, a quadratic from {@code a} through control {@code b} to {@code c}. */
    private static Vec3 crescent(Vec3 at, Vec3 f, Vec3 n, double length, double high, double[] a, double[] b, double[] c, double t) {
        double s = 1 - t;
        double x = s * s * a[0] + 2 * s * t * b[0] + t * t * c[0], y = s * s * a[1] + 2 * s * t * b[1] + t * t * c[1];
        return at.add(f.scale(x * length)).add(n.scale(y * high));
    }

    /** Up past 1 and back: the overshoot of something pried up hard. */
    private static float backOut(float t) {
        float k = 1.7f, s = t - 1;
        return 1 + s * s * ((k + 1) * s + k);
    }
}
