package dev.logos.client;

import static dev.logos.client.FireBeamCore.endOn;
import static dev.logos.client.FireBeamCore.nearFade;
import static dev.logos.client.FireBeamCore.smooth;
import static dev.logos.client.LogosFx.range;
import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;

/**
 * A lasting fire beam after Piloto's FireRay: not a tube but a stream of fire. Around a thin white-gold
 * heart, two interleaved flame strips roll toward the target, their centres wandering and their widths
 * swelling in waves that travel down the line, their edges eaten by scrolling noise; a dark-red soot
 * sheath licks the outside so the flame reads against a bright sky. Along the line Photon throws stylized
 * flame curls (FireQuad_Stylized) and embers forward, and twirly dark smoke rises off it. The wand tip
 * has only a small glow (and a few curls when seen from outside); the end carries a flame head that
 * splashes curls, sparks and smoke back toward the caster once the beam stops growing. Within a couple of
 * blocks of the camera everything fades out, so the caster's view stays clear.
 */
final class FireBeamVisual {
    private static final FX OUTLET = outlet(), TERMINUS = terminus(), BODY = body();
    private FXRuntime outlet, terminus, body;
    private Vec3 lastFrom;
    private float bodyLength = -1;
    private boolean bodyClose;
    private double reached;
    private long grewAt = Long.MIN_VALUE;
    private boolean splashing = true, outletHidden, viewDown;
    private int terminusState = -1;
    private static final float OUTLET_RATE = .5f;
    private final float seed = (float) (Math.random() * 100);

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < .05) return;
        Vec3 dir = delta.scale(1 / length);
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long tick = level.getGameTime();
        double t = tick + pt;
        Vec3 eye = camera.pos;
        // The caster in first person: the wand tip sits just under the camera.
        boolean close = !Minecraft.getInstance().gameRenderer.getMainCamera().isDetached() && eye.distanceTo(from) < 3;
        viewDown = close;
        float scale = Math.max(.3f, radius / .35f);

        // Still racing out (LogosFx's reach) or settled on its full length?
        if (length > reached + .02) {
            reached = length;
            grewAt = tick;
        } else if (length < reached - 1) reached = length;
        boolean growing = tick - grewAt < 3;

        if (outlet == null) {
            outlet = emit(OUTLET, level, from, dir, scale);
            terminus = emit(TERMINUS, level, to, dir.scale(-1), scale);
            body = emit(BODY, level, from.add(to).scale(.5), dir, scale);
        }
        locate(outlet, from.add(dir.scale(.15)), dir, scale);
        locate(terminus, to.subtract(dir.scale(.1)), dir.scale(-1), scale);
        if (close != outletHidden) {
            outletHidden = close;
            rate(outlet, close ? 0 : OUTLET_RATE);
        }
        if (growing == splashing) {
            splashing = !growing;
            for (var object : terminus.objects.values()) if (object instanceof ParticleEmitter e && e.getName().equals("sparks"))
                e.runtime().emission.emissionRate.set(NumberFunction.constant(splashing ? .6f : 0));
        }
        // Down the line the head's smoke would sit on the crosshair, and while it races out of the wand its
        // curls would be left floating right in front of the caster.
        int headState = !close ? 0 : eye.distanceTo(to) < 7 ? 2 : 1;
        if (headState != terminusState) {
            terminusState = headState;
            for (var object : terminus.objects.values()) if (object instanceof ParticleEmitter e) {
                if (e.getName().equals("smoke")) e.runtime().emission.emissionRate.set(NumberFunction.constant(close ? .05f : .22f));
                if (e.getName().equals("curls")) e.runtime().emission.emissionRate.set(NumberFunction.constant(headState == 2 ? 0 : 1.1f));
            }
        }
        // The body emits along the line, beyond the camera's first blocks when the caster looks down it.
        double trim = Math.min(close ? 4 : .35, length * .5);
        float bodyLen = (float) (length - trim);
        locate(body, from.add(dir.scale(trim)).add(to).scale(.5), dir, scale);
        if (Math.abs(bodyLen - bodyLength) > .05f || close != bodyClose) {
            for (var object : body.objects.values()) if (object instanceof ParticleEmitter e) {
                e.runtime().shape.scale.set(new NumberFunction3(.45f, Math.max(.05f, bodyLen / scale), .45f));
                float rate = switch (e.getName()) {
                    case "curls" -> Math.min(14, bodyLen * .45f) * (close ? .4f : 1);
                    case "smoke" -> Math.min(5, bodyLen * .1f) * (close ? .3f : 1);
                    default -> Math.min(8, bodyLen * .22f);
                };
                e.runtime().emission.emissionRate.set(NumberFunction.constant(rate));
            }
            bodyLength = bodyLen;
            bodyClose = close;
        }
        lastFrom = from;

        float r = radius;
        Vec3[] frame = FireBeamCore.frame(dir);
        Vec3 right = frame[0], up = frame[1];
        stack.pushPose();
        stack.translate(-eye.x, -eye.y, -eye.z);
        out.submitCustomGeometry(stack, FireBeamCore.SHEATH, (pose, buf) -> FireBeamCore.strip(pose, buf, eye, from, dir,
                0, length, 3, seed, 1, 1, 1, layer(eye, from, dir, length, r * 1.9, r * .16, t, 0, nearFar(close, 3, 9), close ? .3f : .85f, right, up)));
        // The flame's coloured body is alpha-blended (it stays orange against a bright sky); the inner
        // tongue and the heart are additive glow on top.
        out.submitCustomGeometry(stack, FireBeamCore.FLAME_BODY, (pose, buf) -> FireBeamCore.strip(pose, buf, eye, from, dir,
                0, length, 4, seed, 1, 1, 1, layer(eye, from, dir, length, r * 1.45, 0, t, 0, nearFar(close, 1.2, 6.5), close ? .8f : .95f, right, up)));
        out.submitCustomGeometry(stack, FireBeamCore.FLAME, (pose, buf) -> FireBeamCore.strip(pose, buf, eye, from, dir,
                0, length, 4, seed + 3.7f, 1, .92f, .85f,
                layer(eye, from, dir, length, r * .95, 0, t * 1.3, 2.1, nearFar(close, 1.2, 5.5), .9f, right, up)));
        out.submitCustomGeometry(stack, FireBeamCore.CORE, (pose, buf) -> FireBeamCore.strip(pose, buf, eye, from, dir,
                0, length, 4, seed, 1, 1, 1, layer(eye, from, dir, length, r * .36, 0, t * 1.6, 4.4, nearFar(close, 1, 4.5), .95f, right, up)));
        out.submitCustomGeometry(stack, FireBeamCore.GLOW, (pose, buf) -> {
            float flicker = (float) (.85 + .1 * Math.sin(t * 1.9 + seed) + .05 * Math.sin(t * 5.3));
            // The outlet: a small, low glow on the wand tip (smaller still right under the camera).
            Vec3 tip = from.add(dir.scale(.06));
            FireBeamCore.sprite(pose, buf, camera, tip, r * (close ? .18f : 1.1f) * flicker, 1, .42f, .08f,
                    (close ? .25f : .85f));
            // The flame head: brighter while it races out, a steady glow on the end once settled.
            float head = growing ? 1 : .75f;
            Vec3 end = to.subtract(dir.scale(.08));
            FireBeamCore.sprite(pose, buf, camera, end, r * (growing ? 2.6f : 2.1f) * flicker, 1, .38f, .06f,
                    head * nearFade(eye, end, .8, 2.5));
            FireBeamCore.sprite(pose, buf, camera, end, r * .9f * flicker, 1, .8f, .45f, head * nearFade(eye, end, .8, 2.5));
        });
        stack.popPose();
    }

    void end() {
        if (outlet != null) { outlet.destroy(false); outlet = null; }
        if (terminus != null) { terminus.destroy(false); terminus = null; }
        if (body != null) { body.destroy(false); body = null; }
    }

    private static double[] nearFar(boolean close, double near, double far) {
        // From outside, only fade right at the camera (someone standing in the beam).
        return close ? new double[] {near, far} : new double[] {.3, 1.2};
    }

    /**
     * One flame strip: {@code width} half-width at full swell, lifted by {@code lift}, its centre wandering
     * and its width swelling in waves that run toward the target ({@code phase} keeps strips apart).
     */
    private FireBeamCore.Profile layer(Vec3 eye, Vec3 from, Vec3 dir, double length, double width, double lift,
            double t, double phase, double[] fade, float alpha, Vec3 right, Vec3 up) {
        double ph = seed + phase;
        return new FireBeamCore.Profile() {
            @Override public double width(double s) {
                double open = .22 + .78 * smooth((float) (s / (.9 + width * 1.5)));
                double head = .45 + .55 * smooth((float) ((length - s) / Math.max(.2, width * 1.6)));
                double swell = 1 + .22 * Math.sin(s * 1.9 - t * .9 + ph) + .13 * Math.sin(s * 4.3 - t * 1.7 + ph * 2);
                return width * open * head * swell;
            }

            @Override public Vec3 offset(double s) {
                double amp = width * .28 * smooth((float) (s / 2));
                double a = Math.sin(s * 1.1 - t * .5 + ph) * .7 + Math.sin(s * 2.6 - t * 1.1 + ph * 1.7) * .3;
                double b = Math.sin(s * .8 - t * .37 + ph * .6) * .6 + Math.sin(s * 2.1 - t * .9 + ph) * .4;
                return right.scale(a * amp).add(up.scale(b * amp + lift * smooth((float) (s / 1.5))));
            }

            @Override public float alpha(double s) {
                Vec3 p = from.add(dir.scale(s));
                float on = endOn(eye, p, dir);
                // The caster looks straight down it: thin the far part much harder so the crosshair stays clear.
                return alpha * nearFade(eye, p, fade[0], fade[1]) * (viewDown ? on * on * on : on);
            }
        };
    }

    // ---- Photon layers --------------------------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    private static FXRuntime emit(FX template, Level level, Vec3 at, Vec3 axis, float scale) {
        FXRuntime runtime = template.createRuntime();
        // Keep Photon's own materials; the default LATE stage loses entity depth.
        for (var object : runtime.objects.values()) if (object instanceof ParticleEmitter e)
            e.config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
        locate(runtime, at, axis, scale);
        runtime.emit(new Executor(level));
        return runtime;
    }

    /** Places {@code fx} at {@code at} with its local +Y on {@code axis}. */
    private static void locate(FXRuntime fx, Vec3 at, Vec3 axis, float scale) {
        fx.getRoot().updatePos(new Vector3f((float) at.x, (float) at.y, (float) at.z));
        fx.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) axis.x, (float) axis.y, (float) axis.z)));
        fx.getRoot().updateScale(new Vector3f(scale));
    }

    private static void rate(FXRuntime fx, float rate) {
        for (var object : fx.objects.values()) if (object instanceof ParticleEmitter e)
            e.runtime().emission.emissionRate.set(NumberFunction.constant(rate));
    }

    /** A few small flame curls spinning off the wand tip (Fire_AlphaDistort_Ramped, seen from outside only). */
    private static FX outlet() {
        ParticleEmitter curls = curls("curls", OUTLET_RATE, range(.3f, .5f), range(6, 10), cone(.04f, 25));
        curls.config.setSimulationSpace(ParticleConfig.Space.Local);
        curls.config.setStartSpeed(range(.8f, 2));
        return fx(curls);
    }

    /** The flame head on the end, thrown back toward the caster: curls, sparks (once settled) and smoke. */
    private static FX terminus() {
        ParticleEmitter curls = curls("curls", 1.1f, range(.55f, .95f), range(7, 12), cone(.15f, 55));
        curls.config.setStartSpeed(range(1.5f, 3.5f));
        slowDown(curls, .3f);
        ParticleEmitter sparks = looping("fire_impact/spark", true, .6f, range(.05f, .08f), range(6, 12), cone(.12f, 70), 0xFFFFC064);
        sparks.setName("sparks");
        sparks.config.setStartSize(new NumberFunction3(range(.3f, .5f), range(.05f, .08f), NumberFunction.constant(1)));
        sparks.config.setStartSpeed(range(4, 9));
        sparks.config.physics.setEnable(true);
        sparks.config.physics.setGravity(NumberFunction.constant(.012f));
        sparks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        sparks.config.renderer.setLengthScale(3);
        sparks.config.renderer.setVelocityScale(.05f);
        fade(sparks, 0xFFFFFFFF, 0xFFFFE0A0, 0x00FF6A1C);
        slowDown(sparks, .35f);
        ParticleEmitter smoke = smoke(.22f, range(.6f, 1), range(22, 38), cone(.2f, 45));
        smoke.config.setStartSpeed(range(.5f, 1.2f));
        return fx(curls, sparks, smoke);
    }

    /** Along the line (a box emits along its local +Y, turned onto the beam): curls, embers, rising smoke. */
    private static FX body() {
        ParticleEmitter curls = curls("curls", 4, range(.55f, .95f), range(8, 15), new Box());
        curls.config.setStartSpeed(range(2, 4));
        ParticleEmitter embers = looping("fire_beam/fleck", true, 2, range(.05f, .1f), range(10, 28), new Box(), 0xFFFFFFFF);
        embers.setName("embers");
        embers.config.setStartSpeed(range(2.5f, 5.5f));
        embers.config.physics.setEnable(true);
        embers.config.physics.setGravity(NumberFunction.constant(-.006f));
        sheet(embers);
        fade(embers, 0x00FFFFFF, 0xFFFFF4D0, 0xFFFFA164, 0xC0E03A08, 0x00C10000);
        slowDown(embers, .2f);
        ParticleEmitter smoke = smoke(1, range(.5f, .9f), range(20, 40), new Box());
        smoke.config.setStartSpeed(range(.4f, 1.4f));
        return fx(curls, embers, smoke);
    }

    /** FireQuad_Stylized curls: additive, one random frame each, spinning and swelling as they burn yellow to red. */
    private static ParticleEmitter curls(String name, float rate, RandomConstant size, RandomConstant life, IShape shape) {
        ParticleEmitter e = looping("fire_beam/flame_quad", true, rate, size, life, shape, 0xFFFFFFFF);
        e.setName(name);
        sheet(e);
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-4, 4));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .25f, .17f, .65f, .5f, .9f, 1, 1)));
        fade(e, 0x00FFF0B0, 0xF0FFC250, 0xB0FF7A1A, 0x50E0300A, 0x00901000);
        return e;
    }

    /** Smoke_Twirly_Alpha: dark-red soot puffs that rise, swell and thin out. */
    private static ParticleEmitter smoke(float rate, RandomConstant size, RandomConstant life, IShape shape) {
        ParticleEmitter e = looping("fire_beam/smoke_twirly", false, rate, size, life, shape, 0xFFFFFFFF);
        e.setName("smoke");
        sheet(e);
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(-.0025f));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-2, 2));
        envelope(e, new Curve(0, 1, 0, 2.3f, "age", "size", new ECBCurves(0, .3f, .3f, .6f, .6f, .85f, 1, 1)));
        fade(e, 0x00B03010, 0xA06A1408, 0x703A0C08, 0x30201010, 0x00181414);
        slowDown(e, .3f);
        return e;
    }

    private static ParticleEmitter looping(String texture, boolean additive, float rate, RandomConstant size,
            RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        ProjectileParticles.configureTexture(e, texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(true);
        c.setMaxParticles(400);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(rate));
        return e;
    }

    private static FX fx(ParticleEmitter... layers) {
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(layers));
        return fx;
    }

    static Cone cone(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    static Sphere sphere(float radius) {
        Sphere s = new Sphere();
        s.setRadius(radius);
        return s;
    }

    /** One random fixed frame of a 2×2 sheet. */
    static void sheet(ParticleEmitter e) {
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
    }

    static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }

    /** Unity's speed modifier: full speed at birth, {@code end} of it at death. */
    static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed",
                new ECBCurves(0, 1, .3f, .45f, .6f, end, 1, end)));
    }

}
