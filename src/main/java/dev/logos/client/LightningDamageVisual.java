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
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import org.joml.Vector2i;

/**
 * Lightning damage: the shared hit core in electric blue, plus the accents of UniqueProjectilesVol_5
 * {@code vfx_Hit_Lightning01}: a six-pointed flash that shrinks away in a fifth of a second, and loose
 * arcs flickering around the body for the first quarter second.
 */
final class LightningDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>(DamageBurstCore.layers(0x6AAEFF, 0xBCEBFF));
        layers.add(flash());
        layers.add(arcs());
        return layers;
    }

    /** Flare_01 (ImpactShape_02): full at contact, shrinking and fading to nothing within four ticks. */
    private static ParticleEmitter flash() {
        ParticleEmitter e = layer("flash", true, range(.9f, 1.05f), range(4, 4), 0, 0xC06AAEFF);
        e.config.emission.getBursts().add(burst(0, 1));
        Curve shrink = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .7f, .7f, .3f, 1, 0));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(shrink, shrink, shrink));
        fade(e, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /**
     * LooseEnergy_02 (LooseEnergySheet_01): a frame of the 2×2 arc sheet at a time, re-struck over the
     * first seven ticks, each arc growing and flickering on and off. Alpha-blended as in the source so the
     * strokes stay crisp over the additive core instead of washing out to white; a saturated blue reads
     * against both the pale core and a daylight sky.
     */
    private static ParticleEmitter arcs() {
        ParticleEmitter e = layer("arcs", false, range(.6f, .9f), range(3, 5), .25f, 0xFF4A9BFF);
        for (int[] b : new int[][] {{0, 2}, {1, 2}, {2, 2}, {3, 1}, {4, 1}, {6, 1}}) e.config.emission.getBursts().add(burst(b[0], b[1]));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        // Grows from 0.38 of its size to full, as in the source.
        Curve grow = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .38f, .33f, .6f, .66f, .8f, 1, 1));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(grow, grow, grow));
        fade(e, 0xFFFFFFFF, 0x30FFFFFF, 0xFFFFFFFF, 0x20FFFFFF, 0xC0FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** One full-bright, non-looping layer of {@code lightning_damage/<texture>}, bursts added by the caller. */
    private static ParticleEmitter layer(String texture, boolean additive, RandomConstant size, RandomConstant life, float radius, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "lightning_damage/" + texture, additive);
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
        c.shape.setShape(sphere(radius));
        c.emission.setEmissionRate(NumberFunction.constant(0));
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
