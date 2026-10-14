package dev.logos.client;

import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;

/**
 * The lasting sphere round one body while its spell shield holds, after Piloto's HoneyShield (Spiral_4: the
 * Sphere_InOut mesh under HoneyCombPattern_T with a fresnel rim, wound in by the Spiral bands) in a pale,
 * elementless white. Drawn as custom geometry rather than Photon: it must follow the body every frame, hug its
 * box as a slightly tall ellipsoid and show both faces through one sorted, depth-tested translucent pass.
 * It winds up over {@value #FORM} ticks, is not drawn from its own body's eyes, and breaks into honeycomb cells
 * (a short Photon effect) when the shield goes.
 */
final class WardSphereVisual {
    private static final int LAT = 20, LON = 40;
    /** Ticks for the sphere to wind up, and for its spiral bands to sweep past. */
    private static final int FORM = 16, BANDS = 22;
    /** A unit sphere, pole to pole: positions and (u, v) per grid point. */
    private static final float[][] UNIT = unitSphere();
    private static final RenderType SHELL = material(false), BAND = material(true);
    private static final FX BREAK = breakEffect();

    private int age;
    private Level level;
    private Vec3 center;
    private float across, up;

    /** Each client tick while the shield holds. */
    void tick(LivingEntity body) {
        age++;
        level = body.level();
        center = body.position().add(0, body.getBbHeight() / 2, 0);
        float[] r = radii(body);
        across = r[0];
        up = r[1];
    }

    /**
     * Each frame. {@code firstPerson} when {@code body} is the camera seen from its own eyes: the sphere
     * must not cloud that view, and may simply not be drawn.
     */
    void submit(LivingEntity body, CameraRenderState cam, float partialTick, PoseStack pose, SubmitNodeCollector out, boolean firstPerson) {
        if (firstPerson || age == 0) return;
        float t = age - 1 + partialTick;
        float formed = Math.min(1, t / FORM);
        float[] r = radii(body);
        // A little short of full size at first, settling outward with a slight overshoot.
        float grow = .72f + .28f * backOut(formed);
        float rx = r[0] * grow, ry = r[1] * grow;
        Vec3 c = body.getPosition(partialTick).add(0, body.getBbHeight() / 2, 0);
        Vec3 eye = cam.pos;
        // Standing inside someone's sphere: fade it rather than wrap the whole view in it.
        double inside = Math.sqrt(sq((eye.x - c.x) / rx) + sq((eye.y - c.y) / ry) + sq((eye.z - c.z) / rx));
        float near = (float) clamp((inside - 1.05) / .6, 0, 1);
        float alpha = near * smooth(Math.min(1, t / 6)) * (.92f + .08f * (float) Math.sin(t * .09));
        if (alpha <= .003f) return;
        pose.pushPose();
        pose.translate(c.x - eye.x, c.y - eye.y, c.z - eye.z);
        Vector3f toEye = new Vector3f((float) (eye.x - c.x), (float) (eye.y - c.y), (float) (eye.z - c.z));
        out.submitCustomGeometry(pose, SHELL, (p, buf) -> shell(p, buf, rx, ry, toEye, formed, alpha));
        if (t < BANDS) {
            float sweep = t / BANDS;
            float bandAlpha = near * smooth(Math.min(1, t / 3)) * (1 - smooth(Math.max(0, (sweep - .7f) / .3f)));
            out.submitCustomGeometry(pose, BAND, (p, buf) -> {
                for (int k = 0; k < 3; k++) band(p, buf, rx * 1.03f, ry * 1.03f, k / 3f, sweep, bandAlpha);
            });
        }
        pose.popPose();
    }

    /** The shield is gone (broken, or the body died or left view); release anything still playing. */
    void end() {
        Level lv = Minecraft.getInstance().level;
        if (center == null || lv == null || lv != level || age < 2) return;
        FXRuntime runtime = BREAK.createRuntime();
        runtime.getRoot().updatePos(new Vector3f((float) center.x, (float) center.y, (float) center.z));
        runtime.getRoot().updateScale(new Vector3f(across));
        runtime.emit(new Executor(lv));
        // After emit(), which resets the emitters: stretch the shell of cells to the sphere's own height.
        for (var object : runtime.objects.values()) if (object instanceof ParticleEmitter e) {
            e.runtime().shape.scale.set(new NumberFunction3(1, up / across, 1));
        }
    }

    /**
     * Horizontal and vertical radii of an ellipsoid that holds the whole box: at most 1.3 times taller than
     * wide, so a player gets an egg rather than a capsule, with a little room beyond the box.
     */
    private static float[] radii(LivingEntity body) {
        float a = body.getBbWidth() / 2, b = body.getBbHeight() / 2;
        float k = (float) clamp(b / (a * Math.sqrt(2)), 1, 1.3);
        float h = (float) Math.sqrt(2 * a * a + b * b / (k * k)) * 1.1f + .05f;
        return new float[] {h, h * k};
    }

    private static void shell(PoseStack.Pose pose, VertexConsumer buf, float rx, float ry, Vector3f toEye, float formed, float alpha) {
        int b = Math.round(formed * 255), a = Math.round(alpha * 255);
        for (int i = 0; i < LAT; i++) for (int j = 0; j < LON; j++) {
            // Counter-clockwise seen from outside, so gl_FrontFacing marks the near side.
            point(pose, buf, i, j, rx, ry, toEye, b, a);
            point(pose, buf, i + 1, j, rx, ry, toEye, b, a);
            point(pose, buf, i + 1, j + 1, rx, ry, toEye, b, a);
            point(pose, buf, i, j + 1, rx, ry, toEye, b, a);
        }
    }

    private static void point(PoseStack.Pose pose, VertexConsumer buf, int i, int j, float rx, float ry, Vector3f toEye, int formed, int alpha) {
        float[] u = UNIT[i * (LON + 1) + j];
        float x = u[0] * rx, y = u[1] * ry, z = u[2] * rx;
        // The ellipsoid's normal against the view direction gives the fresnel rim.
        Vector3f n = new Vector3f(u[0] / rx, u[1] / ry, u[2] / rx).normalize();
        Vector3f v = new Vector3f(toEye.x - x, toEye.y - y, toEye.z - z).normalize();
        int rim = Math.round((1 - Math.abs(n.dot(v))) * 255);
        int height = Math.round((u[1] * .5f + .5f) * 255);
        buf.addVertex(pose, x, y, z).setColor(rim, height, formed, alpha).setUv(u[3], u[4])
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /**
     * One of HoneyShield's Spiral bands: a strip winding a turn and a quarter up the sphere's surface. Its head
     * climbs from the bottom to past the top over the sweep, trailing a fading tail.
     */
    private static void band(PoseStack.Pose pose, VertexConsumer buf, float rx, float ry, float phase, float sweep, float alpha) {
        int a = Math.round(alpha * 255);
        if (a <= 0) return;
        float head = -.1f + 1.5f * (1 - (1 - sweep) * (1 - sweep)), tail = head - .55f;
        float width = .045f;
        // Only the part on the sphere, kept off the poles where every band would pile up.
        float lo = Math.max(tail, .06f), hi = Math.min(head, .94f);
        if (hi <= lo) return;
        int steps = 24;
        for (int s = 0; s < steps; s++) {
            float c0 = lo + (hi - lo) * s / steps, c1 = lo + (hi - lo) * (s + 1) / steps;
            float s0 = (c0 - tail) / (head - tail), s1 = (c1 - tail) / (head - tail);
            float[] in0 = onSphere(c0, phase, -width, rx, ry), out0 = onSphere(c0, phase, width, rx, ry);
            float[] in1 = onSphere(c1, phase, -width, rx, ry), out1 = onSphere(c1, phase, width, rx, ry);
            bandVertex(pose, buf, in0, s0, 0, a);
            bandVertex(pose, buf, out0, s0, 1, a);
            bandVertex(pose, buf, out1, s1, 1, a);
            bandVertex(pose, buf, in1, s1, 0, a);
        }
    }

    /** A point at height {@code h} (0 bottom, 1 top) on the spiral, shifted {@code dh} up or down across the band. */
    private static float[] onSphere(float h, float phase, float dh, float rx, float ry) {
        float polar = (float) Math.PI * (1 - Math.max(0, Math.min(1, h + dh)));
        float turn = (float) (2 * Math.PI * (phase + 1.25f * h));
        float ring = (float) Math.sin(polar);
        return new float[] {(float) Math.cos(turn) * ring * rx, (float) Math.cos(polar) * ry, (float) Math.sin(turn) * ring * rx};
    }

    private static void bandVertex(PoseStack.Pose pose, VertexConsumer buf, float[] p, float u, float v, int alpha) {
        buf.addVertex(pose, p[0], p[1], p[2]).setColor(255, 255, 255, alpha).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static float[][] unitSphere() {
        float[][] grid = new float[(LAT + 1) * (LON + 1)][];
        for (int i = 0; i <= LAT; i++) for (int j = 0; j <= LON; j++) {
            double polar = Math.PI * i / LAT, turn = 2 * Math.PI * j / LON;
            grid[i * (LON + 1) + j] = new float[] {
                    (float) (Math.sin(polar) * Math.cos(turn)), (float) Math.cos(polar), (float) (Math.sin(polar) * Math.sin(turn)),
                    j / (float) LON, i / (float) LAT};
        }
        return grid;
    }

    private static double sq(double v) { return v * v; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float smooth(float x) { return x * x * (3 - 2 * x); }

    private static float backOut(float x) {
        float s = 1.4f, y = x - 1;
        return 1 + y * y * ((s + 1) * y + s);
    }

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** The shield breaking: its honeycomb cells flung outward off the surface, under a brief pale flash. */
    private static FX breakEffect() {
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(
                breakLayer("ward/hex", 36, .1f, .2f, range(1.2f, 2.6f), range(9, 15), 1),
                breakLayer("ward/glow", 1, 1.6f, 1.9f, range(0, 0), range(5, 7), 0),
                breakLayer("ward/glow", 14, .1f, .18f, range(2.5f, 4.5f), range(6, 11), 1)));
        return fx;
    }

    private static ParticleEmitter breakLayer(String texture, int count, float min, float max,
            RandomConstant speed,
            RandomConstant life, float radius) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, texture, true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setStartColor(NumberFunction.color(radius == 0 ? 0x38E4ECF6 : 0xF0E4ECF6));
        c.setStartSize(size(min, max));
        c.setStartSpeed(speed);
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        var shape = sphere(radius);
        shape.setRadiusThickness(0);
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        c.emission.getBursts().add(b);
        c.colorOverLifetime.setEnable(true);
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF)));
        Curve shrink = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .4f, .9f, .75f, .6f, 1, .2f));
        c.sizeOverLifetime.setEnable(true);
        c.sizeOverLifetime.setSize(new NumberFunction3(shrink, shrink, shrink));
        c.rotationOverLifetime.setEnable(true);
        c.rotationOverLifetime.setRoll(range(-12, 12));
        c.physics.setEnable(true);
        c.physics.setGravity(NumberFunction.constant(.01f));
        return e;
    }

    private static RenderType material(boolean band) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/ward_" + (band ? "band" : "sphere")))
                .withFragmentShader(id("core/ward_sphere"))
                .withSampler("NoiseTex").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        if (band) pipeline.withShaderDefine("WARD_RIBBON");
        return RenderType.create("logos_ward_" + (band ? "band" : "sphere"), RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", id("textures/vfx/ward/honeycomb.png"))
                .withTexture("NoiseTex", id("textures/vfx/ward/noise.png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
