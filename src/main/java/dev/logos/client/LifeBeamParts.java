package dev.logos.client;

import java.util.List;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * What the life beam ({@link LifeBeamVisual}) and its wake ({@link LifeBeamWake}) draw with: materials over
 * life's existing textures (read in place from {@code life_ray/} and {@code life_impact/}), camera-facing
 * bands and twisting vine ribbons along a line, and the leaves and motes of light that drift off it. Every
 * vertex carries position, colour, UV, light and normal; everything is submitted through the caller's
 * collector, so it goes through {@link ShaderPackOverlay}.
 */
final class LifeBeamParts {
    private LifeBeamParts() {}

    static final RenderType CORE = material("core", "life_ray/laser", true);
    static final RenderType CORE_TINT = material("core_tint", "life_ray/laser", false);
    static final RenderType VINES = material("vines", "life_ray/vines", true);
    static final RenderType VINES_TINT = material("vines_tint", "life_ray/vines", false);
    static final RenderType SHEATH = material("sheath", "life_ray/sheath", true);
    static final RenderType SHEATH_TINT = material("sheath_tint", "life_ray/sheath", false);
    static final RenderType FLARE = material("flare", "life_ray/flare", true);
    static final RenderType GLOW = material("glow", "life_impact/glow", true);
    static final RenderType RAYS = material("rays", "life_impact/rays", true);
    static final RenderType DOT = material("dot", "life_ray/dot", true);
    static final RenderType DOT_TINT = material("dot_tint", "life_ray/dot", false);
    static final RenderType LEAVES = material("leaves", "life_ray/leaves", false);

    /** Leaf tints after the prefab's random LeafAtlas colours; the last (brown) is rare. */
    static final int[] LEAF = {0x2FB83A, 0x58E83A, 0x8FE83A, 0xB8F25A, 0x46C95A, 0xA88A5A};

    // ---- leaves and motes ----------------------------------------------------------------------

    /**
     * A leaf (falls, sways and turns over, alpha-blended) or a mote of light (rises, additive), in absolute
     * game ticks so a beam's live ones can be handed on to its wake unchanged.
     */
    record Mote(boolean leaf, Vec3 at, Vec3 push, double drift, double swayX, double swayZ, double phase, double born, int life,
            int color, int frame, float size, float spin, float roll) {
        Vec3 pos(double t) {
            double a = Math.max(0, t - born);
            double sway = Math.sin(a * .075 + phase);
            // The push eases off over about eight ticks.
            double pushed = 8 * (1 - Math.exp(-a / 8));
            return at.add(push.scale(pushed)).add(swayX * sway, drift * a, swayZ * sway);
        }

        /** 0..1 over its life: quick fade-in, long hold, slow fade-out. */
        float alpha(double t) {
            double a = (t - born) / life;
            if (a <= 0 || a >= 1) return 0;
            return (float) Math.min(1, Math.min(a / .12, (1 - a) / .45));
        }

        double death() { return born + life; }
    }

    static Mote leaf(RandomSource r, Vec3 at, Vec3 push, double born, int life) {
        int tint = LEAF[r.nextInt(6) == 0 ? r.nextInt(LEAF.length) : r.nextInt(LEAF.length - 1)];
        return new Mote(true, at, push, -(.004 + .0055 * r.nextDouble()), (r.nextDouble() - .5) * .5, (r.nextDouble() - .5) * .5,
                r.nextDouble() * 6.28, born, life, tint, r.nextInt(4), .12f + .1f * r.nextFloat(), (r.nextFloat() - .5f) * .125f,
                r.nextFloat() * 6.28f);
    }

    static Mote light(RandomSource r, Vec3 at, Vec3 push, double born, int life) {
        return new Mote(false, at, push, .005 + .009 * r.nextDouble(), (r.nextDouble() - .5) * .12, (r.nextDouble() - .5) * .12,
                r.nextDouble() * 6.28, born, life, r.nextInt(3) == 0 ? 0x4BE8A8 : r.nextInt(3) == 0 ? 0xE8D25A : 0x7FF04A, 0,
                .04f + .05f * r.nextFloat(), 0, 0);
    }

    /** Draws the leaves of {@code motes} (alpha-blended) and the motes of light (tinted halo, then additive core). */
    static void motes(PoseStack stack, net.minecraft.client.renderer.SubmitNodeCollector out, List<Mote> motes, double t,
            Vec3 cam, Vec3 right, Vec3 up) {
        if (motes.isEmpty()) return;
        out.submitCustomGeometry(stack, LEAVES, (pose, buf) -> {
            for (Mote m : motes) if (m.leaf) mote(pose, buf, m, t, cam, right, up, false);
        });
        out.submitCustomGeometry(stack, DOT_TINT, (pose, buf) -> {
            for (Mote m : motes) if (!m.leaf) mote(pose, buf, m, t, cam, right, up, true);
        });
        out.submitCustomGeometry(stack, DOT, (pose, buf) -> {
            for (Mote m : motes) if (!m.leaf) mote(pose, buf, m, t, cam, right, up, false);
        });
    }

    private static void mote(PoseStack.Pose pose, VertexConsumer buf, Mote m, double t, Vec3 cam, Vec3 right, Vec3 up, boolean tint) {
        float a = m.alpha(t);
        if (a <= 0) return;
        Vec3 p = m.pos(t);
        float near = fade(cam, p, .6, 3);
        if (near <= 0) return;
        if (m.leaf) {
            float u0 = (m.frame & 1) * .5f, v0 = (m.frame >> 1) * .5f;
            float roll = m.roll + m.spin * (float) (t - m.born);
            // The leaf turning over as it falls.
            float flip = .35f + .65f * Math.abs((float) Math.cos((t - m.born) * .055 + m.phase));
            Vec3 r = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
            Vec3 u = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll))).scale(flip);
            quad(pose, buf, p, r, u, m.size, u0, v0, u0 + .5f, v0 + .5f, argb(.95f * a * near, m.color));
        } else {
            float twinkle = .65f + .35f * (float) Math.sin((t - m.born) * .5 + m.phase);
            if (tint) quad(pose, buf, p, right, up, m.size * 1.3f, 0, 0, 1, 1, argb(.55f * a * near, m.color));
            else quad(pose, buf, p, right, up, m.size * .55f, 0, 0, 1, 1, argb(a * twinkle * near, 0xF0FFD8));
        }
    }

    // ---- geometry ------------------------------------------------------------------------------

    /** 0 at {@code lo} blocks from the camera or closer, 1 from {@code hi}: what keeps a first-person muzzle clear. */
    static float fade(Vec3 cam, Vec3 p, double lo, double hi) {
        return (float) smooth(lo, hi, cam.distanceTo(p));
    }

    /** How much of its width a band keeps at {@code p}: a third right at the camera, all of it from five blocks. */
    static double slim(Vec3 cam, Vec3 p) {
        return .35 + .65 * smooth(.5, 5, cam.distanceTo(p));
    }

    static double smooth(double lo, double hi, double x) {
        double k = Mth.clamp((x - lo) / (hi - lo), 0, 1);
        return k * k * (3 - 2 * k);
    }

    /** Two unit vectors square to {@code dir}. */
    static Vec3[] basis(Vec3 dir) {
        Vec3 right = dir.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = dir.cross(new Vec3(1, 0, 0));
        right = right.normalize();
        return new Vec3[] {right, right.cross(dir).normalize()};
    }

    /** Distances along a line to place band joints: short pieces next to the muzzle (where it fades), then even ones. */
    private static double[] joints(double len, double step) {
        int n = Mth.clamp((int) Math.ceil(len / step), 2, 64);
        double[] at = new double[n + 6];
        int count = 0;
        at[count++] = 0;
        for (double d : new double[] {.3, .7, 1.2, 1.8}) if (d < len * .5) at[count++] = d;
        double start = at[count - 1];
        for (int i = 1; i <= n; i++) at[count++] = start + (len - start) * i / n;
        return java.util.Arrays.copyOf(at, count);
    }

    /**
     * A camera-facing band from {@code from} along {@code dir} for {@code len}, {@code width} across, its texture
     * repeating every {@code tile} blocks and shifted by {@code scroll}. It swells from a quarter width over
     * {@code taperIn} blocks, rounds off over the last half block, and fades between {@code near} and {@code far}
     * blocks from the camera.
     */
    static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 from, Vec3 dir, double len, float width,
            float scroll, float tile, int color, double taperIn, double near, double far) {
        if (len < .01) return;
        double[] at = joints(len, 1.5);
        Vec3 prevP = null, prevSide = null;
        float prevU = 0;
        int prevC = 0;
        for (double d : at) {
            Vec3 p = from.add(dir.scale(d));
            Vec3 side = dir.cross(cam.subtract(p));
            side = side.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : side.normalize();
            double taper = Math.min(.25 + .75 * Math.min(1, d / taperIn), Math.min(1, (len - d) / .5 + .25));
            // Narrower next to the camera, so a first-person beam leaving the wand stays slim.
            side = side.scale(width * .5 * taper * slim(cam, p));
            float u = (float) (d / tile) + scroll;
            int c = scaleAlpha(color, fade(cam, p, near, far));
            if (prevP != null) {
                vertex(pose, buf, prevP.add(prevSide), prevU, 0, prevC);
                vertex(pose, buf, prevP.subtract(prevSide), prevU, 1, prevC);
                vertex(pose, buf, p.subtract(side), u, 1, c);
                vertex(pose, buf, p.add(side), u, 0, c);
            }
            prevP = p; prevSide = side; prevU = u; prevC = c;
        }
    }

    /**
     * A thin camera-facing ribbon twisting round the line: {@code radius} out from it, one turn every
     * {@code pitch} blocks, turned by {@code phase}. It leaves the muzzle on the axis and winds out over the
     * first block and a half.
     */
    static void helix(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 from, Vec3 dir, double len, float radius,
            float width, double pitch, double phase, float scroll, float tile, int color, double near, double far) {
        if (len < .05) return;
        Vec3[] b = basis(dir);
        int n = Mth.clamp((int) Math.ceil(len / .2), 4, 320);
        Vec3 prevP = null, prevSide = null;
        float prevU = 0;
        int prevC = 0;
        double arc = 0;
        for (int i = 0; i <= n; i++) {
            double d = len * i / n;
            double th = phase + d / pitch * Math.PI * 2;
            double out = radius * Math.min(1, .15 + .85 * d / 1.5) * Math.min(1, (len - d) / .6 + .2);
            Vec3 p = from.add(dir.scale(d)).add(b[0].scale(Math.cos(th) * out)).add(b[1].scale(Math.sin(th) * out));
            if (prevP != null) arc += p.distanceTo(prevP);
            Vec3 tangent = prevP == null ? dir : p.subtract(prevP).normalize();
            Vec3 side = tangent.cross(cam.subtract(p));
            side = side.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : side.normalize();
            side = side.scale(width * .5 * Math.min(1, .3 + d / 1.2) * slim(cam, p));
            float u = (float) (arc / tile) + scroll;
            int c = scaleAlpha(color, fade(cam, p, near, far));
            if (prevP != null) {
                vertex(pose, buf, prevP.add(prevSide), prevU, 0, prevC);
                vertex(pose, buf, prevP.subtract(prevSide), prevU, 1, prevC);
                vertex(pose, buf, p.subtract(side), u, 1, c);
                vertex(pose, buf, p.add(side), u, 0, c);
            }
            prevP = p; prevSide = side; prevU = u; prevC = c;
        }
    }

    static void quadRolled(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 right, Vec3 up, float r, float roll, int color) {
        Vec3 rr = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
        Vec3 uu = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll)));
        quad(pose, buf, c, rr, uu, r, 0, 0, 1, 1, color);
    }

    static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 right, Vec3 up, float r,
            float u0, float v0, float u1, float v1, int color) {
        Vec3 x = right.scale(r), y = up.scale(r);
        vertex(pose, buf, c.subtract(x).add(y), u0, v0, color);
        vertex(pose, buf, c.subtract(x).subtract(y), u0, v1, color);
        vertex(pose, buf, c.add(x).subtract(y), u1, v1, color);
        vertex(pose, buf, c.add(x).add(y), u1, v0, color);
    }

    static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    // ---- colour --------------------------------------------------------------------------------

    static int argb(float alpha, int rgb) {
        return (int) (Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
    }

    static int scaleAlpha(int color, float k) {
        return argb((color >>> 24) / 255f * k, color);
    }

    static int lerpColor(int a, int b, float k) {
        k = Mth.clamp(k, 0, 1);
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static RenderType material(String name, String texture, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/life_beam_" + name)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_life_beam_" + name, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
