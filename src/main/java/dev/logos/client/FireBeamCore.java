package dev.logos.client;

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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * What the fire beam and its wake share: the strip materials ({@code fire_beam_flame.fsh}) and a
 * camera-facing strip whose centre wanders and whose width swells along the line, so the flame has a
 * moving, ragged silhouette instead of two straight edges. Every vertex is written in full and only
 * submitted through the caller's (ShaderPackOverlay) collector.
 */
final class FireBeamCore {
    /** Additive turbulent flame. */
    static final RenderType FLAME = strip("flame", "FIRE_FLAME", "fire_ray/fire_trail", BlendFunction.LIGHTNING);
    /** The same flame alpha-blended: the beam's coloured body, which stays orange against a bright sky. */
    static final RenderType FLAME_BODY = strip("flame_body", "FIRE_FLAME", "fire_ray/fire_trail", BlendFunction.TRANSLUCENT);
    /** Additive white-gold heart. */
    static final RenderType CORE = strip("core", "FIRE_CORE", "fire_ray/fire_trail", BlendFunction.LIGHTNING);
    /** Alpha-blended dark-red soot around the flame. */
    static final RenderType SHEATH = strip("sheath", "FIRE_SHEATH", "fire_ray/smoke_trail", BlendFunction.TRANSLUCENT);
    /** Alpha-blended smoke band of the wake. */
    static final RenderType SMOKE = strip("smoke", "FIRE_SMOKE", "fire_ray/smoke_trail", BlendFunction.TRANSLUCENT);
    /** Additive smouldering glow of the wake. */
    static final RenderType EMBER = strip("ember", "FIRE_EMBER", "fire_ray/fire_trail", BlendFunction.LIGHTNING);
    /** Additive soft sprite. */
    static final RenderType GLOW = glow();

    private FireBeamCore() {}

    /** How a strip runs along the line, {@code s} blocks from its start. */
    interface Profile {
        /** Half width in blocks. */
        double width(double s);
        /** Where the strip's centre sits off the straight line. */
        Vec3 offset(double s);
        /** How much of the layer shows (0–1). */
        float alpha(double s);
    }

    /**
     * A camera-facing strip from {@code start} to {@code end} blocks along {@code dir} from {@code from},
     * {@code perBlock} segments a block. U runs in blocks / 4 plus {@code seed}; V across.
     */
    static void strip(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 from, Vec3 dir, double start, double end,
            int perBlock, float seed, float r, float g, float b, Profile profile) {
        if (end - start < 1e-3) return;
        int n = Mth.clamp((int) Math.ceil((end - start) * perBlock), 4, 256);
        Vec3 prevA = null, prevB = null;
        float prevU = 0, prevAlpha = 0;
        for (int i = 0; i <= n; i++) {
            double s = start + (end - start) * i / n;
            Vec3 p = from.add(dir.scale(s)).add(profile.offset(s));
            Vec3 side = side(dir, p, eye).scale(Math.max(0, profile.width(s)));
            float a = profile.alpha(s);
            Vec3 pa = p.add(side), pb = p.subtract(side);
            float u = (float) (s / 4) + seed;
            if (prevA != null && (a > 0 || prevAlpha > 0)) {
                vertex(pose, buf, prevA, prevU, 0, r, g, b, prevAlpha);
                vertex(pose, buf, prevB, prevU, 1, r, g, b, prevAlpha);
                vertex(pose, buf, pb, u, 1, r, g, b, a);
                vertex(pose, buf, pa, u, 0, r, g, b, a);
            }
            prevA = pa;
            prevB = pb;
            prevU = u;
            prevAlpha = a;
        }
    }

    /** A camera-facing square sprite of half size {@code size}. */
    static void sprite(PoseStack.Pose pose, VertexConsumer buf, CameraRenderState camera, Vec3 at, float size,
            float r, float g, float b, float a) {
        if (a <= 0 || size <= 0) return;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(size);
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(size);
        vertex(pose, buf, at.subtract(right).add(up), 0, 0, r, g, b, a);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1, r, g, b, a);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1, r, g, b, a);
        vertex(pose, buf, at.add(right).add(up), 1, 0, r, g, b, a);
    }

    /** Unit vector across the line at {@code p}, square to both the line and the view. */
    static Vec3 side(Vec3 dir, Vec3 p, Vec3 eye) {
        Vec3 s = dir.cross(eye.subtract(p));
        if (s.lengthSqr() < 1e-8) s = dir.cross(new Vec3(0, 1, 0));
        if (s.lengthSqr() < 1e-8) s = new Vec3(1, 0, 0);
        return s.normalize();
    }

    /** Two unit vectors square to {@code dir} (the second as close to world up as it can be). */
    static Vec3[] frame(Vec3 dir) {
        Vec3 right = dir.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        return new Vec3[] {right, right.cross(dir).normalize()};
    }

    /** How much of something at {@code p} to show this close to the camera: nothing within {@code near}. */
    static float nearFade(Vec3 eye, Vec3 p, double near, double far) {
        return (float) Mth.clamp((eye.distanceTo(p) - near) / (far - near), 0, 1);
    }

    /** Seen down the line (as the caster sees it) the far strip piles up into one spot; thin it there. */
    static float endOn(Vec3 eye, Vec3 p, Vec3 dir) {
        Vec3 view = p.subtract(eye);
        double d = view.length();
        if (d < 1e-4) return 1;
        double k = Mth.clamp((Math.abs(view.dot(dir)) / d - .9) / .1, 0, 1);
        return (float) (1 - .65 * k * k);
    }

    static float smooth(float k) {
        k = Mth.clamp(k, 0, 1);
        return k * k * (3 - 2 * k);
    }

    static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, float r, float g, float b, float a) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(Mth.clamp(r, 0, 1), Mth.clamp(g, 0, 1), Mth.clamp(b, 0, 1), Mth.clamp(a, 0, 1))
                .setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType strip(String name, String define, String texture, BlendFunction blend) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/fire_beam_" + name)).withFragmentShader(id("core/fire_beam_flame"))
                .withShaderDefine(define).withSampler("NoiseTex").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(blend)).build();
        return RenderType.create("logos_fire_beam_" + name, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/" + texture + ".png"))
                .withTexture("NoiseTex", id("textures/vfx/fire_ray/noise.png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType glow() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/fire_beam_glow")).withFragmentShader(id("core/fire_beam_glow"))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
        return RenderType.create("logos_fire_beam_glow", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/fire_ray/glow.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
