package dev.logos.client;

import java.util.List;
import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;


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
final class FireProjectileVisual implements ProjectileVisual {
    private static final RenderType FLAME = material(false);
    private static final RenderType NOISE = material(true);
    private static final RenderType CORE = headMaterial("ares_spark", true);
    private static final RenderType GLOW = headMaterial("ares_glow", true);
    private final ProjectilePath path;
    private final ProjectileMotion motion;
    private Vec3 heading = new Vec3(0, 0, 1);
    private float scale;

    FireProjectileVisual(Vec3 position, double now) {
        path = new ProjectilePath(position);
        motion = new ProjectileMotion(position, now);
    }

    @Override
    public void move(Vec3 position, Vec3 heading, float size, double now) {
        path.move(position);
        motion.move(position, now);
        if (heading.lengthSqr() > 1e-6) this.heading = heading.normalize();
        scale = Math.max(.6f, size / .3f);
    }

    @Override
    public void submit(CameraRenderState camera, double now, PoseStack stack, SubmitNodeCollector out, boolean head, boolean tail) {
        Vec3 origin = motion.sample(now);
        // Ares uses camera-facing particle trails, not a solid sphere or crossed flat flame cards.
        List<Vec3> samples = path.samples(origin, heading, 1.8 * scale, .12 * scale);
        float size = scale;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (tail) {
            out.submitCustomGeometry(stack, FLAME, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size, false));
            out.submitCustomGeometry(stack, NOISE, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size, true));
        }
        Vec3 right = new Vec3(camera.orientation.transform(new org.joml.Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new org.joml.Vector3f(0, 1, 0)));
        if (head) {
            out.submitCustomGeometry(stack, GLOW, (pose, buf) -> head(pose, buf, origin, right, up, .66f * size, 0x60FF850A));
            out.submitCustomGeometry(stack, CORE, (pose, buf) -> head(pose, buf, origin, right, up, .135f * size, 0xFFFFD778));
        }
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
        for (int j = 0; j <= 16; j++) points[j] = ProjectilePath.point(samples, j / 16f);
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

    @Override
    public List<ParticleEmitter> particles(Motion motion) {
        ParticleEmitter sparks = layer(Element.FIRE, true, .1f, 0, size(.035f, .065f), range(0, .05f), range(4, 14), sphere(.15f));
        configureTexture(sparks, "fire/ares_spark", true);
        sparks.config.emission.setDistanceRate(NumberFunction.constant(3));
        sparks.config.setStartColor(NumberFunction.color(0xFFFFA218));
        sparks.config.physics.setGravity(NumberFunction.constant(.005f));

        ParticleEmitter stars = layer(Element.FIRE, true, .05f, 0, size(.09f, .18f), range(0, .035f), range(4, 10), sphere(.2f));
        configureTexture(stars, "fire/ares_star", true);
        stars.config.emission.setDistanceRate(NumberFunction.constant(1));
        stars.config.setStartColor(NumberFunction.color(0xC0FFD578));
        stars.config.physics.setEnable(false);

        ParticleEmitter smoke = layer(Element.FIRE, false, .05f, 0, size(.3f, .45f), range(0, .01f), range(6, 8), sphere(.1f));
        configureTexture(smoke, "fire/ares_smoke", true);
        smoke.config.emission.setDistanceRate(NumberFunction.constant(2));
        smoke.config.physics.setEnable(false);
        smoke.config.shape.setPosition(new NumberFunction3(0, 0, -.3f));
        smoke.config.setStartColor(NumberFunction.color(0x30FF690A));
        smoke.config.uvAnimation.setEnable(true);
        smoke.config.uvAnimation.setTiles(new org.joml.Vector2i(5, 5));
        smoke.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return List.of(sparks, stars, smoke);
    }

    @Override
    public float particleScale(float size, boolean customLook) { return Math.max(.6f, size / .3f); }
}
