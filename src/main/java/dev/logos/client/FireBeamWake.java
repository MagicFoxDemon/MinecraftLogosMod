package dev.logos.client;

import static dev.logos.client.FireBeamCore.endOn;
import static dev.logos.client.FireBeamCore.nearFade;
import static dev.logos.client.FireBeamCore.smooth;
import static dev.logos.client.FireBeamVisual.envelope;
import static dev.logos.client.FireBeamVisual.fade;
import static dev.logos.client.FireBeamVisual.sheet;
import static dev.logos.client.FireBeamVisual.slowDown;
import static dev.logos.client.LogosFx.range;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.runtime.Vfx;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * What a fire beam leaves on its last line, in the manner of the fire ray's smoke: the flame burns back
 * from the wand toward the far end in a third of a second, a smouldering orange line glows on for a couple
 * of seconds and cools, and a band of dark-red soot (the beam's sheath) lifts off the line, widens,
 * wanders, greys and is eaten away over about six and a half seconds, while twirly smoke puffs rise and
 * embers drift up out of it.
 */
final class FireBeamWake implements RayVisual {
    /** Ticks until the last of the smoke is gone. */
    static final int LIFE = 130;
    /** The flame is gone this many ticks after the beam ends. */
    private static final float BURN = 7;
    /** The smouldering line has cooled by then. */
    private static final float EMBER = 50;
    /** Photon effects by rounded length (and whether the caster looks down the line). */
    private static final Map<Integer, FX> BODIES = new HashMap<>();

    private final List<FXRuntime> playing = new ArrayList<>();
    private Vec3 from = Vec3.ZERO, dir = new Vec3(0, 0, 1), drift = new Vec3(1, 0, 0);
    private double length;
    private float light = 1, seed;
    private int age;
    /** The caster looks down the line (first person). */
    private boolean viewDown;

    @Override public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        length = from.distanceTo(to);
        if (length > 1e-4) dir = to.subtract(from).scale(1 / length);
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        drift = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
        seed = level.getRandom().nextFloat() * 100;
        light = Math.max(level.getMaxLocalRawBrightness(BlockPos.containing(from.add(to).scale(.5))),
                level.getMaxLocalRawBrightness(BlockPos.containing(from))) / 15f;
        if (length < .3) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        boolean close = !camera.isDetached() && camera.position().distanceTo(from) < 3;
        double trim = Math.min(close ? 4 : .4, length * .5);
        int rounded = Mth.clamp((int) Math.round(length - trim), 1, 64);
        FX body = BODIES.computeIfAbsent(rounded * 2 + (close ? 1 : 0), key -> body(key / 2));
        Vec3 mid = from.add(dir.scale(trim)).add(to).scale(.5);
        FXRuntime runtime = body.createRuntime();
        for (var object : runtime.objects.values()) if (object instanceof ParticleEmitter e)
            e.config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
        runtime.getRoot().updatePos(new Vector3f((float) mid.x, (float) mid.y, (float) mid.z));
        runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) dir.x, (float) dir.y, (float) dir.z)));
        runtime.emit(new Executor(level));
        playing.add(runtime);
    }

    @Override public boolean tick(Level level) { return ++age < LIFE; }

    @Override
    public void end() {
        playing.forEach(fx -> fx.destroy(true));
        playing.clear();
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (length < .3) return;
        float t = age + partialTick;
        Vec3 eye = camera.pos;
        boolean close = !Minecraft.getInstance().gameRenderer.getMainCamera().isDetached() && eye.distanceTo(from) < 3;
        viewDown = close;
        Vec3[] frame = FireBeamCore.frame(dir);
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        if (t > 1) out.submitCustomGeometry(pose, FireBeamCore.SMOKE, (p, buf) -> smoke(p, buf, eye, t - 1));
        if (t < EMBER) out.submitCustomGeometry(pose, FireBeamCore.EMBER, (p, buf) -> ember(p, buf, eye, t, close));
        if (t < BURN) {
            // The flame burns back from the wand toward the far end, thinning and breaking up.
            float k = t / BURN;
            double start = length * smooth(k) * .85;
            float amount = 1 - smooth(k);
            out.submitCustomGeometry(pose, FireBeamCore.FLAME_BODY, (p, buf) -> FireBeamCore.strip(p, buf, eye, from, dir,
                    start, length, 4, seed, 1, .9f, .8f, flame(eye, start, .3 * (1 - .5 * k), amount, t, close, frame)));
            if (k < .5) out.submitCustomGeometry(pose, FireBeamCore.CORE, (p, buf) -> FireBeamCore.strip(p, buf, eye, from, dir,
                    start, length, 4, seed, 1, 1, 1, flame(eye, start, .1, 1 - k * 2, t, close, frame)));
        }
        pose.popPose();
    }

    private FireBeamCore.Profile flame(Vec3 eye, double start, double width, float amount, float t, boolean close, Vec3[] frame) {
        return new FireBeamCore.Profile() {
            @Override public double width(double s) {
                double tail = smooth((float) ((s - start) / 1.2));
                return width * (.4 + .6 * tail) * (1 + .25 * Math.sin(s * 1.9 - t * .9 + seed));
            }

            @Override public Vec3 offset(double s) {
                return frame[1].scale(width * .4 * Math.sin(s * .9 - t * .4 + seed) + .03 * t);
            }

            @Override public float alpha(double s) {
                Vec3 p = from.add(dir.scale(s));
                return amount * nearFade(eye, p, close ? 2 : .3, close ? 8 : 1.2) * down(eye, p);
            }
        };
    }

    /** The smouldering line: a dim orange glow that rises with the smoke and cools out. */
    private void ember(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, float t, boolean close) {
        float left = (float) Math.pow(1 - t / EMBER, 1.6) * Math.min(1, t / 3);
        double rise = .5 * (1 - Math.exp(-t / 40));
        FireBeamCore.strip(pose, buf, eye, from, dir, 0, length, 2, seed + 1.3f, 1, 1, 1, new FireBeamCore.Profile() {
            @Override public double width(double s) { return .16 + .1 * t / EMBER; }
            @Override public Vec3 offset(double s) { return new Vec3(0, rise * (.8 + .2 * Math.sin(s * .55 + seed)), 0); }
            @Override public float alpha(double s) {
                Vec3 p = from.add(dir.scale(s));
                double ends = Math.min(1, Math.min(s / .8, (length - s) / 1.5));
                return (float) (left * .75 * ends) * nearFade(eye, p, close ? 2.5 : .3, close ? 7 : 1.2) * down(eye, p);
            }
        });
    }

    /**
     * The soot band {@code ts} ticks after the beam: dark red-brown and dense at first, cooling to grey as
     * it rises about a block, widens, wanders sideways and is eaten away.
     */
    private void smoke(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, float ts) {
        float span = LIFE - 1;
        float left = smooth(ts / 5) * (1 - smooth((ts - 6) / (span - 8)));
        if (left <= 0) return;
        float halfWidth = .3f + .75f * (1 - (float) Math.exp(-ts / 34));
        double rise = 1.1 * (1 - Math.exp(-ts / 55));
        float grey = smooth((ts - 6) / 60);
        float lit = .25f + .75f * light;
        float r = lerp(grey, .36f, .56f) * lit, g = lerp(grey, .12f, .54f) * lit, b = lerp(grey, .07f, .53f) * lit;
        FireBeamCore.strip(pose, buf, eye, from, dir, 0, length, 2, seed, r, g, b, new FireBeamCore.Profile() {
            double wave(double s) {
                return Math.sin(s * .55 + seed + ts * .032) * .5 + Math.sin(s * 1.3 - seed * .7 + ts * .019) * .25;
            }

            @Override public double width(double s) {
                return halfWidth * (.4 + .6 * ends(s)) * (1 + .15 * Math.sin(s * 1.7 + seed * 1.3 + ts * .05));
            }

            @Override public Vec3 offset(double s) {
                double w = wave(s);
                return new Vec3(0, rise * (.75 + .25 * w), 0).add(drift.scale(w * .45 * ts / LIFE));
            }

            @Override public float alpha(double s) {
                Vec3 p = from.add(dir.scale(s));
                return (float) (left * (.3 + .7 * ends(s))) * nearFade(eye, p, 2, 6) * down(eye, p);
            }

            double ends(double s) { return Math.min(1, Math.min(s / .8, (length - s) / 1.5)); }
        });
    }

    /** Thins what piles up end-on; much harder when the caster looks straight down the line. */
    private float down(Vec3 eye, Vec3 p) {
        float on = endOn(eye, p, dir);
        return viewDown ? on * on * on : on;
    }

    private static float lerp(float k, float a, float b) { return a + (b - a) * k; }

    // ---- Photon layers --------------------------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** Along {@code length} blocks of line: the last curls burning out, embers drifting up, twirly smoke rising. */
    private static FX body(int length) {
        ParticleEmitter curls = burst("fire_beam/flame_quad", true, Math.min(36, Math.round(length * .7f) + 1),
                range(.4f, .7f), range(5, 9), 0xFFFFFFFF);
        curls.config.shape.setScale(new NumberFunction3(.4f, length, .4f));
        curls.config.setStartDelay(range(0, BURN * .7f));
        curls.config.setStartSpeed(range(.5f, 1.5f));
        sheet(curls);
        curls.config.rotationOverLifetime.setEnable(true);
        curls.config.rotationOverLifetime.setRoll(range(-4, 4));
        envelope(curls, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .5f, .3f, .9f, .7f, 1, 1, .7f)));
        fade(curls, 0x00FFE0A0, 0xC0FFA040, 0x80FF5A10, 0x00A01800);

        ParticleEmitter embers = burst("fire_beam/fleck", true, Math.min(40, Math.round(length * .8f) + 1),
                range(.05f, .1f), range(24, 56), 0xFFFFFFFF);
        embers.config.shape.setScale(new NumberFunction3(.5f, length, .5f));
        embers.config.setStartDelay(range(0, 24));
        embers.config.setStartSpeed(range(.1f, .6f));
        embers.config.physics.setEnable(true);
        embers.config.physics.setGravity(NumberFunction.constant(-.003f));
        sheet(embers);
        fade(embers, 0x00FFFFFF, 0xFFFFE8B0, 0xE0FFA164, 0x90E03A08, 0x00C10000);

        ParticleEmitter puffs = burst("fire_beam/smoke_twirly", false, Math.min(64, Math.round(length * 1.1f) + 1),
                range(.5f, .8f), range(60, 105), 0xFF5E4C46);
        puffs.config.shape.setScale(new NumberFunction3(.35f, length, .35f));
        puffs.config.lights.setEnable(false);
        puffs.config.setStartDelay(range(2, 14));
        puffs.config.setStartSpeed(range(.05f, .3f));
        puffs.config.physics.setEnable(true);
        puffs.config.physics.setGravity(NumberFunction.constant(-.0011f));
        sheet(puffs);
        puffs.config.rotationOverLifetime.setEnable(true);
        puffs.config.rotationOverLifetime.setRoll(range(-1.5f, 1.5f));
        envelope(puffs, new Curve(0, 1, 0, 2.6f, "age", "size", new ECBCurves(0, .38f, .3f, .7f, .7f, .92f, 1, 1)));
        fade(puffs, 0x00FFFFFF, 0x50FFE8E0, 0x38D8D0CC, 0x14C0C0C0, 0x00A0A0A0);
        slowDown(puffs, .4f);
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(curls, embers, puffs));
        return fx;
    }

    /** One burst of {@code count} particles from a box along the line. */
    private static ParticleEmitter burst(String texture, boolean additive, int count, RandomConstant size,
            RandomConstant life, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        ProjectileParticles.configureTexture(e, texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(Math.max(16, count));
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.shape.setShape(new Box());
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        c.emission.getBursts().add(b);
        return e;
    }
}
