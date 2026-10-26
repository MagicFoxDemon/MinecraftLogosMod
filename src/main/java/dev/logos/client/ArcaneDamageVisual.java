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
import org.joml.Vector2i;

/**
 * Arcane damage: the shared hit core in the violet of the arcane impact, plus the arcane ray's own marks: an
 * upright four-point glint that snaps shut within a fifth of a second, and two or three runes knocked loose
 * from the struck side, drifting back toward the caster as they go out. Reuses the arcane impact and ray
 * textures.
 */
final class ArcaneDamageVisual implements EffectBurstVisual {
    private static final int TINT = 0xD35DFF;
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>(DamageBurstCore.layers(TINT, 0xFBE0FF));
        layers.add(glint());
        layers.add(runes());
        return layers;
    }

    /** The arcane impact's cross, upright and taller than wide, full at contact and gone in four ticks. */
    private static ParticleEmitter glint() {
        ParticleEmitter e = layer("arcane_impact/cross", range(1, 1), range(4, 4), sphere(0), 0xD0F5C2FF);
        e.config.setStartSize(new NumberFunction3(range(.55f, .65f), range(.95f, 1.1f), NumberFunction.constant(1)));
        e.config.emission.getBursts().add(burst(0, 1));
        Curve shrink = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .75f, .7f, .3f, 1, 0));
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(shrink, shrink, shrink));
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** A frame of the 3×3 rune sheet each, flung back off the struck side a beat after contact, slowing and flickering out. */
    private static ParticleEmitter runes() {
        Cone back = new Cone();
        back.setRadius(.15f);
        back.setAngle(55);
        ParticleEmitter e = layer("arcane_ray/runes", range(.18f, .24f), range(12, 18), back, 0xF0F5C2FF);
        // Photon cones open along local +Y; turned to open along −Z, back toward the caster.
        e.config.shape.setRotation(new NumberFunction3(-90, 0, 0));
        e.config.emission.getBursts().add(burst(1, 2));
        e.config.emission.getBursts().add(burst(3, 1));
        e.config.setStartSpeed(range(.9f, 1.5f));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(-25, 25)));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(3, 3));
        e.config.uvAnimation.setStartFrame(range(0, 8.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .4f, .6f, .1f, 1, .1f)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x60FFFFFF, 0xD0FFFFFF, 0x30FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** One additive, full-bright, non-looping layer of {@code textures/vfx/<texture>}, bursts added by the caller. */
    private static ParticleEmitter layer(String texture, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, texture, true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(8);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0)));
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        c.physics.setEnable(false);
        return e;
    }

    private static EmissionSetting.Burst burst(int tick, int count) {
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = tick;
        b.setCount(NumberFunction.constant(count));
        return b;
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }
}
