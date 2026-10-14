package dev.logos.client;

import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
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
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

/**
 * {@code shield} taking hold: the moment a ward is raised. The lasting sphere is {@link WardSphereVisual}, which
 * winds itself up; this only marks the cast (also when a held shield is topped up) with a pale flash and a scatter
 * of honeycomb cells lighting up where the sphere will be, in the sphere's elementless white.
 */
final class WardBurstVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = List.of(
            layer("ward/glow", 1, .03f, 1.9f, 2.1f, range(0, 0), range(5, 7), 0, 0x30E4ECF6),
            layer("ward/hex", 24, .9f, .12f, .2f, range(0, .15f), range(8, 13), 2, 0xE0E4ECF6),
            layer("ward/glow", 10, .6f, .08f, .14f, range(.6f, 1.4f), range(8, 14), 0, 0xF0FFFFFF));

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    /** {@code radius} is where the burst starts, in the target's scale; {@code delay} spreads the cells over a few ticks. */
    private static ParticleEmitter layer(String texture, int count, float radius, float min, float max,
            RandomConstant speed, RandomConstant life, int delay, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, texture, true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(size(min, max));
        c.setStartSpeed(speed);
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        var shape = sphere(radius);
        shape.setRadiusThickness(0);
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        if (delay > 0) {
            b.cycles = 3;
            b.interval = delay;
            b.setCount(NumberFunction.constant(count / 3));
        }
        c.emission.getBursts().add(b);
        c.colorOverLifetime.setEnable(true);
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0xFFFFFFFF, 0xB0FFFFFF, 0x00FFFFFF)));
        // Pop in, hold, then shrink away.
        Curve pop = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .2f, 1, .7f, .9f, 1, .3f));
        c.sizeOverLifetime.setEnable(true);
        c.sizeOverLifetime.setSize(new NumberFunction3(pop, pop, pop));
        c.physics.setEnable(false);
        return e;
    }
}
