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
import com.lowdragmc.photon.client.gameobject.emitter.particle.FacingMode;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;

/**
 * {@code push}, {@code pull} and {@code disarm}: an elementless flash of force, after QFX's Comet Flash (a soft glow,
 * a few four-point stars and sparks) in pale silver. Comet Flash has no direction, so a shock ring across the force,
 * a chevron and speed lines from the same pack run along local +Z, the way the force acts. Sizes are for a target of
 * scale 1 (about a player); the whole thing is gone within about half a second.
 */
final class ForceBurstVisual implements EffectBurstVisual {
    private static final int TINT = 0xDCE4EE;
    private static final int HOT = 0xFFFFFF;
    private static final List<ParticleEmitter> LAYERS = List.of(
            glow(), wave(0, 1.6f, 7), wave(2, 1.1f, 6), streaks(), chevron(), stars(), sparks());

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    /**
     * Smaller than the default floor: the server sends 0.6 for anything small, and a push or pull on a crowd of
     * small mobs fires one burst each, so they must stay quiet. A player (1) and larger keep their size.
     */
    @Override
    public float scale(float size) { return Math.min(2, Math.max(.35f, .35f + (size - .6f) * 1.6f)); }

    /** Glow (1): one soft flash that swells to full size as it fades (0.2 s). */
    private static ParticleEmitter glow() {
        ParticleEmitter e = burst("glow", 1, range(1.5f, 1.5f), range(4, 4), sphere(0), argb(0x70, TINT));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .1f, .28f, .8f, .6f, .93f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x60FFFFFF, 0x00FFFFFF);
        return e;
    }

    /**
     * A shock front: a ring lying across the force that widens as it travels along +Z. Quads only face the
     * emitter's own plane in local simulation space; in world space Photon would lay them in the world's XY.
     */
    private static ParticleEmitter wave(int delay, float scale, int life) {
        ParticleEmitter e = burst("ring", 1, range(scale, scale), range(life, life), along(0, 0), argb(0xA8, TINT));
        e.config.setSimulationSpace(ParticleConfig.Space.Local);
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0)));
        e.config.setStartDelay(NumberFunction.constant(delay));
        e.config.setStartSpeed(range(6, 6));
        turnToForce(e);
        e.config.renderer.setFacingMode(FacingMode.EMITTER_TRANSFORM_XY);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .25f, .75f, .6f, .95f, 1, 1)));
        fade(e, 0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF);
        slowDown(e, .3f);
        return e;
    }

    /** Speed lines streaming past the body along +Z, head first. */
    private static ParticleEmitter streaks() {
        Cone rim = along(.6f, 14);
        rim.setRadiusThickness(.3f);
        ParticleEmitter e = burst("streak", 0, range(.1f, .14f), range(4, 6), rim, argb(0xB0, TINT));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(6, 8));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0)));
        e.config.setStartSpeed(range(6, 9));
        stretch(e, 6, 0);
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        slowDown(e, .3f);
        return e;
    }

    /** A chevron shot along +Z: from the side, the arrow of the force. */
    private static ParticleEmitter chevron() {
        ParticleEmitter e = burst("chevron", 1, range(.45f, .45f), range(5, 5), along(0, 0), argb(0xA0, HOT));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0)));
        e.config.setStartSpeed(range(7, 7));
        stretch(e, 1.3f, 0);
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        slowDown(e, .15f);
        return e;
    }

    /** Stars: two or three four-point flares that pop up and shrink away. */
    private static ParticleEmitter stars() {
        ParticleEmitter e = burst("star", 0, range(.3f, .55f), range(3, 9), sphere(.3f), argb(0xF0, HOT));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(2, 3));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(-15, 15)));
        e.config.setStartSpeed(range(0, .2f));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 0, .15f, 1, .4f, 1, 1, 0)));
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF);
        return e;
    }

    /** Sparks: five to eight flecks thrown forward along the force. */
    private static ParticleEmitter sparks() {
        ParticleEmitter e = burst("dot", 0, range(.05f, .08f), range(3, 8), along(.15f, 45), argb(0xFF, HOT));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(5, 8));
        e.config.setStartSpeed(range(2, 5));
        stretch(e, 1.5f, .2f);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 0, .25f, 1, .5f, .9f, 1, 0)));
        fade(e, 0x00FFFFFF, 0xF8FFFFFF, 0x00FFFFFF);
        slowDown(e, .3f);
        return e;
    }

    // ---- shared configuration -----------------------------------------------------------------

    /** One additive, full-bright, non-looping layer of {@code force/<texture>}; {@code count} 0 means set later. */
    private static ParticleEmitter burst(String texture, int count, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "force/" + texture, true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(16);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.shape.setShape(shape);
        c.sizeOverLifetime.setEnable(false);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(Math.max(1, count)));
        c.emission.getBursts().add(b);
        return e;
    }

    /** A cone for {@link #turnToForce}: Photon cones open along local +Y. */
    private static Cone along(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    private static int argb(int alpha, int rgb) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    private static void stretch(ParticleEmitter e, float length, float velocity) {
        turnToForce(e);
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        e.config.renderer.setLengthScale(length);
        e.config.renderer.setVelocityScale(velocity);
    }

    /** Turns the layer's cone from local +Y to +Z. */
    private static void turnToForce(ParticleEmitter e) {
        e.config.shape.setRotation(new NumberFunction3(90, 0, 0));
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }

    /** Unity's speed modifier: full speed at birth, {@code end} of it at death. */
    private static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .45f, .6f, end, 1, end)));
    }
}
