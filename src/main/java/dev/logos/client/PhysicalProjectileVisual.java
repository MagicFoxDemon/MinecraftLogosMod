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

/** QFX Priest's hollow blade, white-gold wake and small feather-shaped flecks. */
final class PhysicalProjectileVisual implements ProjectileVisual {
    private static final RenderType BODY = sprite("head", false);
    private static final RenderType CORE = sprite("head", true);
    private static final RenderType GLOW = sprite("glow", true);
    private static final RenderType WAKE = wakeMaterial();
    private final ProjectileMotion motion;
    private final ProjectilePath path;
    private Vec3 heading = new Vec3(0, 0, 1);
    private float scale;

    PhysicalProjectileVisual(Vec3 position, double now) {
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
        Vec3 direction = heading;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (tail) {
            List<Vec3> samples = path.samples(origin, direction, 2.1 * size, 0);
            out.submitCustomGeometry(stack, WAKE, (pose, buf) -> ribbon(pose, buf, samples, camera.pos, size));
        }
        if (head) {
            Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
            Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
            Vec3 side = direction.cross(camera.pos.subtract(origin));
            side = side.lengthSqr() < 1e-8 ? right : side.normalize();
            Vec3 edge = side.scale(.19 * size);
            Vec3 tip = origin.add(direction.scale(.08 * size));
            Vec3 back = origin.subtract(direction.scale(.65 * size));
            out.submitCustomGeometry(stack, BODY, (pose, buf) -> card(pose, buf, tip, back, edge, 0xFFFFF3D4));
            out.submitCustomGeometry(stack, CORE, (pose, buf) -> card(pose, buf, tip, back, edge.scale(.72), 0xA0FFFFF5));
            out.submitCustomGeometry(stack, GLOW, (pose, buf) -> billboard(pose, buf, origin, right, up, .105f * size, 0, 1, 0x60FFF5D8));
        }
        stack.popPose();
    }

    private static void card(PoseStack.Pose pose, VertexConsumer buf, Vec3 tip, Vec3 back, Vec3 side, int color) {
        vertex(pose, buf, tip.add(side), 0, 0, color);
        vertex(pose, buf, tip.subtract(side), 0, 1, color);
        vertex(pose, buf, back.subtract(side), 1, 1, color);
        vertex(pose, buf, back.add(side), 1, 0, color);
    }

    private static void billboard(PoseStack.Pose pose, VertexConsumer buf, Vec3 center, Vec3 right, Vec3 up,
            float radius, float u0, float u1, int color) {
        right = right.scale(radius);
        up = up.scale(radius);
        vertex(pose, buf, center.subtract(right).add(up), u0, 0, color);
        vertex(pose, buf, center.subtract(right).subtract(up), u0, 1, color);
        vertex(pose, buf, center.add(right).subtract(up), u1, 1, color);
        vertex(pose, buf, center.add(right).add(up), u1, 0, color);
    }

    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, List<Vec3> samples, Vec3 camera, float size) {
        Vec3[] points = new Vec3[17], sides = new Vec3[17];
        for (int i = 0; i <= 16; i++) points[i] = ProjectilePath.point(samples, i / 16f);
        for (int i = 0; i <= 16; i++) {
            Vec3 tangent = points[Math.min(16, i + 1)].subtract(points[Math.max(0, i - 1)]);
            Vec3 side = tangent.cross(camera.subtract(points[i]));
            sides[i] = (side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize())
                    .scale(.13 * size * (1 - i / 16f));
        }
        for (int i = 0; i < 16; i++) {
            float a = i / 16f, b = (i + 1) / 16f;
            int c0 = (int)(230 * (1 - a)) << 24 | 0xFFF0C6;
            int c1 = (int)(230 * (1 - b)) << 24 | 0xFFF0C6;
            vertex(pose, buf, points[i].add(sides[i]), a, 0, c0);
            vertex(pose, buf, points[i].subtract(sides[i]), a, 1, c0);
            vertex(pose, buf, points[i + 1].subtract(sides[i + 1]), b, 1, c1);
            vertex(pose, buf, points[i + 1].add(sides[i + 1]), b, 0, c1);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float u, float v, int color) {
        buf.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType sprite(String texture, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/physical_" + texture + (additive ? "_glow" : "_body")))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_physical_" + texture + additive, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/physical/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType wakeMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/physical_trail")).withFragmentShader(id("core/physical_trail"))
                .withSampler("Sampler1").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_physical_trail", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/physical/trail_noise.png"))
                .withTexture("Sampler1", id("textures/vfx/physical/trail_mask.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    @Override
    public List<ParticleEmitter> particles(Motion motion) {
        ParticleEmitter feathers = layer(Element.PHYSICAL, true, .05f, 0, size(.08f, .14f), range(0, .025f), range(4, 15), sphere(.1f));
        configureTexture(feathers, "physical/feathers", true);
        feathers.config.emission.setDistanceRate(NumberFunction.constant(2));
        feathers.config.setStartColor(NumberFunction.color(0xBCFFF0C6));
        feathers.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        feathers.config.physics.setEnable(false);
        feathers.config.uvAnimation.setEnable(true);
        feathers.config.uvAnimation.setTiles(new org.joml.Vector2i(2, 1));
        feathers.config.uvAnimation.setStartFrame(range(0, 2));
        feathers.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        Curve expand = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .15f, .15f, .7f, .6f, 1, 1, .8f));
        feathers.config.sizeOverLifetime.setSize(new NumberFunction3(expand, expand, expand));

        ParticleEmitter sparks = layer(Element.PHYSICAL, true, .05f, 0, size(.015f, .025f), range(0, .05f), range(5, 16), sphere(.12f));
        configureTexture(sparks, "physical/spark", true);
        sparks.config.emission.setDistanceRate(NumberFunction.constant(3));
        sparks.config.setStartColor(NumberFunction.color(0xD8FFE9AE));
        sparks.config.physics.setEnable(false);

        ParticleEmitter stars = layer(Element.PHYSICAL, true, .05f, 0, size(.065f, .11f), range(0, .017f), range(6, 12), sphere(.12f));
        configureTexture(stars, "physical/star", true);
        stars.config.emission.setDistanceRate(NumberFunction.constant(1.5f));
        stars.config.setStartColor(NumberFunction.color(0xA0FFFFEB));
        stars.config.physics.setEnable(false);

        ParticleEmitter mist = layer(Element.PHYSICAL, false, .05f, 0, size(.3f, .45f), range(0, .012f), range(7, 9), sphere(.06f));
        configureTexture(mist, "physical/mist", false);
        mist.config.emission.setDistanceRate(NumberFunction.constant(3));
        mist.config.setStartColor(NumberFunction.color(0x0CFFF1D4));
        mist.config.physics.setEnable(false);
        mist.config.shape.setPosition(new NumberFunction3(0, 0, -.2f));
        mist.config.uvAnimation.setEnable(true);
        mist.config.uvAnimation.setTiles(new org.joml.Vector2i(5, 5));
        mist.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return List.of(feathers, sparks, stars, mist);
    }
}
