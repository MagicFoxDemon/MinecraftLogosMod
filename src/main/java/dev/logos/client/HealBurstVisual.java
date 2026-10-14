package dev.logos.client;

import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;

/**
 * {@code heal}: one bloom of healing, after Hovl's {@code Heal aura}. The aura there loops; here it plays
 * once and is gone within a second: a soft green halo, a wind swirl and a light ring spreading round the
 * lower body, leaves opening upward, light trails curling round the body and motes drifting up. Healing
 * green with the prefab's gold, never the element's colour. The burst has no direction, so local +Z is up
 * and the origin is the middle of the body. Sizes are for a target of scale 1 (about a player).
 */
final class HealBurstVisual implements EffectBurstVisual {
    /** Unity's petals climb from the feet; lifted a little so they do not sink into the ground. */
    private static final float FEET = -.5f;
    private static final List<ParticleEmitter> LAYERS = List.of(
            halo(), swirl(), ring(0, 2.4f, 0xFF7DFF6E), ring(3, 1.8f, 0xB0FFE9A8),
            leaves(), streaks(), trails(), motes());

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    /** Rainbow: a faint green Aura1 glow swelling round the body. */
    private static ParticleEmitter halo() {
        ParticleEmitter e = layer("halo", true, 1, range(2, 2), range(12, 12), sphere(0), 0x3896FF80);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .3f, .8f, .6f, .95f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** CoreWind: a green wind disc opening under the body and spinning out. */
    private static ParticleEmitter swirl() {
        ParticleEmitter e = layer("swirl", true, 1, range(1.6f, 1.6f), range(12, 12), sphere(0), 0x904CE860);
        flat(e);
        e.config.shape.setPosition(new NumberFunction3(0, 0, FEET));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .1f, .25f, .7f, .5f, .9f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        spin(e, NumberFunction.constant(-20));
        return e;
    }

    /** ShockWave: a thin light line racing outward along the ground, its glow trailing inward. */
    private static ParticleEmitter ring(int delay, float size, int color) {
        ParticleEmitter e = layer("ring", true, 1, range(size, size), range(13, 13), sphere(0), color);
        flat(e);
        e.config.shape.setPosition(new NumberFunction3(0, 0, FEET));
        e.config.setStartDelay(NumberFunction.constant(delay));
        // Curve values are fractions of [lower, upper]: from a sixth of the size, fast at first, easing out.
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .15f, .2f, .65f, .5f, .9f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xB0FFFFFF, 0x40FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** The second Heal aura layer: leaves thrown up and out from round the feet, opening like a flower. */
    private static ParticleEmitter leaves() {
        ParticleEmitter e = layer("leaf", false, 0, range(.3f, .45f), range(12, 18), up(.45f, 50), 0xFF9BEFA0);
        e.config.shape.setPosition(new NumberFunction3(0, 0, FEET));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(14, 18));
        e.config.setStartSpeed(range(2, 3.2f));
        spin(e, range(-8, 8));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .4f, .2f, 1, .7f, .9f, 1, .3f)));
        // Unity's petals turn from the prefab's gold to white as they climb.
        fade(e, 0x00FFC860, 0xFFFFE6A0, 0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF);
        slowDown(e, .15f);
        return e;
    }

    /** The first Heal aura layer: small leaves shooting straight up round the body, drawn as streaks. */
    private static ParticleEmitter streaks() {
        ParticleEmitter e = layer("leaf", true, 0, range(.06f, .1f), range(6, 9), up(.7f, 6), 0xFF68FFCC);
        e.config.shape.setPosition(new NumberFunction3(0, 0, FEET + .1f));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(8, 11));
        e.config.setStartDelay(range(0, 4));
        e.config.setStartSpeed(range(2, 3.5f));
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        e.config.renderer.setLengthScale(3);
        e.config.renderer.setVelocityScale(0);
        fade(e, 0x00FFA700, 0xFFFFE08A, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** TrailsRound: a few Path2 light trails curling round the body as they rise. */
    private static ParticleEmitter trails() {
        ParticleEmitter e = layer("trail", true, 0, range(.55f, .75f), range(10, 14), up(.15f, 60), 0x9086F07A);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(3, 5));
        e.config.setStartDelay(range(0, 2));
        e.config.setStartSpeed(range(.4f, .8f));
        spin(e, new RandomConstant(10, 16));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .5f, .3f, .85f, .6f, 1, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Sparks: in Unity they fall inward; here, once the bloom is out, motes of light drift up and fade. */
    private static ParticleEmitter motes() {
        ParticleEmitter e = layer("glow", true, 0, range(.06f, .12f), range(14, 24), up(.6f, 30), 0xFF68FFCC);
        e.config.shape.setPosition(new NumberFunction3(0, 0, FEET + .2f));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(10, 13));
        EmissionSetting.Burst late = new EmissionSetting.Burst();
        late.time = 4;
        late.setCount(new RandomConstant(5, 7));
        e.config.emission.getBursts().add(late);
        e.config.setStartSpeed(range(.8f, 1.6f));
        fade(e, 0x00FFA700, 0xFFFFE08A, 0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF);
        slowDown(e, .35f);
        return e;
    }

    // ---- shared configuration -----------------------------------------------------------------

    /** One full-bright, non-looping layer of {@code heal/<texture>}; {@code count} 0 means set later. */
    private static ParticleEmitter layer(String texture, boolean additive, int count, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "heal/" + texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(32);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.shape.setShape(shape);
        // Photon cones open along local +Y; turned to local +Z, which the entry points up here.
        if (shape instanceof Cone) c.shape.setRotation(new NumberFunction3(90, 0, 0));
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(Math.max(1, count)));
        c.emission.getBursts().add(b);
        return e;
    }

    /** A cone opening upward (see {@link #layer}). */
    private static Cone up(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    /** Lies flat on the ground instead of facing the camera. */
    private static void flat(ParticleEmitter e) {
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.Horizontal);
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }

    /** Degrees per tick about the view axis. */
    private static void spin(ParticleEmitter e, NumberFunction roll) {
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(roll);
    }

    /** Unity's speed modifier: full speed at birth, {@code end} of it at death. */
    private static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .45f, .6f, end, 1, end)));
    }
}
