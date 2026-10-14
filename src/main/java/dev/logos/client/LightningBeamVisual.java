package dev.logos.client;

import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;
import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;

/** ShockBlueRay's scrolling shock strip, crossing cyan ribbons, white discharges and blue outlet/impact caps. */
final class LightningBeamVisual {
    private static final RenderType SHOCK = material("shock", "lightning_beam/shock", "SHOCK_STRIP");
    private static final RenderType RUNIC = material("runic", "lightning_beam/runic", "RUNIC_RIBBON");
    private static final RenderType ARC = material("arc", "fire_beam/laser", "SHOCK_ARC");
    private static final RenderType CAP = material("cap", "fire_beam/glow", "SHOCK_CAP");
    private static final FX OUTLET = effect(false), TERMINUS = effect(true), BODY = bodyEffect();
    private final boolean defaultMotes;
    private FXRuntime outlet, terminus, body;
    private float bodyLength = -1;
    private Vec3 lastFrom;

    LightningBeamVisual(boolean defaultMotes) { this.defaultMotes = defaultMotes; }

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < .0025) return;
        Vec3 axis = delta.normalize();
        float scale = Math.max(.2f, radius / .5f), length = (float)delta.length();
        if (outlet == null) {
            outlet = emit(OUTLET, level, from, scale);
            terminus = emit(TERMINUS, level, to, scale);
            if (defaultMotes) body = emit(BODY, level, from.add(to).scale(.5), scale);
        }
        locate(outlet, from, axis, scale);
        locate(terminus, to.subtract(axis.scale(.04)), axis.scale(-1), scale);
        if (body != null) {
            locate(body, from.add(to).scale(.5), axis, scale);
            if (Math.abs(bodyLength - length) > .02f) {
                for (var object : body.objects.values()) if (object instanceof ParticleEmitter e) {
                    e.runtime().shape.scale.set(new NumberFunction3(.55f, length / scale, .55f));
                    e.runtime().emission.emissionRate.set(NumberFunction.constant(Math.min(10, length * .35f)));
                }
                bodyLength = length;
            }
        }
        lastFrom = from;
        double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(stack, SHOCK, (pose, buf) -> strip(pose, buf, from, to, camera.pos, radius * .65f));
        out.submitCustomGeometry(stack, RUNIC, (pose, buf) -> ribbons(pose, buf, from, to, camera.pos, radius, now, true));
        out.submitCustomGeometry(stack, ARC, (pose, buf) -> ribbons(pose, buf, from, to, camera.pos, radius, now, false));
        out.submitCustomGeometry(stack, CAP, (pose, buf) -> {
            cap(pose, buf, from.add(axis.scale(.02)), camera, radius * 1.1f);
            cap(pose, buf, to.subtract(axis.scale(.07)), camera, radius * 1.3f);
        });
        stack.popPose();
    }

    void end() {
        if (outlet != null) { outlet.destroy(false); outlet = null; }
        if (terminus != null) { terminus.destroy(false); terminus = null; }
        if (body != null) { body.destroy(false); body = null; }
    }

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    private static FXRuntime emit(FX template, Level level, Vec3 at, float scale) {
        FXRuntime runtime = template.createRuntime();
        for (var object : runtime.objects.values()) if (object instanceof ParticleEmitter e)
            e.config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
        locate(runtime, at, new Vec3(0, 1, 0), scale);
        runtime.emit(new Executor(level));
        return runtime;
    }

    private static void locate(FXRuntime fx, Vec3 at, Vec3 axis, float scale) {
        fx.getRoot().updatePos(new Vector3f((float)at.x, (float)at.y, (float)at.z));
        fx.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float)axis.x, (float)axis.y, (float)axis.z)));
        fx.getRoot().updateScale(new Vector3f(scale));
    }

    private static FX effect(boolean end) {
        FX fx = new FX();
        fx.getFxData().objects().addAll(end ? List.of(
                particle("lightning_beam/electric", .65f, 1.1f, .38f, 5, 9, 0xC01BD7FF, 4, 2),
                particle("lightning_beam/smoky", 1.0f, 1.5f, .22f, 8, 20, 0xC00EBEFF, 2, 2),
                particle("lightning_beam/rays", 1.3f, 1.7f, .18f, 5, 8, 0x7036BAFF, 1, 1)) : List.of(
                particle("fire_beam/glow", .9f, 1.15f, .12f, 12, 20, 0x600397D9, 1, 1),
                particle("lightning_beam/flare", .7f, 1.2f, .20f, 12, 24, 0x802EBEFF, 1, 1),
                particle("lightning_beam/electric", .35f, .65f, .22f, 4, 8, 0xA093EFFF, 4, 2)));
        return fx;
    }

    private static ParticleEmitter particle(String texture, float min, float max, float rate, float lifeMin,
            float lifeMax, int color, int columns, int rows) {
        ParticleEmitter e = layer(Element.LIGHTNING, true, rate, 0, size(min, max), range(0, 0), range(lifeMin, lifeMax), sphere(0));
        ProjectileParticles.configureTexture(e, texture, true);
        e.config.setSimulationSpace(ParticleConfig.Space.Local);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.physics.setEnable(false);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0xFFFFFFFF, 0xA0B5F4FF, 0x0000B8FF)));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(NumberFunction.constant(1), NumberFunction.constant(1), NumberFunction.constant(1)));
        e.config.setMaxParticles(64);
        if (columns > 1 || rows > 1) {
            e.config.uvAnimation.setEnable(true);
            e.config.uvAnimation.setTiles(new Vector2i(columns, rows));
            e.config.uvAnimation.setStartFrame(NumberFunction.constant(0));
            e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, columns * rows - 1, "age", "frame",
                    new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        }
        return e;
    }

    private static FX bodyEffect() {
        FX fx = new FX();
        ParticleEmitter e = particle("lightning_beam/electric", .18f, .42f, 1, 5, 10, 0xC0E0FFFF, 4, 2);
        e.config.shape.setShape(new Box());
        fx.getFxData().objects().add(e);
        return fx;
    }

    private static void strip(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius) {
        Vec3 delta = to.subtract(from), side = facing(delta, camera.subtract(from.add(to).scale(.5)));
        float repeat = (float)delta.length() / 4;
        for (int i = 0; i < 24; i++) {
            float a = i / 24f, b = (i + 1) / 24f;
            Vec3 p = from.add(delta.scale(a)), q = from.add(delta.scale(b));
            float wa = radius * muzzle(a, delta.length()), wb = radius * muzzle(b, delta.length());
            quad(pose, buf, p.add(side.scale(wa)), p.subtract(side.scale(wa)), q.subtract(side.scale(wb)), q.add(side.scale(wb)),
                    a * repeat, b * repeat, 0xFF57EFFF);
        }
    }

    private static void ribbons(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera,
            float radius, double now, boolean runic) {
        Vec3 delta = to.subtract(from), axis = delta.normalize();
        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = axis.cross(right).normalize();
        int segments = Math.min(96, Math.max(12, (int)(delta.length() * 2.5)));
        int strands = runic ? 4 : 3;
        long frame = (long)Math.floor(now / (runic ? 6 : 3));
        for (int strand = 0; strand < strands; strand++) {
            double seed = strand * 17.13 + frame * 3.77 + (runic ? 117 : 0);
            for (int i = 0; i < segments; i++) {
                float a = i / (float)segments, b = (i + 1) / (float)segments;
                Vec3 p = arcPoint(from, delta, right, up, radius, seed, i, a, runic);
                Vec3 q = arcPoint(from, delta, right, up, radius, seed, i + 1, b, runic);
                double width = radius * (runic ? .035 : .012);
                Vec3 side = facing(q.subtract(p), camera.subtract(p.add(q).scale(.5))).scale(width);
                int color = runic ? 0xC000D5F5 : strand == 0 ? 0xE0C9FFFF : 0xB000BFFF;
                quad(pose, buf, p.add(side), p.subtract(side), q.subtract(side), q.add(side), a * 3, b * 3, color);
            }
        }
    }

    private static Vec3 arcPoint(Vec3 from, Vec3 delta, Vec3 right, Vec3 up, float radius,
            double seed, int index, float t, boolean runic) {
        double fade = Math.min(1, t * 10) * Math.min(1, (1 - t) * 10);
        double spread = radius * (runic ? .75 : .55) * fade;
        return from.add(delta.scale(t)).add(right.scale(noise(seed + index * 1.31) * spread))
                .add(up.scale(noise(seed + index * 7.17 + 3) * spread));
    }

    private static double noise(double value) {
        double n = Math.sin(value * 127.1) * 43758.5453;
        return (n - Math.floor(n)) * 2 - 1;
    }

    private static Vec3 facing(Vec3 axis, Vec3 view) {
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        return side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
    }

    private static float muzzle(float t, double length) { return .25f + .75f * (float)Math.min(1, t * length / .55); }

    private static void cap(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, CameraRenderState camera, float radius) {
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(radius);
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(radius);
        quad(pose, buf, at.subtract(right).add(up), at.subtract(right).subtract(up),
                at.add(right).subtract(up), at.add(right).add(up), 0, 1, 0xFFFFFFFF);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float u0, float u1, int color) {
        vertex(pose, buf, a, u0, 0, color); vertex(pose, buf, b, u0, 1, color);
        vertex(pose, buf, c, u1, 1, color); vertex(pose, buf, d, u1, 0, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float u, float v, int color) {
        buf.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType material(String name, String texture, String define) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/lightning_beam_" + name)).withFragmentShader(id("core/lightning_beam"))
                .withShaderDefine(define).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING));
        if (name.equals("shock")) pipeline.withSampler("NoiseTex");
        if (name.equals("runic")) pipeline.withSampler("MaskTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/" + texture + ".png"));
        if (name.equals("shock")) setup.withTexture("NoiseTex", id("textures/vfx/lightning_beam/noise.png"));
        if (name.equals("runic")) setup.withTexture("MaskTex", id("textures/vfx/lightning_beam/runic_mask.png"));
        return RenderType.create("logos_lightning_beam_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
