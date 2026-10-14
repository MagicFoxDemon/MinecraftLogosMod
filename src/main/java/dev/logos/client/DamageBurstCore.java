package dev.logos.client;

import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.ArrayList;
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
import org.joml.Vector2i;

/**
 * The part every damage burst shares — "a spell landed on this body and hurt it" — rebuilt from
 * UniqueProjectilesVol_5 {@code vfx_Hit_Wind02}: a swirl at the centre, crescent wind arcs flung outward,
 * a crossed lens flare, spikes and sparks thrown out of the far side. Element visuals tint it with their
 * own colours and add their accents. Sizes are for a target of scale 1 (about a player).
 */
final class DamageBurstCore {
    private DamageBurstCore() {}

    /** {@code tint}: the element's colour; {@code hot}: its near-white centre. Both opaque RGB. */
    static List<ParticleEmitter> layers(int tint, int hot) {
        List<ParticleEmitter> layers = new ArrayList<>();
        layers.add(glow(tint));
        layers.add(swirl(tint));
        layers.add(arcs(tint));
        layers.add(flare(hot, 0));
        layers.add(flare(hot, 90));
        layers.add(spikes(hot));
        layers.add(sparks(tint));
        return layers;
    }

    private static ParticleEmitter glow(int tint) {
        ParticleEmitter e = burst("glow", 1, range(1.1f, 1.3f), range(7, 7), sphere(0), argb(0x60, tint));
        envelope(e, pulse(.15f));
        fade(e, 0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** SwirlCenter_01: grows from nothing while it spins, holds, then fades (0 → 1 by 21%, 1 until 78%). */
    private static ParticleEmitter swirl(int tint) {
        ParticleEmitter e = burst("swirl", 1, range(.72f, .8f), range(7, 7), sphere(0), argb(0x80, tint));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .05f, .2f, .7f, .5f, .95f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xC0FFFFFF, 0x50FFFFFF, 0x00FFFFFF);
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(NumberFunction.constant(-60));
        return e;
    }

    /** WindStripes_01: three to six crescents flung outward, growing as they slow. */
    private static ParticleEmitter arcs(int tint) {
        ParticleEmitter e = burst("arc", 0, range(.5f, .65f), range(7, 11), sphere(.25f), argb(0xFF, tint));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(4, 6));
        e.config.setStartSpeed(range(.8f, 1.2f));
        // Curve values are fractions of [lower, upper]: 0.44 → 1.7 times the start size.
        envelope(e, new Curve(0, 1, 0, 1.7f, "age", "size", new ECBCurves(0, .26f, .25f, .62f, .6f, .9f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        slowDown(e, .07f);
        return e;
    }

    /** LensFlare_03/04: a thin streak and its upright twin, shrinking away within a quarter second. */
    private static ParticleEmitter flare(int hot, float roll) {
        ParticleEmitter e = burst("flare", 1, range(1, 1), range(5, 5), sphere(0), argb(0xD0, hot));
        e.config.setStartSize(new NumberFunction3(range(1.7f, 1.9f), range(.21f, .24f), NumberFunction.constant(1)));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(roll)));
        envelope(e, new Curve(0, 1, 0, 1.5f, "age", "size", new ECBCurves(0, 1, .3f, .8f, .7f, .27f, 1, 0)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
        return e;
    }

    /** Spikes_01: a beat after contact, a few sharp spikes splash back off the struck side. */
    private static ParticleEmitter spikes(int hot) {
        Cone out = back(.12f, 80);
        ParticleEmitter e = burst("spike", 0, range(1, 1), range(3, 5), out, argb(0xF0, hot));
        faceBack(e);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(2, 4));
        e.config.setStartDelay(NumberFunction.constant(1));
        e.config.setStartSpeed(range(.8f, 1.2f));
        e.config.setStartSize(new NumberFunction3(range(.14f, .2f), range(.15f, .2f), NumberFunction.constant(1)));
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        e.config.renderer.setLengthScale(4);
        e.config.renderer.setVelocityScale(0);
        fade(e, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Sparks_01: a spray of flecks from a 2×2 sheet splashing back off the struck side, slowing as they fly. */
    private static ParticleEmitter sparks(int tint) {
        ParticleEmitter e = burst("sparks", 18, range(.05f, .09f), range(8, 13), back(.18f, 85), argb(0xF0, tint));
        faceBack(e);
        e.config.setStartSpeed(range(4f, 6f));
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        e.config.renderer.setLengthScale(2.5f);
        e.config.renderer.setVelocityScale(.06f);
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .9f, .7f, .6f, 1, .46f)));
        fade(e, 0x40FFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        slowDown(e, .2f);
        return e;
    }

    // ---- shared configuration -----------------------------------------------------------------

    /** One additive, full-bright, non-looping layer of {@code damage/<texture>}; {@code count} 0 means set later. */
    private static ParticleEmitter burst(String texture, int count, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "damage/" + texture, true);
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
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(Math.max(1, count)));
        c.emission.getBursts().add(b);
        return e;
    }

    /** A cone for {@link #faceBack}. */
    private static Cone back(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    /**
     * Photon cones open along local +Y; this one is turned to open along −Z, back toward whatever the
     * effect came from, so the struck body does not hide the spray.
     */
    private static void faceBack(ParticleEmitter e) {
        e.config.shape.setRotation(new NumberFunction3(-90, 0, 0));
    }

    private static int argb(int alpha, int rgb) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    /** Peaks at {@code peak} of its life and shrinks to a fifth; no layer is cut off at full size. */
    private static Curve pulse(float peak) {
        return new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .5f, peak / 2, .9f, peak, 1, 1, .2f));
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
