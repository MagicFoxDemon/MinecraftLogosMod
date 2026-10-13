package dev.logos.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** UltimateBeam's soft laser core, sparse winding trails and fat-dot accents, all world-depth tested. */
final class FireBeamCore {
    private static final RenderType CORE = material("laser", "LASER_CORE");
    private static final RenderType CONE = material("cone", "CONE_GLOW");
    private static final RenderType SPIRAL = material("spiral", "WINDING_TRAIL");
    private static final RenderType DOT = material("dot", "FAT_DOT");

    private FireBeamCore() {}

    /** Called with the same ShaderPackOverlay collector as the beam's other custom geometry. */
    static void submit(Vec3 from, Vec3 to, float radius, double now, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < .05) return;
        Vec3 axis = delta.scale(1 / length);
        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = axis.cross(right).normalize();
        Vec3 horizontal = right;
        // The source laser is a single gradient strip, with no noisy opacity or broken centre.
        out.submitCustomGeometry(stack, CORE, (pose, buf) -> strip(pose, buf, from, to, camera.pos,
                radius * .95f, radius * .95f, 0xFFFFFFFF));
        out.submitCustomGeometry(stack, CONE, (pose, buf) -> {
            // Two faint travelling cones give breathing motion without a cloud of overlapping quads.
            for (int i = 0; i < 2; i++) {
                double t = fract(now * .012 + i * .5);
                double reach = Math.min(length * .2, radius * 2.5);
                Vec3 a = from.add(axis.scale(t * Math.max(0, length - reach)));
                Vec3 b = a.add(axis.scale(reach));
                int alpha = (int)(45 * Math.sin(t * Math.PI));
                strip(pose, buf, a, b, camera.pos, radius * .12f, radius * .85f, alpha << 24 | 0xFFB63B);
            }
        });
        out.submitCustomGeometry(stack, SPIRAL, (pose, buf) -> {
            helix(pose, buf, from, axis, horizontal, up, length, radius, now * .045, camera.pos, 0);
            helix(pose, buf, from, axis, horizontal, up, length, radius, now * .045, camera.pos, Math.PI);
        });
        out.submitCustomGeometry(stack, DOT, (pose, buf) -> {
            Vec3 screenRight = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
            Vec3 screenUp = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
            int count = Math.min(18, Math.max(3, (int)(length * 1.2)));
            for (int i = 0; i < count; i++) {
                double t = fract(now * .018 + i * .61803398875);
                double a = i * 2.399963 + now * .025;
                double scatter = radius * (.45 + .28 * Math.sin(i * 1.7));
                Vec3 at = from.add(delta.scale(.06 + .88 * t))
                        .add(horizontal.scale(Math.cos(a) * scatter)).add(up.scale(Math.sin(a) * scatter));
                float r = radius * (.045f + .02f * (i % 3));
                int alpha = (int)(190 * Math.pow(Math.sin(t * Math.PI), .65));
                quad(pose, buf, at, screenRight.scale(r), screenUp.scale(r), alpha << 24 | 0xFFE6A0);
            }
        });
    }

    private static void helix(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 axis, Vec3 right, Vec3 up,
            double length, float radius, double time, Vec3 camera, double phase) {
        int segments = Math.min(192, Math.max(32, (int)(length * 14)));
        for (int i = 0; i < segments; i++) {
            double t0 = (double)i / segments, t1 = (double)(i + 1) / segments;
            Vec3 a = helixPoint(from, axis, right, up, length, radius, time, phase, t0);
            Vec3 b = helixPoint(from, axis, right, up, length, radius, time, phase, t1);
            Vec3 side = facingSide(b.subtract(a), camera.subtract(a.add(b).scale(.5))).scale(radius * .075);
            int c0 = windingColor(t0), c1 = windingColor(t1);
            float u0 = (float)(t0 * length / 2.8 - time * .13), u1 = (float)(t1 * length / 2.8 - time * .13);
            vertex(pose, buf, a.add(side), u0, 0, c0);
            vertex(pose, buf, a.subtract(side), u0, 1, c0);
            vertex(pose, buf, b.subtract(side), u1, 1, c1);
            vertex(pose, buf, b.add(side), u1, 0, c1);
        }
    }

    private static Vec3 helixPoint(Vec3 from, Vec3 axis, Vec3 right, Vec3 up, double length,
            float radius, double time, double phase, double t) {
        double a = t * length * Math.PI * 2 / 3.8 - time + phase;
        double taper = Math.min(1, t * length / .8) * Math.min(1, (1 - t) * length / .65);
        double r = radius * .8 * taper;
        return from.add(axis.scale(t * length)).add(right.scale(Math.cos(a) * r)).add(up.scale(Math.sin(a) * r));
    }

    private static int windingColor(double t) {
        int alpha = (int)(145 * Math.min(1, t * 9) * Math.min(1, (1 - t) * 9));
        return alpha << 24 | 0xFFD97A;
    }

    private static void strip(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 camera,
            float startWidth, float endWidth, int color) {
        Vec3 axis = b.subtract(a);
        Vec3 side = facingSide(axis, camera.subtract(a.add(b).scale(.5)));
        // Only the muzzle narrows. The long edge stays straight so the core reads as a ray.
        for (int i = 0; i < 24; i++) {
            float t0 = i / 24f, t1 = (i + 1) / 24f;
            float w0 = (startWidth + (endWidth - startWidth) * t0) * muzzle(t0, axis.length());
            float w1 = (startWidth + (endWidth - startWidth) * t1) * muzzle(t1, axis.length());
            Vec3 p0 = a.add(axis.scale(t0)), p1 = a.add(axis.scale(t1));
            vertex(pose, buf, p0.add(side.scale(w0)), t0, 0, color);
            vertex(pose, buf, p0.subtract(side.scale(w0)), t0, 1, color);
            vertex(pose, buf, p1.subtract(side.scale(w1)), t1, 1, color);
            vertex(pose, buf, p1.add(side.scale(w1)), t1, 0, color);
        }
    }

    private static float muzzle(float t, double len) {
        float s = (float)Math.min(1, t * len / .65);
        return .22f + .78f * s * s * (3 - 2 * s);
    }

    private static Vec3 facingSide(Vec3 axis, Vec3 view) {
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        return side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Vec3 right, Vec3 up, int color) {
        vertex(pose, buf, at.subtract(right).add(up), 0, 0, color);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1, color);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1, color);
        vertex(pose, buf, at.add(right).add(up), 1, 0, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static double fract(double n) { return n - Math.floor(n); }

    private static RenderType material(String texture, String define) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/fire_beam_detail_" + texture)).withFragmentShader(id("core/fire_beam_detail"))
                .withShaderDefine(define).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_fire_beam_" + texture, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/fire_beam/" + texture + ".png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
