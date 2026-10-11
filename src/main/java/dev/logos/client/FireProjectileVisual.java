package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** QFX Ares-inspired flowing ribbons and a shared, smoothly moving head; Photon supplies embers. */
final class FireProjectileVisual {
    private static final RenderType FLAME = material(false);
    private static final RenderType NOISE = material(true);
    private static final RenderType CORE = headMaterial("ares_spark", true);
    private static final RenderType GLOW = headMaterial("ares_glow", true);
    private final List<Vec3> path = new ArrayList<>();
    private final ProjectileMotion motion;
    private Vec3 position;
    private Vec3 heading = new Vec3(0, 0, 1);
    private float scale;

    FireProjectileVisual(Vec3 position, double now) {
        this.position = position;
        motion = new ProjectileMotion(position, now);
    }

    void move(Vec3 position, Vec3 heading, float size, double now) {
        if (this.position.distanceToSqr(position) > 1e-6) {
            path.addFirst(this.position);
            if (path.size() > 14) path.removeLast();
        }
        motion.move(position, now);
        this.position = position;
        if (heading.lengthSqr() > 1e-6) this.heading = heading.normalize();
        scale = Math.max(.6f, size / .3f);
    }

    void submit(CameraRenderState camera, double now, PoseStack stack, SubmitNodeCollector out) {
        Vec3 origin = motion.sample(now);
        // Ares uses camera-facing particle trails, not a solid sphere or crossed flat flame cards.
        List<Vec3> samples = new ArrayList<>();
        samples.add(origin.add(heading.scale(.12 * scale)));
        Vec3 last = origin;
        double length = 0;
        for (Vec3 p : path) {
            // Retargeting can leave the newest reported point ahead of the interpolated head.
            if (p.subtract(origin).dot(heading) > 0) continue;
            double segment = p.distanceTo(last);
            if (segment < .05) continue;
            if (length + segment >= 1.8 * scale) {
                samples.add(last.lerp(p, (1.8 * scale - length) / segment));
                length = 1.8 * scale;
                break;
            }
            samples.add(p);
            length += segment;
            last = p;
        }
        if (length < 1.8 * scale) samples.add(last.subtract(heading.scale(1.8 * scale - length)));
        float size = scale;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(stack, FLAME, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size, false));
        out.submitCustomGeometry(stack, NOISE, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size, true));
        Vec3 right = new Vec3(camera.orientation.transform(new org.joml.Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new org.joml.Vector3f(0, 1, 0)));
        out.submitCustomGeometry(stack, GLOW, (pose, buf) -> head(pose, buf, origin, right, up, .44f * size, 0x60FF850A));
        out.submitCustomGeometry(stack, CORE, (pose, buf) -> head(pose, buf, origin, right, up, .09f * size, 0xFFFFD778));
        stack.popPose();
    }

    private static void head(PoseStack.Pose pose, VertexConsumer buf, Vec3 center, Vec3 right, Vec3 up,
            float radius, int color) {
        right = right.scale(radius);
        up = up.scale(radius);
        vertex(pose, buf, center.subtract(right).add(up), 0, 0, color);
        vertex(pose, buf, center.subtract(right).subtract(up), 0, 1, color);
        vertex(pose, buf, center.add(right).subtract(up), 1, 1, color);
        vertex(pose, buf, center.add(right).add(up), 1, 0, color);
    }

    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, List<Vec3> samples, Vec3 camera,
            float size, boolean smoke) {
        Vec3[] points = new Vec3[17], edges = new Vec3[17];
        for (int j = 0; j <= 16; j++) points[j] = point(samples, j / 16f);
        for (int j = 0; j <= 16; j++) {
            Vec3 tangent = points[Math.min(16, j + 1)].subtract(points[Math.max(0, j - 1)]);
            Vec3 side = tangent.cross(camera.subtract(points[j]));
            side = side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
            float t = j / 16f;
            edges[j] = side.scale(size * (smoke ? .43 : .3) * (1 - .72 * t));
        }
        for (int j = 0; j < 16; j++) {
            float t0 = j / 16f, t1 = (j + 1) / 16f;
            int c0 = color(t0, smoke), c1 = color(t1, smoke);
            vertex(pose, buf, points[j].add(edges[j]), t0, 0, c0);
            vertex(pose, buf, points[j].subtract(edges[j]), t0, 1, c0);
            vertex(pose, buf, points[j+1].subtract(edges[j+1]), t1, 1, c1);
            vertex(pose, buf, points[j+1].add(edges[j+1]), t1, 0, c1);
        }
    }

    private static int color(float t, boolean smoke) {
        int alpha = (int) ((smoke ? 100 : 255) * Math.pow(1 - t, .65));
        // A colored flame body retains orange against a bright sky; only the turbulence adds light.
        return alpha << 24 | (smoke ? 0xFF4000 : 0xFF6905);
    }

    private static Vec3 point(List<Vec3> points, float t) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += points.get(i).distanceTo(points.get(i - 1));
        double distance = total * t;
        for (int i = 1; i < points.size(); i++) {
            double segment = points.get(i).distanceTo(points.get(i - 1));
            if (distance <= segment) return points.get(i - 1).lerp(points.get(i), segment < 1e-6 ? 0 : distance / segment);
            distance -= segment;
        }
        return points.getLast();
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT);
    }

    private static RenderType material(boolean noise) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("logos", "pipeline/fire_" + (noise ? "noise" : "trail")))
                .withFragmentShader(Identifier.fromNamespaceAndPath("logos", "core/fire_trail"))
                .withSampler("Sampler1").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(noise ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (noise) builder.withShaderDefine("ARES_NOISE");
        return RenderType.create("logos_fire_" + (noise ? "noise" : "trail"), RenderSetup.builder(builder.build())
                .withTexture("Sampler0", texture(noise ? "ares_noise" : "ares_trail"))
                .withTexture("Sampler1", texture("ares_mask")).sortOnUpload().createRenderSetup());
    }

    private static RenderType headMaterial(String texture, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("logos", "pipeline/fire_" + texture))
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_fire_" + texture, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture(texture)).sortOnUpload().createRenderSetup());
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("logos", "textures/vfx/fire/" + name + ".png");
    }
}
