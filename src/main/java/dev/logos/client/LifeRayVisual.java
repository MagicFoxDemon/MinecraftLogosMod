package dev.logos.client;

import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.ArrayList;
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
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2i;
import org.joml.Vector3f;

/**
 * A life {@code ray}, after the release half of Piloto's {@code PS_UltimateBeam_Nature}: a light bullet
 * runs the line in two ticks, a warm laser and a band of thorny vines flash along it and narrow away
 * while a silky green sheath swells round them, and a slanted flare opens at the muzzle. What stays is
 * life's own: a thin green line fading slowly, leaves drifting down from it and motes of light rising,
 * all gone in under seven seconds. A ray that stops on a block or a creature blooms at the end. Everything
 * drawn along the line fades out close to the camera, so in first person the wand's muzzle never fills
 * the view.
 */
final class LifeRayVisual implements RayVisual {
    private static final RenderType LASER = material("laser", true);
    private static final RenderType VINES = material("vines", true);
    private static final RenderType SHEATH = material("sheath", true);
    private static final RenderType FLARE = material("flare", true);
    private static final RenderType DOT = material("dot", true);
    private static final RenderType LEAVES = material("leaves", false);
    // Alpha-blended twins laid under the additive layers, so the line stays green against a bright sky.
    private static final RenderType LASER_TINT = material("laser", false);
    private static final RenderType VINES_TINT = material("vines", false);
    private static final RenderType SHEATH_TINT = material("sheath", false);
    private static final RenderType DOT_TINT = material("dot", false);
    private static final FX BLOOM = bloom();
    /** Ticks for the bullet to run the line; the burst is over by {@link #BURST}. */
    private static final float RUN = 2, BURST = 8;
    /** Ticks until the afterglow line is gone; leaves and motes last at most about 133. */
    private static final int AFTERGLOW = 118;
    /** Leaf tints after the prefab's random LeafAtlas colours; the last (brown) is rare. */
    private static final int[] LEAF = {0x2FB83A, 0x58E83A, 0x8FE83A, 0xB8F25A, 0x46C95A, 0xA88A5A};

    private Vec3 from = Vec3.ZERO, dir = new Vec3(0, 0, 1);
    private double length;
    private int age;
    private int lastDeath;
    private final List<Mote> motes = new ArrayList<>();
    private FXRuntime bloomRuntime;

    @Override
    public boolean legacy() { return false; }

    /** A leaf (falls, sways and turns over, alpha-blended) or a mote of light (rises, additive). */
    private record Mote(boolean leaf, Vec3 at, double drift, double swayX, double swayZ, double phase, int born, int life,
            int color, int frame, float size, float spin, float roll) {
        Vec3 pos(double t) {
            double a = t - born;
            double sway = Math.sin(a * .075 + phase);
            return at.add(swayX * sway, drift * a, swayZ * sway);
        }

        /** 0..1 over its life: quick fade-in, long hold, slow fade-out. */
        float alpha(double t) {
            double a = (t - born) / life;
            if (a <= 0 || a >= 1) return 0;
            return (float) Math.min(1, Math.min(a / .12, (1 - a) / .45));
        }
    }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        Vec3 d = to.subtract(from);
        length = d.length();
        dir = length > 1e-4 ? d.scale(1 / length) : new Vec3(0, 0, 1);
        RandomSource r = level.getRandom();
        // The first stretch next to the muzzle stays clear of leaves and motes.
        double clear = Math.min(.45, 2.5 / Math.max(1, length));
        int leaves = Mth.clamp((int) (length * .9), 4, 56);
        for (int i = 0; i < leaves; i++) {
            Vec3 at = along(clear + (1 - clear) * r.nextDouble()).add(jitter(r, .12));
            int tint = LEAF[r.nextInt(6) == 0 ? r.nextInt(LEAF.length) : r.nextInt(LEAF.length - 1)];
            motes.add(new Mote(true, at, -(.004 + .0055 * r.nextDouble()), (r.nextDouble() - .5) * .5,
                    (r.nextDouble() - .5) * .5, r.nextDouble() * 6.28, 2 + r.nextInt(18), 70 + r.nextInt(45),
                    tint, r.nextInt(4), .12f + .1f * r.nextFloat(), (r.nextFloat() - .5f) * .125f, r.nextFloat() * 6.28f));
        }
        int lights = Mth.clamp((int) (length * 1.4), 6, 90);
        for (int i = 0; i < lights; i++) {
            Vec3 at = along(clear + (1 - clear) * r.nextDouble()).add(jitter(r, .1));
            motes.add(new Mote(false, at, .005 + .009 * r.nextDouble(), (r.nextDouble() - .5) * .12,
                    (r.nextDouble() - .5) * .12, r.nextDouble() * 6.28, 3 + r.nextInt(28), 45 + r.nextInt(45),
                    r.nextInt(3) == 0 ? 0x4BE8A8 : 0x7FF04A, 0, .04f + .05f * r.nextFloat(), 0, 0));
        }
        for (Mote m : motes) lastDeath = Math.max(lastDeath, m.born + m.life);
        if (end == Vfx.RayEnd.BLOCK || end == Vfx.RayEnd.CREATURE) {
            Vec3 at = to.subtract(dir.scale(.08));
            bloomRuntime = BLOOM.createRuntime();
            bloomRuntime.getRoot().updatePos(new Vector3f((float) at.x, (float) at.y, (float) at.z));
            bloomRuntime.emit(new Executor(level));
        }
    }

    @Override
    public boolean tick(Level level) {
        age++;
        if (bloomRuntime != null && age > 40) { bloomRuntime.destroy(false); bloomRuntime = null; }
        return age <= Math.max(lastDeath, AFTERGLOW) + 2;
    }

    @Override
    public void end() {
        if (bloomRuntime != null) { bloomRuntime.destroy(true); bloomRuntime = null; }
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack stack, SubmitNodeCollector out) {
        if (length < .05) return;
        double t = age + partialTick;
        Vec3 cam = camera.pos;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        // How far along the line the light has run (the bullet's head).
        double reach = Math.min(1, t / RUN);
        stack.pushPose();
        stack.translate(-cam.x, -cam.y, -cam.z);

        if (t < BURST + 4) {
            float k = (float) (t / BURST);
            // MA_Laser_01: full width at once, narrowing to nothing; warm gold turning green.
            float laserW = .3f * (float) Math.max(0, 1 - Math.pow(Math.max(0, k - .25) / .75, 1.3));
            if (laserW > 0) {
                int warm = lerpColor(0xFFB165, 0x6FFF67, Mth.clamp(k * 1.6f, 0, 1));
                out.submitCustomGeometry(stack, LASER_TINT, (pose, buf) -> band(pose, buf, cam, reach, laserW * 1.4f, 0, 1,
                        argb(.6f * (1 - .5f * k), 0x34C83C)));
                out.submitCustomGeometry(stack, LASER, (pose, buf) -> band(pose, buf, cam, reach, laserW, 0, 1, argb(.8f, warm)));
                out.submitCustomGeometry(stack, LASER, (pose, buf) -> band(pose, buf, cam, reach, laserW * .35f, 0, 1,
                        argb(.9f * (1 - k), 0xF6FFE0)));
            }
            // VinesTrail_Beam_02: thorny vines streaming forward, narrowing to a fifth.
            float vineA = Mth.clamp(1.4f - k * 1.3f, 0, 1);
            if (vineA > 0) {
                float vineW = .62f * (1 - .78f * Mth.clamp(k, 0, 1));
                int vine = k < .3f ? lerpColor(0xFF9B3B, 0xCCFF53, k / .3f) : lerpColor(0xCCFF53, 0x76FF5F, Mth.clamp((k - .3f) / .7f, 0, 1));
                float scroll = (float) (-t * .35);
                out.submitCustomGeometry(stack, VINES_TINT, (pose, buf) -> band(pose, buf, cam, reach, vineW, scroll, 3.5f, argb(.7f * vineA, 0x2E9E2C)));
                out.submitCustomGeometry(stack, VINES, (pose, buf) -> band(pose, buf, cam, reach, vineW, scroll, 3.5f, argb(.7f * vineA, vine)));
            }
            // WaterNoise_MaskedAlpha: a silky sheath swelling round the line, fading as it opens.
            double s = (t - 1) / 10;
            if (s > 0 && s < 1) {
                float sheathW = (float) (.12 + .9 * Math.sqrt(s));
                float sheathA = (float) (.55 * Math.min(1, (1 - s) / .55));
                int tint = lerpColor(0xCBFF71, 0x9AE87F, (float) s);
                float scroll = (float) (-t * .12);
                out.submitCustomGeometry(stack, SHEATH_TINT, (pose, buf) -> band(pose, buf, cam, 1, sheathW, scroll, 6, argb(.45f * sheathA, 0x3DB04E)));
                out.submitCustomGeometry(stack, SHEATH, (pose, buf) -> band(pose, buf, cam, 1, sheathW, scroll, 6, argb(.6f * sheathA, tint)));
            }
            // The Flare_FatDot bullet racing the line.
            if (t < RUN + 1) {
                Vec3 head = along(reach);
                float a = (float) Mth.clamp(RUN + 1 - t, 0, 1);
                float r = .35f * fade(cam, head);
                out.submitCustomGeometry(stack, DOT, (pose, buf) -> quad(pose, buf, head, right, up, r, 0, 0, 1, 1, argb(a, 0xF2FFD0)));
            }
            // DiagonalFlare_ConalSpliff at the muzzle: two slanted flares, kept small near the camera.
            if (t < 7) {
                float a = (float) (1 - t / 7);
                Vec3 muzzle = from.add(dir.scale(Math.min(.6, length * .5)));
                float near = fade(cam, muzzle);
                float r = (.35f + .9f * near) * (float) (.4 + .6 * (1 - t / 7));
                int c = lerpColor(0xFFA661, 0x96FF46, (float) Math.min(1, t / 5));
                out.submitCustomGeometry(stack, FLARE, (pose, buf) -> {
                    quadRolled(pose, buf, muzzle, right, up, r, -.70f, argb(a * (.35f + .65f * near), c));
                    quadRolled(pose, buf, muzzle, right, up, r * .8f, 4.19f, argb(a * (.3f + .6f * near), c));
                });
            }
        }

        // The afterglow: a thin green line narrowing and fading over about five seconds, a faint glow round it.
        if (t > 3 && t < AFTERGLOW) {
            float a = (float) (Mth.clamp((t - 3) / 4, 0, 1) * (1 - smooth(8, AFTERGLOW - 3, t)));
            float w = .07f * (1 - .6f * (float) smooth(10, AFTERGLOW - 3, t));
            float pulse = 1 + .12f * (float) Math.sin(t * .35);
            out.submitCustomGeometry(stack, LASER_TINT, (pose, buf) -> band(pose, buf, cam, 1, w * 1.8f, 0, 1, argb(.6f * a, 0x2BB33A)));
            out.submitCustomGeometry(stack, LASER, (pose, buf) -> band(pose, buf, cam, 1, w, 0, 1, argb(.7f * a * pulse, 0x7DFF6E)));
            float scroll = (float) (-t * .04);
            out.submitCustomGeometry(stack, SHEATH_TINT, (pose, buf) -> band(pose, buf, cam, 1, .34f, scroll, 6, argb(.22f * a, 0x3DB04E)));
        }

        // Leaves drifting down and light rising off the line.
        out.submitCustomGeometry(stack, LEAVES, (pose, buf) -> {
            for (Mote m : motes) if (m.leaf) mote(pose, buf, m, t, cam, right, up, false);
        });
        out.submitCustomGeometry(stack, DOT_TINT, (pose, buf) -> {
            for (Mote m : motes) if (!m.leaf) mote(pose, buf, m, t, cam, right, up, true);
        });
        out.submitCustomGeometry(stack, DOT, (pose, buf) -> {
            for (Mote m : motes) if (!m.leaf) mote(pose, buf, m, t, cam, right, up, false);
        });
        stack.popPose();
    }

    // ---- geometry ------------------------------------------------------------------------------

    private Vec3 along(double s) {
        return from.add(dir.scale(length * s));
    }

    private static Vec3 jitter(RandomSource r, double spread) {
        return new Vec3(r.nextGaussian() * spread, r.nextGaussian() * spread, r.nextGaussian() * spread);
    }

    /** 0 inside the camera, 1 from three blocks away: what fades the line next to a first-person muzzle. */
    private static float fade(Vec3 cam, Vec3 p) {
        return (float) smooth(.6, 3, cam.distanceTo(p));
    }

    private static double smooth(double lo, double hi, double x) {
        double k = Mth.clamp((x - lo) / (hi - lo), 0, 1);
        return k * k * (3 - 2 * k);
    }

    /**
     * A camera-facing band from {@link #from} over {@code reach} of the line, {@code width} across, its
     * texture repeating every {@code tile} blocks; every point fades near the camera.
     */
    private void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, double reach, float width, float scroll, float tile, int color) {
        double len = length * reach;
        if (len < .01) return;
        int n = Mth.clamp((int) Math.ceil(len / 1.5), 2, 48);
        // Short pieces next to the muzzle so the camera fade is smooth there.
        double[] at = new double[n + 4];
        int count = 0;
        at[count++] = 0;
        for (double d : new double[] {.4, .9, 1.6}) if (d < len * .5) at[count++] = d;
        double start = at[count - 1];
        for (int i = 1; i <= n; i++) at[count++] = start + (len - start) * i / n;
        Vec3 prevP = null, prevSide = null;
        float prevU = 0;
        int prevC = 0;
        for (int i = 0; i < count; i++) {
            Vec3 p = from.add(dir.scale(at[i]));
            Vec3 side = dir.cross(cam.subtract(p));
            side = side.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : side.normalize();
            // Ends taper over half a block so the band has no square cut.
            double taper = Math.min(1, Math.min(at[i] / .5 + .2, (len - at[i]) / .5 + .2));
            side = side.scale(width * .5 * taper);
            float u = (float) (at[i] / tile) + scroll;
            int c = scaleAlpha(color, fade(cam, p));
            if (prevP != null) {
                vertex(pose, buf, prevP.add(prevSide), prevU, 0, prevC);
                vertex(pose, buf, prevP.subtract(prevSide), prevU, 1, prevC);
                vertex(pose, buf, p.subtract(side), u, 1, c);
                vertex(pose, buf, p.add(side), u, 0, c);
            }
            prevP = p; prevSide = side; prevU = u; prevC = c;
        }
    }

    /** {@code tint}: a mote's coloured, alpha-blended halo (drawn under its additive core). */
    private static void mote(PoseStack.Pose pose, VertexConsumer buf, Mote m, double t, Vec3 cam, Vec3 right, Vec3 up, boolean tint) {
        float a = m.alpha(t);
        if (a <= 0) return;
        Vec3 p = m.pos(t);
        float near = fade(cam, p);
        if (near <= 0) return;
        if (m.leaf) {
            float u0 = (m.frame & 1) * .5f, v0 = (m.frame >> 1) * .5f;
            float roll = m.roll + m.spin * (float) (t - m.born);
            // The leaf turning over as it falls.
            float flip = .35f + .65f * Math.abs((float) Math.cos((t - m.born) * .055 + m.phase));
            Vec3 r = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
            Vec3 u = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll))).scale(flip);
            quad(pose, buf, p, r, u, m.size, u0, v0, u0 + .5f, v0 + .5f, argb(.95f * a * near, m.color));
        } else {
            float twinkle = .65f + .35f * (float) Math.sin((t - m.born) * .5 + m.phase);
            if (tint) quad(pose, buf, p, right, up, m.size * 1.3f, 0, 0, 1, 1, argb(.55f * a * near, m.color));
            else quad(pose, buf, p, right, up, m.size * .55f, 0, 0, 1, 1, argb(a * twinkle * near, 0xF0FFD8));
        }
    }

    private static void quadRolled(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 right, Vec3 up, float r, float roll, int color) {
        Vec3 rr = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
        Vec3 uu = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll)));
        quad(pose, buf, c, rr, uu, r, 0, 0, 1, 1, color);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 right, Vec3 up, float r,
            float u0, float v0, float u1, float v1, int color) {
        Vec3 x = right.scale(r), y = up.scale(r);
        vertex(pose, buf, c.subtract(x).add(y), u0, v0, color);
        vertex(pose, buf, c.subtract(x).subtract(y), u0, v1, color);
        vertex(pose, buf, c.add(x).subtract(y), u1, v1, color);
        vertex(pose, buf, c.add(x).add(y), u1, v0, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static int argb(float alpha, int rgb) {
        return (int) (Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static int scaleAlpha(int color, float k) {
        return argb((color >>> 24) / 255f * k, color);
    }

    private static int lerpColor(int a, int b, float k) {
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static RenderType material(String texture, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/life_ray_" + texture)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_life_ray_" + texture, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/life_ray/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    // ---- the bloom where the ray lands ---------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** Life's bloom where the ray lands: a mint glow, the slanted flare, leaves bursting out and motes. */
    private static FX bloom() {
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(
                still("glow", 1.3f, 1.5f, 14, 0x5882FFA0),
                still("flare", 1.1f, 1.3f, 8, 0xC0D5FF6A),
                still("dot", .5f, .6f, 6, 0xE0F2FFD0),
                burstLeaves(),
                burstMotes()));
        return fx;
    }

    private static ParticleEmitter still(String texture, float min, float max, float life, int color) {
        ParticleEmitter e = layer(Element.LIFE, true, 0, 1, size(min, max), range(0, 0), range(life, life), sphere(0));
        tint(e, texture, true, color);
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .1f, .9f, .3f, 1, 1, .6f));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return e;
    }

    private static ParticleEmitter burstLeaves() {
        ParticleEmitter e = layer(Element.LIFE, false, 0, 12, size(.16f, .26f), range(1.5f, 3f), range(14, 26), sphere(.2f));
        tint(e, "leaves", false, 0xF07ADF4A);
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-12, 12));
        slowDown(e, .1f);
        return e;
    }

    private static ParticleEmitter burstMotes() {
        ParticleEmitter e = layer(Element.LIFE, true, 0, 16, size(.05f, .1f), range(.8f, 2f), range(14, 26), sphere(.25f));
        tint(e, "dot", true, 0xE0E7FFA0);
        slowDown(e, .2f);
        return e;
    }

    private static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .35f, .6f, end, 1, end)));
    }

    private static void tint(ParticleEmitter e, String texture, boolean additive, int color) {
        configureTexture(e, "life_ray/" + texture, additive);
        e.config.setDuration(20);
        e.config.setMaxParticles(24);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xE0F1FFCC, 0x90CCFFAA, 0x3075DC99, 0x0047BD99)));
        e.config.physics.setEnable(false);
    }
}
