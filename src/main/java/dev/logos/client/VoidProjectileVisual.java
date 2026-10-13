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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Curse's layered aura and filaments, recolored violet around a screen-projected starfield. */
final class VoidProjectileVisual implements ProjectileVisual {
    private static final RenderType HEAD = material("head", "curse_aura", false);
    private static final RenderType TRAIL = material("trail", "curse_trail", false);
    private static final RenderType FILAMENT = material("filament", "curse_filament", true);
    private static final RenderType GLOW = glowMaterial();
    private final ProjectileMotion motion;
    private final ProjectilePath path;
    private Vec3 heading = new Vec3(0, 0, 1);
    private float scale;

    VoidProjectileVisual(Vec3 position, double now) {
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
    public void submit(CameraRenderState camera, double now, PoseStack pose, SubmitNodeCollector out, boolean head, boolean tail) {
        Vec3 origin = motion.sample(now);
        float size = scale;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (tail) {
            List<Vec3> points = path.samples(origin, heading, 2.3 * size, 0);
            out.submitCustomGeometry(pose, TRAIL, (p, b) -> ribbon(p, b, points, camera.pos, size, now, -1));
            for (int strand = 0; strand < 3; strand++) {
                int index = strand;
                out.submitCustomGeometry(pose, FILAMENT, (p, b) -> ribbon(p, b, points, camera.pos, size, now, index));
            }
        }
        if (head) {
            Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(.36 * size);
            Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(.36 * size);
            out.submitCustomGeometry(pose, GLOW, (p, b) -> {
                vertex(p, b, origin.subtract(right).add(up), 0, 0, 0x409A28FF);
                vertex(p, b, origin.subtract(right).subtract(up), 0, 1, 0x409A28FF);
                vertex(p, b, origin.add(right).subtract(up), 1, 1, 0x409A28FF);
                vertex(p, b, origin.add(right).add(up), 1, 0, 0x409A28FF);
            });
            pose.translate(origin.x, origin.y, origin.z);
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), heading.toVector3f()));
            out.submitCustomGeometry(pose, HEAD, (p, b) -> shell(p, b, size));
        }
        pose.popPose();
    }

    /** A rounded, tapered volume keeps the starfield visible from every viewing direction. */
    private static void shell(PoseStack.Pose pose, VertexConsumer buf, float size) {
        for (int ring = 0; ring < 12; ring++) {
            float a = ring / 12f, b = (ring + 1) / 12f;
            for (int side = 0; side < 24; side++) {
                float u0 = side / 24f, u1 = (side + 1) / 24f;
                vertex(pose, buf, shellPoint(a, u0, size), a, u0, 0xFF9A28FF);
                vertex(pose, buf, shellPoint(b, u0, size), b, u0, 0xFF9A28FF);
                vertex(pose, buf, shellPoint(b, u1, size), b, u1, 0xFF9A28FF);
                vertex(pose, buf, shellPoint(a, u1, size), a, u1, 0xFF9A28FF);
            }
        }
    }

    private static Vec3 shellPoint(float t, float u, float size) {
        double radius = .20 * Math.pow(Math.sin(Math.PI * t), .65) * (1 - .3 * t) * size;
        double angle = Math.PI * 2 * u;
        return new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, (.16 - .72 * t) * size);
    }

    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, List<Vec3> samples, Vec3 camera,
            float size, double now, int strand) {
        Vec3[] points = new Vec3[25], sides = new Vec3[25];
        for (int i = 0; i <= 24; i++) {
            float t = i / 24f;
            Vec3 point = ProjectilePath.point(samples, t);
            Vec3 tangent = ProjectilePath.point(samples, Math.min(1, t + .02f))
                    .subtract(ProjectilePath.point(samples, Math.max(0, t - .02f)));
            Vec3 side = tangent.cross(camera.subtract(point));
            side = side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
            if (strand >= 0) {
                double wave = Math.sin(t * (8 + strand * 2.7) - now * (.09 + strand * .023) + strand * 2.094)
                        + .3 * Math.sin(t * 23 + now * .06 + strand);
                point = point.add(side.scale(wave * .10 * size * Math.sin(Math.PI * t)));
            }
            points[i] = point;
            sides[i] = side.scale(size * (strand < 0 ? .24 : .035) * (1 - .85 * t));
        }
        for (int i = 0; i < 24; i++) {
            float a = i / 24f, b = (i + 1) / 24f;
            int ca = (int)((strand < 0 ? 215 : 150) * Math.pow(1 - a, .7)) << 24 | 0x9A28FF;
            int cb = (int)((strand < 0 ? 215 : 150) * Math.pow(1 - b, .7)) << 24 | 0x9A28FF;
            vertex(pose, buf, points[i].add(sides[i]), a, 0, ca);
            vertex(pose, buf, points[i].subtract(sides[i]), a, 1, ca);
            vertex(pose, buf, points[i + 1].subtract(sides[i + 1]), b, 1, cb);
            vertex(pose, buf, points[i + 1].add(sides[i + 1]), b, 0, cb);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(color).setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType material(String name, String texture, boolean additive) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_" + name)).withVertexShader(id("core/void_projectile"))
                .withFragmentShader(id("core/void_projectile")).withCull(name.equals("head"))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (name.equals("head")) builder.withShaderDefine("VOID_HEAD");
        else builder.withSampler("Sampler1");
        if (!additive) builder.withSampler("Sampler2");
        if (additive) builder.withShaderDefine("VOID_FILAMENT");
        var setup = RenderSetup.builder(builder.build())
                .withTexture("Sampler0", id("textures/vfx/void/" + texture + ".png"));
        if (!name.equals("head")) setup.withTexture("Sampler1", id("textures/vfx/void/curse_mask.png"));
        if (!additive) setup.withTexture("Sampler2", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"));
        return RenderType.create("logos_void_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static RenderType glowMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/void_glow")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
        return RenderType.create("logos_void_glow", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/void/curse_glow.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    @Override
    public List<ParticleEmitter> particles(Motion motion) {
        ParticleEmitter sparks = layer(Element.VOID, true, .05f, 0, size(.025f, .045f), range(0, .025f), range(4, 12), sphere(.13f));
        configureTexture(sparks, "void/curse_spark", true);
        sparks.config.setStartColor(NumberFunction.color(0xD0D2A0FF));
        sparks.config.emission.setDistanceRate(NumberFunction.constant(3));
        sparks.config.physics.setEnable(false);

        ParticleEmitter mist = layer(Element.VOID, false, .05f, 0, size(.24f, .38f), range(0, .006f), range(5, 10), sphere(.08f));
        configureTexture(mist, "void/curse_mist", false);
        mist.config.setStartColor(NumberFunction.color(0x389A28FF));
        mist.config.emission.setDistanceRate(NumberFunction.constant(3));
        mist.config.shape.setPosition(new NumberFunction3(0, 0, -.25f));
        mist.config.physics.setEnable(false);
        mist.config.uvAnimation.setEnable(true);
        mist.config.uvAnimation.setTiles(new org.joml.Vector2i(5, 5));
        mist.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return List.of(sparks, mist);
    }
}
