package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

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
 * What the shared charge ({@link ChargeCore}) draws with: materials over textures under {@code textures/vfx/}
 * (most of them read in place from other effects: {@code heal/glow}, {@code heal/ring}, {@code fire_beam/spiral}
 * and so on), and camera-facing quads, quads laid in a plane, ribbons along a path, open cylinders and small
 * cubes. Every vertex carries position, colour, UV, light and normal; everything is submitted through the
 * caller's collector, so it goes through {@link ShaderPackOverlay}.
 */
final class ChargeParts {
    private ChargeParts() {}

    private static final Map<String, RenderType> ADD = new HashMap<>();
    private static final Map<String, RenderType> BLEND = new HashMap<>();

    /** Additive (glowing) material over {@code textures/vfx/<texture>.png}. */
    static RenderType add(String texture) {
        return ADD.computeIfAbsent(texture, t -> material(t, "textures/vfx/" + t + ".png", true));
    }

    /** Alpha-blended material over {@code textures/vfx/<texture>.png}. */
    static RenderType blend(String texture) {
        return BLEND.computeIfAbsent(texture, t -> material(t, "textures/vfx/" + t + ".png", false));
    }

    /** Alpha-blended over the block atlas: debris lifted off the ground, drawn with the ground's own texture. */
    static final RenderType BLOCKS = RenderType.create("logos_charge_blocks", RenderSetup.builder(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET).withLocation(id("pipeline/charge_blocks")).withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
            .withTexture("Sampler0", Identifier.withDefaultNamespace("textures/atlas/blocks.png")).sortOnUpload().createRenderSetup());

    private static RenderType material(String name, String texture, boolean additive) {
        String key = name.replace('/', '_');
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/charge_" + (additive ? "add_" : "blend_") + key)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_charge_" + (additive ? "add_" : "blend_") + key, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id(texture)).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    // ---- geometry ------------------------------------------------------------------------------

    static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** A quad round {@code c} spanned by {@code a} and {@code b} (half-extents, not unit), whole texture. */
    static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, int color) {
        vertex(pose, buf, c.subtract(a).add(b), 0, 0, color);
        vertex(pose, buf, c.subtract(a).subtract(b), 0, 1, color);
        vertex(pose, buf, c.add(a).subtract(b), 1, 1, color);
        vertex(pose, buf, c.add(a).add(b), 1, 0, color);
    }

    /** A camera-facing square of half-size {@code r} turned by {@code roll}. */
    static void sprite(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, float r, float roll, int color) {
        float cos = Mth.cos(roll) * r, sin = Mth.sin(roll) * r;
        quad(pose, buf, c, view.right.scale(cos).add(view.up.scale(sin)), view.up.scale(cos).subtract(view.right.scale(sin)), color);
    }

    /** A camera-facing square showing frame {@code frame} of a {@code cols} x {@code rows} flipbook (row by row). */
    static void frame(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, float r, float roll, int frame, int cols, int rows, int color) {
        float cos = Mth.cos(roll) * r, sin = Mth.sin(roll) * r;
        Vec3 a = view.right.scale(cos).add(view.up.scale(sin)), b = view.up.scale(cos).subtract(view.right.scale(sin));
        float u0 = (frame % cols) / (float) cols, v0 = (frame / cols) / (float) rows, u1 = u0 + 1f / cols, v1 = v0 + 1f / rows;
        vertex(pose, buf, c.subtract(a).add(b), u0, v0, color);
        vertex(pose, buf, c.subtract(a).subtract(b), u0, v1, color);
        vertex(pose, buf, c.add(a).subtract(b), u1, v1, color);
        vertex(pose, buf, c.add(a).add(b), u1, v0, color);
    }

    /** A square of half-size {@code r} lying in the plane of the unit vectors {@code a} and {@code b}, turned by {@code turn}. */
    static void flat(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, float r, float turn, int color) {
        float cos = Mth.cos(turn) * r, sin = Mth.sin(turn) * r;
        quad(pose, buf, c, a.scale(cos).add(b.scale(sin)), b.scale(cos).subtract(a.scale(sin)), color);
    }

    /** A camera-facing streak from {@code tail} to {@code head}, {@code w} wide: the texture's U runs tail to head. */
    static void streak(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 tail, Vec3 head, float w, int color) {
        Vec3 along = head.subtract(tail);
        Vec3 side = along.cross(head.add(tail).scale(.5).subtract(view.cam));
        if (side.lengthSqr() < 1e-10) return;
        side = side.normalize().scale(w * .5);
        vertex(pose, buf, tail.add(side), 0, 0, color);
        vertex(pose, buf, tail.subtract(side), 0, 1, color);
        vertex(pose, buf, head.subtract(side), 1, 1, color);
        vertex(pose, buf, head.add(side), 1, 0, color);
    }

    /**
     * A camera-facing ribbon through {@code points}, each with its own half-width, colour and texture U (V
     * runs across). Segments whose ends are both transparent are skipped.
     */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3[] points, float[] half, int[] colors, float[] u, int count) {
        Vec3 prevSide = null;
        for (int i = 0; i < count; i++) {
            Vec3 tangent = points[Math.min(i + 1, count - 1)].subtract(points[Math.max(i - 1, 0)]);
            Vec3 side = tangent.cross(points[i].subtract(view.cam));
            side = side.lengthSqr() < 1e-12 ? (prevSide != null ? prevSide : view.right) : side.normalize();
            if (i > 0 && (colors[i - 1] >>> 24 != 0 || colors[i] >>> 24 != 0)) {
                vertex(pose, buf, points[i - 1].add(prevSide.scale(half[i - 1])), u[i - 1], 0, colors[i - 1]);
                vertex(pose, buf, points[i - 1].subtract(prevSide.scale(half[i - 1])), u[i - 1], 1, colors[i - 1]);
                vertex(pose, buf, points[i].subtract(side.scale(half[i])), u[i], 1, colors[i]);
                vertex(pose, buf, points[i].add(side.scale(half[i])), u[i], 0, colors[i]);
            }
            prevSide = side;
        }
    }

    /**
     * An open upright cylinder from {@code base}, {@code radius} wide and {@code height} tall: U wraps
     * {@code uTiles} times round it from {@code u0}, V runs from {@code v0} at the bottom by {@code vTiles}.
     */
    static void cylinder(PoseStack.Pose pose, VertexConsumer buf, Vec3 base, float radius, float height, int segments,
            float u0, float uTiles, float v0, float vTiles, int bottom, int top) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            Vec3 p0 = base.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
            Vec3 p1 = base.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            float s0 = u0 + uTiles * i / segments, s1 = u0 + uTiles * (i + 1) / segments;
            vertex(pose, buf, p0, s0, v0 + vTiles, bottom);
            vertex(pose, buf, p0.add(0, height, 0), s0, v0, top);
            vertex(pose, buf, p1.add(0, height, 0), s1, v0, top);
            vertex(pose, buf, p1, s1, v0 + vTiles, bottom);
        }
    }

    /** A cube of half-size {@code r} at {@code c}, turned by {@code turn}, each face showing [u0,u1]x[v0,v1], shaded by facing. */
    static void cube(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, Quaternionf turn, float u0, float v0, float u1, float v1,
            int rgb, float alpha) {
        Vector3f[] axes = {turn.transform(new Vector3f(r, 0, 0)), turn.transform(new Vector3f(0, r, 0)), turn.transform(new Vector3f(0, 0, r))};
        for (int f = 0; f < 6; f++) {
            Vector3f n = new Vector3f(axes[f % 3]).mul(f < 3 ? 1 : -1);
            Vector3f a = axes[(f + 1) % 3], b = axes[(f + 2) % 3];
            // Lit from above: tops brightest, undersides darkest.
            float shade = .62f + .38f * Mth.clamp(n.y / r * .5f + .5f, 0, 1);
            int color = argb(alpha, lerpColor(0, rgb, shade));
            Vec3 m = c.add(n.x, n.y, n.z), av = new Vec3(a.x, a.y, a.z), bv = new Vec3(b.x, b.y, b.z);
            vertex(pose, buf, m.subtract(av).add(bv), u0, v0, color);
            vertex(pose, buf, m.subtract(av).subtract(bv), u0, v1, color);
            vertex(pose, buf, m.add(av).subtract(bv), u1, v1, color);
            vertex(pose, buf, m.add(av).add(bv), u1, v0, color);
        }
    }

    /** Where the camera is and which way its screen axes point this frame. */
    record View(Vec3 cam, Vec3 right, Vec3 up) {
        static View of(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
            return new View(camera.pos, new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))),
                    new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))));
        }
    }

    // ---- maths and colour ----------------------------------------------------------------------

    static double smooth(double lo, double hi, double x) {
        double k = Mth.clamp((x - lo) / (hi - lo), 0, 1);
        return k * k * (3 - 2 * k);
    }

    static float smooth(float lo, float hi, float x) {
        return (float) smooth((double) lo, hi, x);
    }

    /** 0 at {@code lo} blocks from the camera or closer, 1 from {@code hi}: what keeps the view clear up close. */
    static float fade(Vec3 cam, Vec3 p, double lo, double hi) {
        return (float) smooth(lo, hi, cam.distanceTo(p));
    }

    /** Two unit vectors square to the unit vector {@code n} (and to each other). */
    static Vec3[] basis(Vec3 n) {
        Vec3 a = n.cross(new Vec3(0, 1, 0));
        if (a.lengthSqr() < 1e-6) a = n.cross(new Vec3(1, 0, 0));
        a = a.normalize();
        return new Vec3[] {a, n.cross(a).normalize()};
    }

    static Vec3 randomUnit(RandomSource r) {
        double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, k = Math.sqrt(1 - z * z);
        return new Vec3(Math.cos(a) * k, z, Math.sin(a) * k);
    }

    static int argb(float alpha, int rgb) {
        return (int) (Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
    }

    static int lerpColor(int a, int b, float k) {
        k = Mth.clamp(k, 0, 1);
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }
}
