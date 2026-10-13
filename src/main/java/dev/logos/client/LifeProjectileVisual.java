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

/** Nature's yellow-green light, flowing wake, tumbling leaves and soft cyan mist. */
final class LifeProjectileVisual implements ProjectileVisual {
    private static final RenderType CORE = sprite("nature_core", false);
    private static final RenderType FLARE = sprite("nature_flare", true);
    private static final RenderType GLOW = sprite("nature_glow", true);
    private static final RenderType WAKE = wakeMaterial();
    private final ProjectileMotion motion;
    private final ProjectilePath path;
    private Vec3 heading = new Vec3(0, 0, 1);
    private float scale;

    LifeProjectileVisual(Vec3 position, double now) {
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
            List<Vec3> points = path.samples(origin, heading, 2.2 * size, 0);
            out.submitCustomGeometry(stack, WAKE, (pose, buf) -> ribbon(pose, buf, points, camera.pos, size));
        }
        if (head) {
            Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
            Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
            float pulse = 1 + .045f * (float)Math.sin(now * .18);
            out.submitCustomGeometry(stack, GLOW, (pose, buf) -> billboard(pose, buf, origin, right, up,
                    .36f * size, 0x4867E894));
            float roll = (float)(now * .025);
            Vec3 flareRight = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
            Vec3 flareUp = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll)));
            out.submitCustomGeometry(stack, FLARE, (pose, buf) -> billboard(pose, buf, origin, flareRight, flareUp,
                    .24f * size * pulse, 0xC0D5FF3B));
            out.submitCustomGeometry(stack, CORE, (pose, buf) -> billboard(pose, buf, origin, right, up,
                    .12f * size * pulse, 0xFFD5FF43));
            out.submitCustomGeometry(stack, CORE, (pose, buf) -> billboard(pose, buf, origin, right, up,
                    .045f * size, 0xF0F2FFD8));
        }
        stack.popPose();
    }

    private static void billboard(PoseStack.Pose pose, VertexConsumer buf, Vec3 center, Vec3 right, Vec3 up,
            float radius, int color) {
        right = right.scale(radius);
        up = up.scale(radius);
        vertex(pose, buf, center.subtract(right).add(up), 0, 0, color);
        vertex(pose, buf, center.subtract(right).subtract(up), 0, 1, color);
        vertex(pose, buf, center.add(right).subtract(up), 1, 1, color);
        vertex(pose, buf, center.add(right).add(up), 1, 0, color);
    }

    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, List<Vec3> samples, Vec3 camera, float size) {
        Vec3[] points = new Vec3[21], sides = new Vec3[21];
        for (int i = 0; i <= 20; i++) points[i] = ProjectilePath.point(samples, i / 20f);
        for (int i = 0; i <= 20; i++) {
            Vec3 tangent = points[Math.min(20, i + 1)].subtract(points[Math.max(0, i - 1)]);
            Vec3 side = tangent.cross(camera.subtract(points[i]));
            sides[i] = (side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize())
                    .scale(.16 * size * (1 - .95 * i / 20f));
        }
        for (int i = 0; i < 20; i++) {
            float a = i / 20f, b = (i + 1) / 20f;
            int ca = (int)(195 * Math.pow(1 - a, .8)) << 24 | 0xB9FF43;
            int cb = (int)(195 * Math.pow(1 - b, .8)) << 24 | 0xB9FF43;
            vertex(pose, buf, points[i].add(sides[i]), a, 0, ca);
            vertex(pose, buf, points[i].subtract(sides[i]), a, 1, ca);
            vertex(pose, buf, points[i + 1].subtract(sides[i + 1]), b, 1, cb);
            vertex(pose, buf, points[i + 1].add(sides[i + 1]), b, 0, cb);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(color).setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType sprite(String texture, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/life_" + texture)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_life_" + texture, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/life/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType wakeMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/life_trail")).withFragmentShader(id("core/life_trail"))
                .withSampler("Sampler1").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_life_trail", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/life/nature_trail.png"))
                .withTexture("Sampler1", id("textures/vfx/life/nature_mask.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    @Override
    public List<ParticleEmitter> particles(Motion motion) {
        ParticleEmitter leaves = layer(Element.LIFE, false, .05f, 0, size(.12f, .19f), range(0, .035f), range(5, 14), sphere(.13f));
        configureTexture(leaves, "life/nature_leaf", false);
        leaves.config.emission.setDistanceRate(NumberFunction.constant(4));
        leaves.config.setStartColor(NumberFunction.color(0xE08DFF68));
        leaves.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        leaves.config.rotationOverLifetime.setEnable(true);
        leaves.config.rotationOverLifetime.setRoll(range(-180, 180));
        leaves.config.physics.setEnable(false);

        ParticleEmitter sparks = layer(Element.LIFE, true, .05f, 0, size(.02f, .045f), range(0, .04f), range(4, 16), sphere(.14f));
        configureTexture(sparks, "life/nature_core", true);
        sparks.config.emission.setDistanceRate(NumberFunction.constant(4.5f));
        sparks.config.setStartColor(NumberFunction.color(0xD0DAFF8A));
        sparks.config.physics.setEnable(false);

        ParticleEmitter mist = layer(Element.LIFE, false, .05f, 0, size(.3f, .45f), range(0, .008f), range(6, 8), sphere(.06f));
        configureTexture(mist, "life/nature_mist", false);
        mist.config.emission.setDistanceRate(NumberFunction.constant(2.5f));
        mist.config.setStartColor(NumberFunction.color(0x2451DDB2));
        mist.config.shape.setPosition(new NumberFunction3(0, 0, -.2f));
        mist.config.physics.setEnable(false);
        mist.config.uvAnimation.setEnable(true);
        mist.config.uvAnimation.setTiles(new org.joml.Vector2i(5, 5));
        mist.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return List.of(leaves, sparks, mist);
    }
}
