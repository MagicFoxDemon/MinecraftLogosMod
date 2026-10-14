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

/** WaterRay's streaming water texture and continuously renewed splash caps, tinted slightly toward icy white. */
final class FrostBeamVisual {
    private static final RenderType WATER = material("water"), CAP = material("cap"), CORE = material("core"), CRYSTALS = material("crystals");
    private static final FX OUTLET = effect(false), TERMINUS = effect(true);
    private FXRuntime outlet, terminus;
    private Vec3 lastFrom;

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < .0025) return;
        Vec3 axis = delta.normalize();
        float scale = Math.max(.2f, radius / .5f);
        if (outlet == null) {
            outlet = emit(OUTLET, level, from, scale);
            terminus = emit(TERMINUS, level, to, scale);
        }
        locate(outlet, from, axis, scale);
        locate(terminus, to.subtract(axis.scale(.05)), axis.scale(-1), scale);
        lastFrom = from;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(stack, WATER, (pose, buf) -> cylinder(pose, buf, from, to, camera.pos, radius));
        out.submitCustomGeometry(stack, CORE, (pose, buf) -> band(pose, buf, from, to, camera.pos, radius * .75f));
        double time = level.getGameTime() + net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        out.submitCustomGeometry(stack, CRYSTALS, (pose, buf) -> crystals(pose, buf, from, to, radius, time));
        // Stable centres hide the strip ends while the individual splash particles grow and fade.
        out.submitCustomGeometry(stack, CAP, (pose, buf) -> {
            cap(pose, buf, from.add(axis.scale(.02)), camera, radius * .95f);
            cap(pose, buf, to.subtract(axis.scale(.07)), camera, radius * 1.6f);
        });
        stack.popPose();
    }

    void end() {
        if (outlet != null) { outlet.destroy(false); outlet = null; }
        if (terminus != null) { terminus.destroy(false); terminus = null; }
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
                splash("flip", 1.15f, 1.6f, .3f, 18, 26, 0xB0C6EFFF, 5, 4, false),
                splash("ring", 1.4f, 1.85f, .18f, 10, 16, 0xA0A3DCFF, 1, 1, false),
                splash("cross", 1.1f, 1.55f, .12f, 12, 20, 0x70DEF7FF, 1, 1, true),
                motes(true)) : List.of(
                splash("flip", .65f, .95f, .22f, 16, 24, 0xA8BCEAFF, 5, 4, false),
                splash("ring", .7f, 1.05f, .12f, 12, 18, 0x889DDCFF, 1, 1, false),
                splash("cross", .55f, .85f, .12f, 10, 18, 0x68D8F4FF, 1, 1, true),
                motes(false)));
        return fx;
    }

    private static ParticleEmitter splash(String texture, float min, float max, float rate, float lifeMin,
            float lifeMax, int color, int columns, int rows, boolean additive) {
        ParticleEmitter e = layer(Element.FROST, true, rate, 0, size(min, max), range(0, 0), range(lifeMin, lifeMax), sphere(0));
        ProjectileParticles.configureTexture(e, (texture.equals("cross") ? "fire_beam/" : "frost_beam/") + texture, additive);
        e.config.setSimulationSpace(ParticleConfig.Space.Local);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.physics.setEnable(false);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0xFFFFFFFF, 0xC0C8ECFF, 0x00B8E8FF)));
        Curve grow = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .65f, .18f, .9f, .65f, 1, 1, 1.25f));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(grow, grow, grow));
        e.config.setMaxParticles(24);
        if (columns > 1 || rows > 1) {
            e.config.uvAnimation.setEnable(true);
            e.config.uvAnimation.setTiles(new Vector2i(columns, rows));
            e.config.uvAnimation.setStartFrame(NumberFunction.constant(0));
            e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, columns * rows - 1, "age", "frame",
                    new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        }
        return e;
    }

    private static ParticleEmitter motes(boolean end) {
        ParticleEmitter e = layer(Element.FROST, true, end ? .45f : .18f, 0, size(.035f, .085f),
                range(.01f, .045f), range(10, 24), sphere(end ? .18f : .08f));
        ProjectileParticles.configureTexture(e, "frost_beam/bubble", true);
        e.config.setStartColor(NumberFunction.color(0xD8E1F8FF));
        e.config.physics.setEnable(false);
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        return e;
    }

    private static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius) {
        Vec3 axis = to.subtract(from);
        Vec3 side = axis.cross(camera.subtract(from.add(to).scale(.5)));
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1e-8) side = new Vec3(1, 0, 0);
        side = side.normalize();
        float repeat = (float)axis.length() / 2.8f;
        for (int i = 0; i < 32; i++) {
            float a = i / 32f, b = (i + 1) / 32f;
            Vec3 pa = from.add(axis.scale(a)), pb = from.add(axis.scale(b));
            Vec3 sa = side.scale(radius * width(a, axis.length())), sb = side.scale(radius * width(b, axis.length()));
            vertex(pose, buf, pa.add(sa), a * repeat, 0);
            vertex(pose, buf, pa.subtract(sa), a * repeat, 1);
            vertex(pose, buf, pb.subtract(sb), b * repeat, 1);
            vertex(pose, buf, pb.add(sb), b * repeat, 0);
        }
    }

    private static float width(float t, double length) {
        float s = (float)Math.min(1, t * length / .65);
        return .22f + .78f * s * s * (3 - 2 * s);
    }

    /** Camera-independent circular cross sections make the water texture wrap around a real shell. */
    private static void cylinder(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius) {
        Vec3 delta = to.subtract(from), axis = delta.normalize();
        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = axis.cross(right).normalize();
        double length = delta.length();
        for (int i = 0; i < 32; i++) {
            float t0 = i / 32f, t1 = (i + 1) / 32f;
            Vec3 p0 = from.add(delta.scale(t0)), p1 = from.add(delta.scale(t1));
            for (int j = 0; j < 24; j++) {
                double a = j * Math.PI / 12, b = (j + 1) * Math.PI / 12;
                Vec3 n0 = right.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
                Vec3 n1 = right.scale(Math.cos(b)).add(up.scale(Math.sin(b)));
                Vec3 a0 = p0.add(n0.scale(radius * width(t0, length))), a1 = p0.add(n1.scale(radius * width(t0, length)));
                Vec3 b0 = p1.add(n0.scale(radius * width(t1, length))), b1 = p1.add(n1.scale(radius * width(t1, length)));
                surfaceVertex(pose, buf, a0, n0, camera, t0 * (float)length / 2.8f, j / 12f);
                surfaceVertex(pose, buf, a1, n1, camera, t0 * (float)length / 2.8f, (j + 1) / 12f);
                surfaceVertex(pose, buf, b1, n1, camera, t1 * (float)length / 2.8f, (j + 1) / 12f);
                surfaceVertex(pose, buf, b0, n0, camera, t1 * (float)length / 2.8f, j / 12f);
            }
        }
    }

    private static void surfaceVertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Vec3 normal, Vec3 camera, float u, float v) {
        double facing = normal.dot(camera.subtract(at).normalize());
        int alpha = (int)(255 * (facing < 0 ? .16 : .35 + .65 * facing));
        double light = .65 + .35 * Math.max(0, normal.dot(new Vec3(.3, .8, -.4).normalize()));
        int shade = (int)(255 * light);
        int color = alpha << 24 | shade << 16 | shade << 8 | shade;
        buf.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
    }

    /** Small six-sided pointed crystals move around the beam in world space rather than facing the camera. */
    private static void crystals(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, float radius, double time) {
        Vec3 delta = to.subtract(from), axis = delta.normalize();
        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = axis.cross(right).normalize();
        int count = Math.min(28, Math.max(6, (int)(delta.length() * 1.7)));
        for (int i = 0; i < count; i++) {
            double cycle = time * .012 + i * .61803398875;
            double t = cycle - Math.floor(cycle);
            double angle = i * 2.399963 + time * .025;
            Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            Vec3 centre = from.add(delta.scale(.06 + .88 * t)).add(radial.scale(radius * (.8 + .35 * Math.sin(i * 1.7))));
            double size = radius * (.07 + .045 * (1 + Math.sin(i * 3.1)));
            Vec3 direction = axis.add(radial.scale(.45 * Math.sin(i * 2.1))).normalize();
            Vec3 x = direction.cross(up);
            if (x.lengthSqr() < 1e-8) x = right;
            x = x.normalize();
            Vec3 y = direction.cross(x).normalize();
            Vec3 tip = centre.add(direction.scale(size * 2.4)), tail = centre.subtract(direction.scale(size * 1.7));
            int alpha = (int)(180 * Math.min(1, t * 8) * Math.min(1, (1 - t) * 8));
            for (int face = 0; face < 6; face++) {
                double a = face * Math.PI / 3 + angle, b = (face + 1) * Math.PI / 3 + angle;
                Vec3 p = centre.add(x.scale(Math.cos(a) * size)).add(y.scale(Math.sin(a) * size));
                Vec3 q = centre.add(x.scale(Math.cos(b) * size)).add(y.scale(Math.sin(b) * size));
                int color = alpha << 24 | (face % 3 == 0 ? 0xF0FCFF : face % 3 == 1 ? 0x82B9E2 : 0xB8E8FF);
                crystalFace(pose, buf, tip, p, q, color);
                crystalFace(pose, buf, tail, q, p, color);
            }
        }
    }

    private static void crystalFace(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 c, int color) {
        Vec3 normal = b.subtract(a).cross(c.subtract(a)).normalize();
        for (Vec3 point : new Vec3[] {a, b, c, c})
            buf.addVertex(pose, (float)point.x, (float)point.y, (float)point.z).setColor(color).setUv(0, 0)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
    }

    private static void cap(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, CameraRenderState camera, float radius) {
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(radius);
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(radius);
        vertex(pose, buf, at.subtract(right).add(up), 0, 0);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1);
        vertex(pose, buf, at.add(right).add(up), 1, 0);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float u, float v) {
        buf.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(0xFFFFFFFF).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType material(String layer) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/frost_beam_" + layer))
                .withFragmentShader(id("core/frost_beam")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(layer.equals("crystals") ? BlendFunction.TRANSLUCENT : BlendFunction.LIGHTNING));
        if (layer.equals("cap")) pipeline.withShaderDefine("FROST_CAP");
        if (layer.equals("core")) pipeline.withShaderDefine("FROST_CORE");
        if (layer.equals("crystals")) pipeline.withShaderDefine("FROST_CRYSTALS");
        // Shared gradient_002 export already used by fire; no duplicate ice texture.
        String texture = layer.equals("core") ? "fire_beam/laser" : layer.equals("cap") ? "fire_beam/glow" : "frost_beam/water";
        return RenderType.create("logos_frost_beam", RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", id("textures/vfx/" + texture + ".png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
