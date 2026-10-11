package dev.logos.client;

import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Comet-inspired rose-violet orb, two flowing wakes and a trail of soft glints. */
final class ArcaneProjectileVisual implements ProjectileVisual {
    private static final RenderType BODY = sprite("glow", false);
    private static final RenderType HALO = sprite("glow", true);
    private static final RenderType OUTER = wakeMaterial(false);
    private static final RenderType INNER = wakeMaterial(true);
    private final ProjectileMotion motion;
    private final ProjectilePath path;
    private Vec3 heading = new Vec3(0, 0, 1);
    private float scale;

    ArcaneProjectileVisual(Vec3 position, double now) {
        motion = new ProjectileMotion(position, now);
        path = new ProjectilePath(position);
    }

    @Override
    public void move(Vec3 position, Vec3 heading, float size, double now) {
        motion.move(position, now);
        path.move(position);
        if (heading.lengthSqr() > 1e-6) this.heading = heading.normalize();
        scale = particleScale(size, false);
    }

    @Override
    public float particleScale(float size, boolean customLook) {
        return Math.min(1.25f, Math.max(.6f, size / .3f));
    }

    @Override
    public void submit(CameraRenderState camera, double now, PoseStack stack, SubmitNodeCollector out, boolean head, boolean tail) {
        Vec3 origin = motion.sample(now);
        float size = scale;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (tail) {
            List<Vec3> samples = path.samples(origin, heading, 2.4 * size, 0);
            out.submitCustomGeometry(stack, OUTER, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size, false));
            out.submitCustomGeometry(stack, INNER, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size, true));
        }
        if (head) {
            Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
            Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
            out.submitCustomGeometry(stack, BODY, (pose, buf) -> billboard(pose, buf, origin, right, up, .26f * size, 0xFFE668EF));
            out.submitCustomGeometry(stack, HALO, (pose, buf) -> billboard(pose, buf, origin, right, up, .42f * size, 0x70CA38FF));
        }
        stack.popPose();
    }

    private static void billboard(PoseStack.Pose pose, VertexConsumer buf, Vec3 center, Vec3 right, Vec3 up, float radius, int color) {
        right = right.scale(radius);
        up = up.scale(radius);
        vertex(pose, buf, center.subtract(right).add(up), 0, 0, color);
        vertex(pose, buf, center.subtract(right).subtract(up), 0, 1, color);
        vertex(pose, buf, center.add(right).subtract(up), 1, 1, color);
        vertex(pose, buf, center.add(right).add(up), 1, 0, color);
    }

    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, List<Vec3> samples, Vec3 camera, float size, boolean inner) {
        Vec3[] points = new Vec3[17], sides = new Vec3[17];
        for (int i = 0; i <= 16; i++) points[i] = ProjectilePath.point(samples, i / 16f);
        for (int i = 0; i <= 16; i++) {
            Vec3 tangent = points[Math.min(16, i + 1)].subtract(points[Math.max(0, i - 1)]);
            Vec3 side = tangent.cross(camera.subtract(points[i]));
            sides[i] = (side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize())
                    .scale((inner ? .14 : .24) * size * (1 - .85 * i / 16f));
        }
        for (int i = 0; i < 16; i++) {
            float a = i / 16f, b = (i + 1) / 16f;
            int c0 = color(a, inner), c1 = color(b, inner);
            vertex(pose, buf, points[i].add(sides[i]), a, 0, c0);
            vertex(pose, buf, points[i].subtract(sides[i]), a, 1, c0);
            vertex(pose, buf, points[i + 1].subtract(sides[i + 1]), b, 1, c1);
            vertex(pose, buf, points[i + 1].add(sides[i + 1]), b, 0, c1);
        }
    }

    private static int color(float t, boolean inner) {
        int alpha = (int)((inner ? 175 : 235) * Math.pow(1 - t, .7));
        int red = (int)((inner ? 250 : 224) * (1 - t) + 130 * t);
        int green = inner ? 152 : 68;
        return alpha << 24 | red << 16 | green << 8 | 255;
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float u, float v, int color) {
        buf.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT);
    }

    private static RenderType sprite(String texture, boolean additive) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/arcane_" + texture + (additive ? "_glow" : "_body")))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (!additive) builder.withFragmentShader(id("core/arcane_head"));
        return RenderType.create("logos_arcane_" + texture + additive, RenderSetup.builder(builder.build())
                .withTexture("Sampler0", id("textures/vfx/arcane/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType wakeMaterial(boolean inner) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/arcane_trail_" + inner)).withFragmentShader(id("core/arcane_trail"))
                .withSampler("Sampler1").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(inner ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (inner) builder.withShaderDefine("COMET_INNER");
        return RenderType.create("logos_arcane_trail_" + inner, RenderSetup.builder(builder.build())
                .withTexture("Sampler0", id("textures/vfx/arcane/trail_" + (inner ? "inner" : "outer") + ".png"))
                .withTexture("Sampler1", id("textures/vfx/arcane/trail_mask.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    @Override
    public List<ParticleEmitter> particles(Motion motion) {
        ParticleEmitter stars = layer(Element.ARCANE, true, .05f, 0, size(.08f, .16f), range(0, .0175f), range(4, 12), sphere(.125f));
        configureTexture(stars, "arcane/star", true);
        stars.config.emission.setDistanceRate(NumberFunction.constant(3.5f));
        stars.config.setStartColor(NumberFunction.color(0xDCE6A0FF));
        stars.config.physics.setEnable(false);
        Curve bloom = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 0, .2f, 1, .55f, .7f, 1, 0));
        stars.config.sizeOverLifetime.setSize(new NumberFunction3(bloom, bloom, bloom));

        ParticleEmitter sparks = layer(Element.ARCANE, true, .05f, 0, size(.02f, .04f), range(0, .0125f), range(4, 13), sphere(.15f));
        configureTexture(sparks, "arcane/spark", true);
        sparks.config.emission.setDistanceRate(NumberFunction.constant(4));
        sparks.config.setStartColor(NumberFunction.color(0xDCFBB6F2));
        sparks.config.physics.setEnable(false);
        sparks.config.sizeOverLifetime.setSize(new NumberFunction3(bloom, bloom, bloom));

        ParticleEmitter glow = layer(Element.ARCANE, true, .05f, 0, size(.35f, .5f), range(0, 0), range(3, 4), sphere(.035f));
        configureTexture(glow, "arcane/glow", true);
        glow.config.emission.setDistanceRate(NumberFunction.constant(4));
        glow.config.setStartColor(NumberFunction.color(0x24D35DFF));
        glow.config.physics.setEnable(false);
        return List.of(stars, sparks, glow);
    }
}
