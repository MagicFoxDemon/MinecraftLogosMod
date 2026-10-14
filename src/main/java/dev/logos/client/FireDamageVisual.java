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
 * Fire damage: the shared hit core in flame orange, plus the accents of UniqueProjectilesVol_5
 * {@code vfx_Hit_Fireball01}: flame tongues bursting off the struck side, a few embers drifting up, and a
 * brief dark smoke puff left behind. All of it is over within about a second and stays under the core.
 */
final class FireDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>();
        layers.add(smoke());
        layers.addAll(DamageBurstCore.layers(0xFF9D22, 0xFFE2AB));
        layers.add(flames());
        layers.add(embers());
        return layers;
    }

    /** Flames_01: tongues from a 2×2 sheet flung outward and braking hard, yellow-orange cooling to red. */
    private static ParticleEmitter flames() {
        ParticleEmitter e = burst("fire_damage/flames", 8, range(.4f, .55f), range(7, 12), back(.1f, 75), 0xFFFFFFFF, true);
        faceBack(e);
        e.config.setStartSize(new NumberFunction3(range(.4f, .55f), range(.3f, .42f), NumberFunction.constant(1)));
        e.config.setStartSpeed(range(3f, 4.2f));
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        e.config.renderer.setLengthScale(1.6f);
        e.config.renderer.setVelocityScale(0);
        sheet(e);
        // Unity: full size at birth, 0.46 of it at death.
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .85f, .7f, .6f, 1, .46f)));
        fade(e, 0x00FFC860, 0xC0FFA030, 0xC0FF9022, 0x80FF6A14, 0x00FF5010);
        slowDown(e, .1f);
        return e;
    }

    /** Fire's half of Sparks_01: a handful of embers that keep rising after the burst has gone. */
    private static ParticleEmitter embers() {
        ParticleEmitter e = burst("damage/sparks", 7, range(.07f, .11f), range(12, 20), sphere(.2f), 0xFFFFB347, true);
        e.config.setStartDelay(range(1, 3));
        e.config.setStartSpeed(range(.4f, .9f));
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(-.006f));
        sheet(e);
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF, 0xC0FF7A1C, 0x00FF4A10);
        return e;
    }

    /** Smoke_01: two or three grey puffs that swell and climb while the core fades, lit by the world. */
    private static ParticleEmitter smoke() {
        ParticleEmitter e = burst("fire_damage/smoke", 0, range(.38f, .55f), range(16, 24), sphere(.18f), 0xFF3A3A3A, false);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(2, 3));
        e.config.lights.setEnable(false);
        e.config.setStartDelay(range(1, 3));
        e.config.setStartSpeed(range(.2f, .4f));
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(-.004f));
        sheet(e);
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-3, 3));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .3f, .65f, .7f, .9f, 1, 1)));
        fade(e, 0x00FFFFFF, 0x60FFFFFF, 0x50B0B0B0, 0x00808080);
        return e;
    }

    // ---- configuration --------------------------------------------------------------------------

    /** One non-looping layer of {@code textures/vfx/<texture>}; {@code count} 0 means set later. */
    private static ParticleEmitter burst(String texture, int count, RandomConstant size, RandomConstant life, IShape shape,
            int color, boolean additive) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, texture, additive);
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
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(Math.max(1, count)));
        c.emission.getBursts().add(b);
        return e;
    }

    /** One random fixed frame of a 2×2 sheet. */
    private static void sheet(ParticleEmitter e) {
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
    }

    private static Cone back(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    /** Photon cones open along local +Y; turned to −Z, back toward the caster, so the body does not hide them. */
    private static void faceBack(ParticleEmitter e) {
        e.config.shape.setRotation(new NumberFunction3(-90, 0, 0));
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
