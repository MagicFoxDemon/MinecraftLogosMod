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
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.platform.DestFactor;
import org.joml.Vector2i;

/**
 * Void damage: the shared hit core in violet, opened over a brief rift into the starfield (the void's
 * mark) that collapses as the core fades, with a ring and motes drawn into it and a wisp of ghostly
 * smoke left behind. Accents from UniqueProjectilesVol_5 {@code vfx_Hit_Ghostly01–03} and
 * {@code vfx_Hit_Orb18_purple}. Sizes are for a target of scale 1 (about a player).
 */
final class VoidDamageVisual implements EffectBurstVisual {
    private static final int VIOLET = 0x9A28FF;
    private static final int PALE = 0xD5A8FF;
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>();
        // The rift goes first so the core's additive light is drawn over the dark starfield.
        layers.add(rift());
        layers.addAll(DamageBurstCore.layers(VIOLET, PALE));
        layers.add(ring());
        layers.add(motes());
        layers.add(smoke());
        return layers;
    }

    /**
     * Orb18 DarkBackground_01 as a torn pocket of starfield: opens with the hit, holds while the swirl
     * spins, then collapses to nothing a little before the core is gone.
     */
    private static ParticleEmitter rift() {
        ParticleEmitter e = layer("rift", false, 1, range(.42f, .48f), range(9, 9), sphere(0), argb(0xFF, VIOLET));
        for (var material : e.config.renderer.getMaterials()) material.setMaterial(new VoidDamageMaterial());
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .35f, .1f, .85f, .2f, 1, .55f, 1,
                .7f, .8f, .85f, .4f, .95f, .1f, 1, 0)));
        fade(e, 0x00FFFFFF, 0xE8FFFFFF, 0xE8FFFFFF, 0xA0FFFFFF, 0x00FFFFFF);
        spin(e, 25);
        return e;
    }

    /** Ghostly Shockwave_01 run backwards: the ring closes in on the rift instead of spreading out. */
    private static ParticleEmitter ring() {
        ParticleEmitter e = layer("ring", true, 1, range(1.05f, 1.15f), range(7, 7), sphere(0), argb(0xA0, VIOLET));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .4f, .7f, .7f, .45f, 1, .25f)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF);
        spin(e, 20);
        return e;
    }

    /** Pale motes from around the body falling into the rift. */
    private static ParticleEmitter motes() {
        ParticleEmitter e = layer("mote", true, 10, range(.035f, .06f), range(6, 9), sphere(.5f), argb(0xE0, PALE));
        // Negative speed pulls them in from the sphere; 1.5 blocks a second covers it in about a third of a second.
        e.config.setStartSpeed(range(-1.8f, -1.3f));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .6f, .3f, 1, .7f, .8f, 1, .2f)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Ghostly Smoke_01: a few dim violet puffs that thin out after the hit, shortened to stay brief. */
    private static ParticleEmitter smoke() {
        ParticleEmitter e = layer("smoke", false, 3, range(.22f, .4f), range(12, 16), sphere(.18f), argb(0x60, 0x552A90));
        e.config.setStartSpeed(range(.05f, .2f));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .3f, .65f, .65f, .9f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x60FFFFFF, 0x00FFFFFF);
        spin(e, 3);
        return e;
    }

    // ---- configuration ------------------------------------------------------------------------

    /** One full-bright, non-looping burst of {@code void_damage/<texture>}. */
    private static ParticleEmitter layer(String texture, boolean additive, int count, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "void_damage/" + texture, additive);
        if (!additive) for (var material : c.renderer.getMaterials()) material.getBlendMode().setDstColorFactor(DestFactor.ONE_MINUS_SRC_ALPHA);
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
        b.setCount(NumberFunction.constant(count));
        c.emission.getBursts().add(b);
        return e;
    }

    private static int argb(int alpha, int rgb) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }

    /** Degrees per tick. */
    private static void spin(ParticleEmitter e, float roll) {
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(NumberFunction.constant(roll));
    }
}
