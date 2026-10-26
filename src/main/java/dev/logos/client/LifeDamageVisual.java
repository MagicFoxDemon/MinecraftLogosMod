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

/**
 * Life damage: the shared hit core in the green of the life impact, plus life taken rather than given, the
 * reverse of the heal, whose leaves rise. The life impact's bloom opens and wilts shut, browning, and a few
 * leaves are torn off the struck side and wither as they tumble and fall. Reuses the life impact textures.
 */
final class LifeDamageVisual implements EffectBurstVisual {
    private static final int TINT = 0x4AD35C;
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>(DamageBurstCore.layers(TINT, 0xE7FFA0));
        layers.add(wilt());
        layers.add(leaves());
        return layers;
    }

    /** The petal bloom: open at contact, then closing in on itself and browning over a third of a second. */
    private static ParticleEmitter wilt() {
        ParticleEmitter e = layer("petal", true, range(.8f, .9f), range(7, 7), sphere(0), 0xA06EE070);
        e.config.emission.getBursts().add(burst(0, 1));
        Curve close = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .7f, .2f, 1, .6f, .6f, 1, .2f));
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(close, close, close));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(NumberFunction.constant(50));
        fade(e, 0xFFFFFFFF, 0xFFD8F0A0, 0x80A08040, 0x00704020);
        return e;
    }

    /** Leaves torn off back toward the attacker, tumbling and falling as they turn from green to dead brown. */
    private static ParticleEmitter leaves() {
        Cone back = new Cone();
        back.setRadius(.18f);
        back.setAngle(70);
        // Ordinary alpha keeps the leaf silhouette and its veins over the additive core.
        ParticleEmitter e = layer("leaf", false, range(.13f, .2f), range(14, 22), back, 0xFFFFFFFF);
        // Photon cones open along local +Y; turned to open along −Z, back toward the attacker.
        e.config.shape.setRotation(new NumberFunction3(-90, 0, 0));
        e.config.emission.getBursts().add(burst(1, 5));
        e.config.setStartSpeed(range(1.2f, 2.2f));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-260, 260));
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .45f, .6f, .25f, 1, .25f)));
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(.01f));
        fade(e, 0xFFA3FF73, 0xFFD8E070, 0xE0A87838, 0x00785028);
        return e;
    }

    /** One full-bright, non-looping layer of {@code life_impact/<texture>}, bursts added by the caller. */
    private static ParticleEmitter layer(String texture, boolean additive, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "life_impact/" + texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(30);
        c.setLooping(false);
        c.setMaxParticles(8);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
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
