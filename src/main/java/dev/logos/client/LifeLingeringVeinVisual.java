package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A life vein that lingers (see VEIN_VFX.md): thorny vines grow up out of the surface all along where it crawled,
 * after the reference the maintainer picked (the Forest pack's rounded vine barrier). Most arch over and dive back in
 * a little behind where they came out; some rise and curl over at the top, like a shoot feeling for something to
 * climb. A leaf or two opens on each as it grows past. They stay as long as what the vein left there stays, then draw
 * back into the surface tip first. Unlike the crystals they are plants, not light: lit by the world, dark by night.
 */
final class LifeLingeringVeinVisual implements VeinVisual {
    private static final RenderType VINES = material("life_ray/vines"), LEAF = material("life_impact/leaf");
    /** Ticks a vine takes to grow to its full length. */
    private static final float GROW = 7;
    /** At most this many ticks to draw back in, and never more than this share of its life. */
    private static final float SINK = 14, SINK_SHARE = .3f;
    /** Vines per block crawled, and no more than this many for one head however long the way. */
    private static final double PER_BLOCK = 2.2;
    private static final int MAX = 120;
    /** What share of them rise and curl over rather than arch back in. */
    private static final float HOOKS = .35f;
    /** Beyond this far from the camera they are not drawn. */
    private static final double FAR = 96;
    /** Woody where it comes out of the ground, green further along; the leaves a fresher green. */
    private static final int ROOT = 0x4A3F22, STEM = 0x3E7A24, LEAF_TINT = 0x4C9A2C;

    /** A leaf {@code at} this share along its vine, {@code size} long, turned {@code turn} round it. */
    private record Leaf(float at, float size, float turn) {}

    /**
     * One vine along {@code points} (the first sunk a little into the surface facing {@code n}), {@code along} the
     * length up to each, {@code width} across; laid at tick {@code laid}, gone at {@code until}.
     */
    private record Vine(Vec3[] points, float[] along, Vec3 n, float width, float seed, long laid, long until, boolean hook,
            List<Leaf> leaves) {
        float length() { return along[along.length - 1]; }

        /** How much of it is out of the surface at {@code now}, 0 to 1. */
        float shown(double now) {
            double t = now - laid;
            if (t <= 0) return 0;
            float k = (float) Math.min(1, t / GROW), grown = 1 - (1 - k) * (1 - k) * (1 - k);
            double sink = Math.min(SINK, (until - laid) * SINK_SHARE), left = until - now;
            return left >= sink ? grown : grown * VfxParts.smooth(0, 1, (float) (left / Math.max(sink, 1e-3)));
        }
    }

    private final List<Vine> vines = new ArrayList<>();
    private double carried;

    @Override
    public boolean tick(Level level, LiveVein vein) {
        long now = ClientCharges.ticks();
        RandomSource r = level.getRandom();
        Vec3 head = vein.pos().add(vein.normal().scale(vein.height() / 2));
        if (!vein.ended()) {
            lay(level, vein, r, now);
        } else if (vein.sinceEnd() == 1 && vein.struck()) {
            LogosFx.accents(level, Element.LIFE, head, 10, vein.width() / 2, 0.06);
        }
        vines.removeIf(v -> v.until() <= now);
        return !vines.isEmpty() || !vein.ended();
    }

    /** Grows vines along what the head crawled since the last tick. */
    private void lay(Level level, LiveVein vein, RandomSource r, long now) {
        Vec3 from = vein.prev(), to = vein.pos(), n = vein.normal();
        double length = from.distanceTo(to);
        Vec3 dir = length > 1e-6 ? to.subtract(from).scale(1 / length) : vein.dir();
        Vec3 side = dir.cross(n);
        side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
        long until = now + Math.max(1, vein.linger());
        double every = 1 / PER_BLOCK;
        for (double s = every - carried; s <= length; s += every) {
            if (vines.size() >= MAX) break;
            Vec3 at = from.lerp(to, length < 1e-9 ? 1 : s / length).add(side.scale((r.nextDouble() - .5) * vein.width() * .8));
            Vine v = r.nextFloat() < HOOKS ? hook(vein, r, at, n, until, now) : arch(vein, r, at, dir, n, side, until, now);
            vines.add(v);
            CrystalVeinVisual.crumb(level, r, at, n);
        }
        carried = (carried + length) % every;
    }

    /** A vine that comes out at {@code at}, arches over and dives back in a little behind, bowed to one side. */
    private Vine arch(LiveVein vein, RandomSource r, Vec3 at, Vec3 dir, Vec3 n, Vec3 side, long until, long now) {
        double span = vein.width() * (.5 + .7 * r.nextDouble()), high = vein.height() * (.6 + .55 * r.nextDouble());
        Vec3 back = at.subtract(dir.scale(span)).add(side.scale((r.nextDouble() - .5) * vein.width() * .6));
        Vec3 bow = side.scale((r.nextDouble() - .5) * span * .8);
        Vec3 a = at.subtract(n.scale(.12)), b = back.subtract(n.scale(.12));
        // Control points a third higher than the arch stands, so its top is where it should be.
        Vec3 c1 = at.add(n.scale(high * 1.33)).add(bow), c2 = back.add(n.scale(high * 1.33)).add(bow);
        Vec3[] points = new Vec3[17];
        for (int i = 0; i < points.length; i++) points[i] = bezier(a, c1, c2, b, i / (points.length - 1.0));
        return vine(points, n, vein.width() * (.46f + .14f * r.nextFloat()), r, until, now, false);
    }

    /** A vine that comes out at {@code at}, rises leaning one way and curls over at the top, its tip free. */
    private Vine hook(LiveVein vein, RandomSource r, Vec3 at, Vec3 n, long until, long now) {
        double high = vein.height() * (.7 + .55 * r.nextDouble()), curl = high * (.18 + .1 * r.nextDouble());
        Vec3 u = perpendicular(n), w = n.cross(u);
        double turn = r.nextDouble() * Math.PI * 2;
        Vec3 lean = u.scale(Math.cos(turn)).add(w.scale(Math.sin(turn)));
        Vec3 top = at.add(n.scale(high - curl)).add(lean.scale(high * .25));
        Vec3 a = at.subtract(n.scale(.12));
        List<Vec3> points = new ArrayList<>();
        // Up to the top, arriving straight up so the curl goes on smoothly.
        for (int i = 0; i <= 8; i++) points.add(bezier(a, at.add(n.scale(high * .5)), top.subtract(n.scale(high * .25)), top, i / 8.0));
        // Then over and round, tighter as it goes.
        Vec3 centre = top.add(lean.scale(curl));
        double sweep = Math.PI * (1.4 + .4 * r.nextDouble());
        for (int i = 1; i <= 10; i++) {
            double t = sweep * i / 10, radius = curl * (1 - .5 * i / 10);
            points.add(centre.add(lean.scale(-Math.cos(t) * radius)).add(n.scale(Math.sin(t) * radius)));
        }
        return vine(points.toArray(Vec3[]::new), n, vein.width() * (.4f + .1f * r.nextFloat()), r, until, now, true);
    }

    private static Vine vine(Vec3[] points, Vec3 n, float width, RandomSource r, long until, long now, boolean hook) {
        float[] along = new float[points.length];
        for (int i = 1; i < points.length; i++) along[i] = along[i - 1] + (float) points[i].distanceTo(points[i - 1]);
        List<Leaf> leaves = new ArrayList<>();
        int count = 1 + r.nextInt(2);
        for (int k = 0; k < count; k++) leaves.add(new Leaf(.3f + .5f * r.nextFloat(), width * (.45f + .2f * r.nextFloat()), r.nextFloat() * 6.3f));
        // One after another as the head passes, not all in one tick.
        long when = now + r.nextInt(3);
        return new Vine(points, along, n, width, r.nextFloat(), when, until + (when - now), hook, leaves);
    }

    @Override
    public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (vines.isEmpty()) return;
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : partialTick);
        Vec3 cam = camera.pos;
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        out.submitCustomGeometry(pose, VINES, (p, buf) -> {
            for (Vine v : vines) {
                if (v.points()[0].distanceTo(cam) > FAR) continue;
                float shown = v.shown(now);
                if (shown > .01f) stem(p, buf, cam, v, shown * v.length(), light(level, v));
            }
        });
        out.submitCustomGeometry(pose, LEAF, (p, buf) -> {
            for (Vine v : vines) {
                if (v.points()[0].distanceTo(cam) > FAR) continue;
                float shown = v.shown(now) * v.length();
                for (Leaf leaf : v.leaves()) leaf(p, buf, v, leaf, shown, light(level, v));
            }
        });
        pose.popPose();
    }

    /** The world's light where the vine comes out, so it is dark by night like the plants round it. */
    private static int light(Level level, Vine v) {
        return LevelRenderer.getLightCoords(level, BlockPos.containing(v.points()[0].add(v.n().scale(.6))));
    }

    /**
     * The vine up to {@code reach} along it, as a strip turned to the camera: the texture's twisted thorny stems
     * running along it, narrowing to a point at the growing tip (and toward the end of a curling one).
     */
    private static void stem(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vine v, float reach, int light) {
        Vec3[] pts = v.points();
        float[] along = v.along();
        float repeat = v.width() * 2;
        Vec3 lastP = null, lastSide = null;
        float lastU = 0;
        int lastColor = 0;
        for (int i = 0; i < pts.length; i++) {
            float s = along[i];
            Vec3 p = pts[i];
            boolean end = false;
            if (s >= reach) {
                if (i == 0) return;
                float k = (reach - along[i - 1]) / Math.max(1e-4f, s - along[i - 1]);
                p = pts[i - 1].lerp(pts[i], k);
                s = reach;
                end = true;
            }
            Vec3 tangent = (i + 1 < pts.length && !end ? pts[i + 1] : p).subtract(i > 0 ? pts[i - 1] : p);
            Vec3 side = tangent.cross(cam.subtract(p));
            if (side.lengthSqr() < 1e-10) side = lastSide != null ? lastSide : perpendicular(tangent.lengthSqr() < 1e-10 ? v.n() : tangent);
            float taper = VfxParts.smooth(0, Math.min(.3f, v.width()), reach - s);
            if (v.hook()) taper *= 1 - .55f * s / v.length();
            Vec3 half = side.normalize().scale(v.width() * .5f * Math.max(taper, .05f));
            float u = v.seed() * 4 + s / repeat;
            int color = 0xFF000000 | VfxParts.lerpColor(ROOT, STEM, s / .5f);
            if (lastP != null) {
                vertex(pose, buf, lastP.add(lastSide), lastU, 0, lastColor, light);
                vertex(pose, buf, lastP.subtract(lastSide), lastU, 1, lastColor, light);
                vertex(pose, buf, p.subtract(half), u, 1, color, light);
                vertex(pose, buf, p.add(half), u, 0, color, light);
            }
            lastP = p;
            lastSide = half;
            lastU = u;
            lastColor = color;
            if (end) return;
        }
    }

    /** A leaf on {@code v}, opening as the vine grows past where it sits ({@code reach} along it). */
    private static void leaf(PoseStack.Pose pose, VertexConsumer buf, Vine v, Leaf leaf, float reach, int light) {
        float at = leaf.at() * v.length();
        float open = VfxParts.smooth(0, .35f, reach - at);
        if (open <= .02f) return;
        Vec3[] pts = v.points();
        float[] along = v.along();
        int i = 1;
        while (i < pts.length - 1 && along[i] < at) i++;
        float k = (at - along[i - 1]) / Math.max(1e-4f, along[i] - along[i - 1]);
        Vec3 base = pts[i - 1].lerp(pts[i], k), tangent = pts[i].subtract(pts[i - 1]).normalize();
        Vec3 a = perpendicular(tangent), b = tangent.cross(a);
        // Out from the stem, turned round it, and swept a little toward the tip.
        Vec3 outward = a.scale(Math.cos(leaf.turn())).add(b.scale(Math.sin(leaf.turn()))).add(tangent.scale(.6)).normalize();
        Vec3 across = tangent.cross(outward).normalize().scale(leaf.size() * .35f * open);
        Vec3 tip = base.add(outward.scale(leaf.size() * open));
        int color = 0xFF000000 | LEAF_TINT;
        vertex(pose, buf, base.subtract(across), 0, 1, color, light);
        vertex(pose, buf, base.add(across), 1, 1, color, light);
        vertex(pose, buf, tip.add(across), 1, 0, color, light);
        vertex(pose, buf, tip.subtract(across), 0, 0, color, light);
    }

    /**
     * Solid and lit by the world: vanilla's particle vertex stage (the world's light at each vertex) and
     * {@code core/vein_vine}, which cuts the texture by its alpha and shades by it. Both faces drawn; writes depth.
     */
    private static RenderType material(String texture) {
        Identifier id = Identifier.fromNamespaceAndPath(LogosMod.MODID, "textures/vfx/" + texture + ".png");
        String key = texture.replace('/', '_');
        var pipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(LogosMod.MODID, "pipeline/vein_vine_" + key))
                .withFragmentShader(Identifier.fromNamespaceAndPath(LogosMod.MODID, "core/vein_vine")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)).build();
        return RenderType.create("logos_vein_vine_" + key, RenderSetup.builder(pipeline).withTexture("Sampler0", id).useLightmap()
                .createRenderSetup());
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int light) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setLight(light).setNormal(pose, 0, 1, 0);
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
        double s = 1 - t;
        return a.scale(s * s * s).add(b.scale(3 * s * s * t)).add(c.scale(3 * s * t * t)).add(d.scale(t * t * t));
    }

    private static Vec3 perpendicular(Vec3 a) {
        Vec3 p = Math.abs(a.y) < .9 * a.length() ? new Vec3(0, 1, 0).cross(a) : new Vec3(1, 0, 0).cross(a);
        return p.normalize();
    }
}
