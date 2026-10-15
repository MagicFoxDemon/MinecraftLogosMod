package dev.logos.client;

import static dev.logos.client.LogosFx.range;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.runtime.Vfx;
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
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A void {@code ray}: the burst half of Piloto Studio's {@code PS_UltimateBeam_Void}. In a tick the line
 * tears open from the wand to the end as a band of open void (the screen-projected starfield every void
 * effect shows), wrapped in violet wisps and two winding strands, with a pale thread burning down its
 * middle and a crossed flare at the wand. The wisps collapse within a third of a second; what stays is the
 * band, which keeps narrowing until it is gone, shedding a few pale motes. A ray that struck something
 * tears a small rift there, with a shock ring and sparks thrown back toward the caster. Links (drain,
 * execute) are the same line, slimmer, with nothing at their ends.
 */
final class VoidRayVisual implements RayVisual {
    /** Ticks until nothing is left: the burst settles by tick 11, then the line narrows away over five seconds. */
    private static final int LIFE = 111;
    private static final RenderType BAND = type("band", "VOID_RAY_BAND", "silk", false, true);
    private static final RenderType RIFT = type("rift", "VOID_RAY_RIFT", "silk", false, true);
    private static final RenderType GLOW = type("glow", "VOID_RAY_GLOW", "wisp", true, false);
    private static final RenderType FLARE = type("flare", "VOID_RAY_SPRITE", "flare", true, false);
    private static final RenderType STAR = type("star", "VOID_RAY_SPRITE", "star", true, false);
    private static final RenderType DARK_STAR = type("dark_star", "VOID_RAY_SPRITE", "star", false, false);
    private static final RenderType DOT = type("dot", "VOID_RAY_SPRITE", "dot", true, false);
    private static final RenderType RING = type("ring", "VOID_RAY_SPRITE", "ring", true, false);
    private static final FX SPARKS = sparks();

    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO, axis = new Vec3(0, 0, 1);
    private Vfx.RayEnd end = Vfx.RayEnd.OPEN;
    private double length;
    private long born;
    private int seed;
    private @Nullable FXRuntime sparks;

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        this.to = to;
        this.end = end;
        Vec3 delta = to.subtract(from);
        length = delta.length();
        axis = length > 1e-4 ? delta.scale(1 / length) : new Vec3(0, 0, 1);
        born = level.getGameTime();
        seed = level.getRandom().nextInt();
    }

    @Override
    public boolean tick(Level level) {
        long age = level.getGameTime() - born;
        // The sparks leave the struck point as the band's front arrives there.
        if (sparks == null && age >= 1 && hit()) {
            sparks = SPARKS.createRuntime();
            sparks.getRoot().updatePos(impactPoint().toVector3f());
            sparks.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), axis.toVector3f()));
            sparks.emit(new Executor(level));
        }
        return age < LIFE;
    }

    @Override
    public void end() {
        if (sparks != null) { sparks.destroy(true); sparks = null; }
    }

    private boolean hit() {
        return end == Vfx.RayEnd.BLOCK || end == Vfx.RayEnd.CREATURE;
    }

    /** Where the struck rift opens: just in front of a wall, or on the caster's side of a creature's middle. */
    private Vec3 impactPoint() {
        double back = end == Vfx.RayEnd.CREATURE ? Math.min(.55, length * .3) : Math.min(.12, length * .1);
        return to.subtract(axis.scale(back));
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (length < .05) return;
        float age = level.getGameTime() - born + partialTick;
        if (age < 0 || age >= LIFE) return;
        Frame f = new Frame(camera.pos, camera.orientation);
        float scale = end == Vfx.RayEnd.LINK ? .6f : 1;
        // The front tears from the wand to the end within a tick.
        double front = length * ease(clamp(age / 1.2f));
        out.submitCustomGeometry(pose, BAND, (p, buf) -> band(p, buf, f, age, front, scale));
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
            glow(p, buf, f, age, front, scale);
            strands(p, buf, f, age, front, scale);
        });
        if (age < 2.2f) out.submitCustomGeometry(pose, DOT, (p, buf) -> head(p, buf, f, age, front, scale));
        if (age < 8) muzzle(pose, out, f, age, scale);
        out.submitCustomGeometry(pose, STAR, (p, buf) -> motes(p, buf, f, age, scale));
        if (hit() && age >= 1) impact(pose, out, f, age - 1);
    }

    /** Half-width of the void band: bursts open, holds, collapses to a thin line, then narrows away. */
    private static float bandWidth(float age) {
        if (age < 1) return .3f * ease(age);
        if (age < 4.5f) return .3f;
        if (age < 11) return lerp(.3f, .085f, smooth((age - 4.5f) / 6.5f));
        return .085f * (float) Math.pow(1 - clamp((age - 11) / (LIFE - 12)), 1.4);
    }

    /** How the line thins toward its ends: it grows out of the wand, and an open ray frays out in the air. */
    private float endTaper(double s) {
        float t = smooth((float) (s / Math.min(1, length * .3)));
        if (end == Vfx.RayEnd.OPEN) t *= 1 - .8f * smooth((float) ((s - length * .75) / (length * .25)));
        if (end == Vfx.RayEnd.LINK) t *= smooth((float) ((length - s) / Math.min(.8, length * .3)));
        return t;
    }

    private float endFade(double s) {
        return end == Vfx.RayEnd.OPEN ? 1 - smooth((float) ((s - length * .85) / (length * .15))) : 1;
    }

    private void band(PoseStack.Pose pose, VertexConsumer buf, Frame f, float age, double front, float scale) {
        float width = bandWidth(age) * scale;
        if (width <= .002f) return;
        float burst = 1 - smooth((age - 4) / 7);
        // Pale while the burst is hot, settling to the void's violet (the shader reads the colour's floor as heat).
        float r = lerp(.6f, .93f, burst), g = lerp(.16f, .8f, burst);
        float alpha = 1 - smooth((age - (LIFE - 8)) / 8);
        ribbon(pose, buf, f, front, (s, out) -> {
            float ripple = 1 + .2f * burst * (float) Math.sin(s * 2.3 - age * 2.5);
            out.set(width * ripple * endTaper(s), r, g, 1, alpha * endFade(s) * near(f, s));
        });
    }

    /** The burst's violet wisps (MA_Laser_Fire), and afterwards a faint halo that keeps the thin line readable at night. */
    private void glow(PoseStack.Pose pose, VertexConsumer buf, Frame f, float age, double front, float scale) {
        float burst = age < 1 ? ease(age) : age < 4 ? 1 : 1 - smooth((age - 4) / 5);
        float halo = bandWidth(age) * 2.6f;
        float width = (burst * .75f + halo) * scale;
        if (width <= .002f) return;
        float alpha = (burst * .9f + (1 - burst) * .3f) * (end == Vfx.RayEnd.LINK ? .6f : 1) * (1 - smooth((age - (LIFE - 10)) / 10));
        ribbon(pose, buf, f, front, (s, out) ->
                out.set(width * endTaper(s), .62f, .24f, 1, alpha * endFade(s) * near(f, s)));
    }

    /** Two pale strands winding round a ray (the Trail_WindyGeneric orbits), unwinding as the burst dies; links have none. */
    private void strands(PoseStack.Pose pose, VertexConsumer buf, Frame f, float age, double front, float scale) {
        if (age >= 12 || front <= 1e-3 || end == Vfx.RayEnd.LINK) return;
        float life = age / 12;
        float radius = (.1f + .3f * (1 - life)) * scale;
        float alpha = .75f * (float) Math.pow(1 - life, 1.3) * ease(clamp(age / 1.5f));
        Vec3 side = perpendicular(axis), up = axis.cross(side);
        int n = (int) Math.min(320, Math.max(8, front / .15));
        for (int k = 0; k < 2; k++) {
            double phase = k * Math.PI + (seed & 7);
            Vec3[] points = new Vec3[n + 1];
            for (int i = 0; i <= n; i++) {
                double s = front * i / n;
                double a = s * 1.5 - age * .8 + phase;
                // Kept tight round the wand, where they would sweep across the lens.
                double rr = radius * endTaper(s) * smooth((float) (s / 3));
                points[i] = from.add(axis.scale(s)).add(side.scale(Math.cos(a) * rr)).add(up.scale(Math.sin(a) * rr));
            }
            polyline(pose, buf, f, points, .04f * scale, .78f, .5f, 1, alpha, front);
        }
    }

    /** The bright head racing ahead of the band (Flare_FatDot shot along the beam). */
    private void head(PoseStack.Pose pose, VertexConsumer buf, Frame f, float age, double front, float scale) {
        Vec3 at = from.add(axis.scale(front));
        float alpha = (1 - smooth(age / 2.2f)) * near(f, front);
        sprite(pose, buf, f, at, .45f * scale, 0, .9f, .8f, 1, alpha);
        sprite(pose, buf, f, at, .9f * scale, 0, .6f, .25f, 1, alpha * .5f);
    }

    /**
     * The crossed flare and a dark four-point glint at the wand (DiagonalFlare_ConalSpliff, Flare_Glint_Dark).
     * Right in front of the lens it shrinks to a spark and is pushed a little out along the ray.
     */
    private void muzzle(PoseStack pose, SubmitNodeCollector out, Frame f, float age, float scale) {
        double distance = from.distanceTo(f.camera);
        float far = smooth((float) ((distance - 1) / 3));
        Vec3 at = from.add(axis.scale(.5 * (1 - far)));
        float size = (float) Math.min(1.1, .14 + .25 * distance) * scale;
        float life = age / 7;
        float grow = life < .115f ? life / .115f : life < .42f ? 1 : 1 - (life - .42f) / .58f;
        float alpha = (1 - life) * (.35f + .65f * far);
        out.submitCustomGeometry(pose, FLARE, (p, buf) -> {
            sprite(p, buf, f, at, size * grow, (float) Math.PI / 4, .78f, .5f, 1, alpha);
            sprite(p, buf, f, at, size * grow * .8f, -(float) Math.PI / 4, .78f, .5f, 1, alpha);
        });
        if (age < 3 && far > 0) out.submitCustomGeometry(pose, DARK_STAR, (p, buf) ->
                sprite(p, buf, f, at, size * 1.2f * (1 - age / 3), 0, .1f, .02f, .2f, far * .9f));
    }

    /** Pale motes shed from the line as it narrows, drifting off and twinkling out. */
    private void motes(PoseStack.Pose pose, VertexConsumer buf, Frame f, float age, float scale) {
        int count = (int) Math.min(48, length * 1.5);
        Vec3 side = perpendicular(axis), up = axis.cross(side);
        for (int i = 0; i < count; i++) {
            float r1 = hash(i, 1), r2 = hash(i, 2), r3 = hash(i, 3), r4 = hash(i, 4);
            float birth = 2 + r1 * 30, life = 14 + r2 * 30;
            float t = (age - birth) / life;
            if (t <= 0 || t >= 1) continue;
            double s = length * (.04 + .92 * r3);
            double turn = r4 * Math.PI * 2;
            Vec3 drift = side.scale(Math.cos(turn)).add(up.scale(Math.sin(turn)));
            Vec3 at = from.add(axis.scale(s)).add(drift.scale((.05 + (.3 + .4 * r2) * (age - birth) / 20) * scale));
            float twinkle = .6f + .4f * (float) Math.sin(age * (.8 + r1) + i);
            float alpha = (float) Math.sin(t * Math.PI) * twinkle * near(f, s) * endFade(s);
            sprite(pose, buf, f, at, (.05f + .06f * r4) * scale, r3 * 3, .88f, .72f, 1, alpha);
        }
    }

    /** A torn pocket of void where the ray struck, a shock ring and a glint; Photon throws the sparks. */
    private void impact(PoseStack pose, SubmitNodeCollector out, Frame f, float age) {
        Vec3 at = impactPoint();
        float rift = age < 2 ? ease(age / 2) : age < 6 ? 1 : 1 - smooth((age - 6) / 8);
        if (rift > 0) out.submitCustomGeometry(pose, RIFT, (p, buf) ->
                sprite(p, buf, f, at, .75f * rift, age * .05f, .75f, .4f, 1, 1));
        if (age < 9) out.submitCustomGeometry(pose, RING, (p, buf) ->
                sprite(p, buf, f, at, .3f + 1.4f * ease(age / 9), age * .1f, .65f, .3f, 1, 1 - age / 9));
        if (age < 5) out.submitCustomGeometry(pose, STAR, (p, buf) ->
                sprite(p, buf, f, at, 1.1f * (1 - age / 5), 0, .9f, .8f, 1, 1));
    }

    // ---- geometry -----------------------------------------------------------------------------

    /** The camera's position and its screen axes in the world. */
    private record Frame(Vec3 camera, Vec3 right, Vec3 up) {
        Frame(Vec3 camera, Quaternionf orientation) {
            this(camera, new Vec3(orientation.transform(new Vector3f(1, 0, 0))), new Vec3(orientation.transform(new Vector3f(0, 1, 0))));
        }
    }

    /** What a ribbon looks like at one point. */
    private static final class Sample {
        float halfWidth, r, g, b, a;

        void set(float halfWidth, float r, float g, float b, float a) {
            this.halfWidth = halfWidth; this.r = r; this.g = g; this.b = b; this.a = a;
        }
    }

    private interface Profile { void at(double s, Sample out); }

    /** A camera-facing ribbon along the ray up to {@code front}; finer near the wand, where the camera may be. */
    private void ribbon(PoseStack.Pose pose, VertexConsumer buf, Frame f, double front, Profile profile) {
        if (front <= 1e-3) return;
        Sample a = new Sample(), b = new Sample();
        double s0 = 0;
        profile.at(s0, a);
        Vec3 p0 = from, side0 = facing(p0, axis, f);
        while (s0 < front) {
            double step = s0 < 3 ? .15 : s0 < 12 ? .35 : .7;
            double s1 = Math.min(front, s0 + step);
            profile.at(s1, b);
            Vec3 p1 = from.add(axis.scale(s1)), side1 = facing(p1, axis, f);
            if (a.a > .002f || b.a > .002f) {
                vertex(pose, buf, f, p0.add(side0.scale(a.halfWidth)), (float) s0, 0, a);
                vertex(pose, buf, f, p0.subtract(side0.scale(a.halfWidth)), (float) s0, 1, a);
                vertex(pose, buf, f, p1.subtract(side1.scale(b.halfWidth)), (float) s1, 1, b);
                vertex(pose, buf, f, p1.add(side1.scale(b.halfWidth)), (float) s1, 0, b);
            }
            Sample swap = a; a = b; b = swap;
            s0 = s1; p0 = p1; side0 = side1;
        }
    }

    /** A thin camera-facing ribbon through arbitrary points spread evenly over {@code front}. */
    private void polyline(PoseStack.Pose pose, VertexConsumer buf, Frame f, Vec3[] points, float halfWidth,
            float r, float g, float b, float alpha, double front) {
        Sample a = new Sample(), c = new Sample();
        for (int i = 0; i + 1 < points.length; i++) {
            Vec3 p0 = points[i], p1 = points[i + 1];
            Vec3 dir = p1.subtract(p0);
            if (dir.lengthSqr() < 1e-10) continue;
            dir = dir.normalize();
            double s0 = front * i / (points.length - 1), s1 = front * (i + 1) / (points.length - 1);
            a.set(halfWidth, r, g, b, alpha * near(f, s0) * endFade(s0));
            c.set(halfWidth, r, g, b, alpha * near(f, s1) * endFade(s1));
            Vec3 side0 = facing(p0, dir, f), side1 = facing(p1, dir, f);
            vertex(pose, buf, f, p0.add(side0.scale(halfWidth)), (float) s0, 0, a);
            vertex(pose, buf, f, p0.subtract(side0.scale(halfWidth)), (float) s0, 1, a);
            vertex(pose, buf, f, p1.subtract(side1.scale(halfWidth)), (float) s1, 1, c);
            vertex(pose, buf, f, p1.add(side1.scale(halfWidth)), (float) s1, 0, c);
        }
    }

    /** A camera-facing square of half-size {@code size}, turned by {@code roll} in the screen plane. */
    private static void sprite(PoseStack.Pose pose, VertexConsumer buf, Frame f, Vec3 at, float size, float roll,
            float r, float g, float b, float alpha) {
        if (size <= 1e-3 || alpha <= .002f) return;
        float c = (float) Math.cos(roll) * size, s = (float) Math.sin(roll) * size;
        Vec3 x = f.right.scale(c).add(f.up.scale(s)), y = f.up.scale(c).subtract(f.right.scale(s));
        Sample k = new Sample();
        k.set(size, r, g, b, alpha);
        vertex(pose, buf, f, at.subtract(x).subtract(y), 0, 1, k);
        vertex(pose, buf, f, at.add(x).subtract(y), 1, 1, k);
        vertex(pose, buf, f, at.add(x).add(y), 1, 0, k);
        vertex(pose, buf, f, at.subtract(x).add(y), 0, 0, k);
    }

    /** Every element written, relative to the camera, as SHADER_PACK_VFX.md requires. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Frame f, Vec3 p, float u, float v, Sample k) {
        buf.addVertex(pose, (float) (p.x - f.camera.x), (float) (p.y - f.camera.y), (float) (p.z - f.camera.z))
                .setColor(k.r, k.g, k.b, clamp(k.a)).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** Across a line through {@code p} along {@code dir}, in the plane facing the camera. */
    private static Vec3 facing(Vec3 p, Vec3 dir, Frame f) {
        Vec3 side = dir.cross(f.camera.subtract(p));
        // Looking straight down the line: any direction across it will do.
        if (side.lengthSqr() < 1e-8) side = dir.cross(f.up);
        return side.lengthSqr() < 1e-8 ? perpendicular(dir) : side.normalize();
    }

    private static Vec3 perpendicular(Vec3 dir) {
        return dir.cross(Math.abs(dir.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
    }

    /** Fades whatever is right in front of the lens (a first-person ray starts at the wand head). */
    private float near(Frame f, double s) {
        double d = from.add(axis.scale(s)).distanceTo(f.camera);
        return .05f + .95f * smooth((float) ((d - .5) / 3));
    }

    /** A fixed random number in [0, 1) for mote {@code i}, so motes keep their places frame to frame. */
    private float hash(int i, int salt) {
        int h = seed * 0x9E3779B1 + i * 0x85EBCA6B + salt * 0xC2B2AE35;
        h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12; h *= 0x297A2D39; h ^= h >>> 15;
        return (h >>> 8) / (float) (1 << 24);
    }

    private static float clamp(float x) { return Math.max(0, Math.min(1, x)); }
    private static float smooth(float x) { x = clamp(x); return x * x * (3 - 2 * x); }
    private static float ease(float x) { x = clamp(x); return 1 - (1 - x) * (1 - x); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // ---- materials and the impact sparks --------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** Flecks of violet light thrown back off the struck point toward the caster. */
    private static FX sparks() {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        ProjectileParticles.configureTexture(e, "void_ray/dot", true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(32);
        c.setStartColor(NumberFunction.color(0xF0B47CFF));
        c.setStartSize(new NumberFunction3(range(.06f, .1f), range(.06f, .1f), NumberFunction.constant(1)));
        // Blocks per second.
        c.setStartSpeed(range(4, 8));
        c.setStartLifetime(range(6, 11));
        Cone cone = new Cone();
        cone.setRadius(.15f);
        cone.setAngle(70);
        c.shape.setShape(cone);
        // Cones open along local +Y; turned to open along -Z, back toward the caster.
        c.shape.setRotation(new NumberFunction3(-90, 0, 0));
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst burst = new EmissionSetting.Burst();
        burst.time = 0;
        burst.setCount(NumberFunction.constant(16));
        c.emission.getBursts().add(burst);
        c.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        c.renderer.setLengthScale(3);
        c.renderer.setVelocityScale(.05f);
        c.colorOverLifetime.setEnable(true);
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF)));
        c.velocityOverLifetime.setEnable(true);
        c.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .4f, .6f, .15f, 1, .1f)));
        FX fx = new FX();
        fx.getFxData().objects().add(e);
        return fx;
    }

    /** Custom geometry drawn through the shader-pack overlay; no layer writes depth. */
    private static RenderType type(String name, String define, String texture, boolean additive, boolean starfield) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_ray_" + name)).withVertexShader(id("core/void_ray_geometry"))
                .withFragmentShader(id("core/void_ray_geometry")).withShaderDefine(define).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (starfield) pipeline.withSampler("StarTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/void_ray/" + texture + ".png"));
        if (starfield) setup.withTexture("StarTex", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"));
        return RenderType.create("logos_void_ray_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
