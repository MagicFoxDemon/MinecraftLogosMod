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
 * Frost damage: the shared hit core in ice blue, plus the accents of UniqueProjectilesVol_5
 * {@code vfx_Hit_Icicle01}: ice shards knocked off the struck side and a breath of frost mist.
 */
final class FrostDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>(DamageBurstCore.layers(0x8CCBFF, 0xDCF4FF));
        layers.add(mist());
        layers.add(shards());
        return layers;
    }

    /** Debris_01: CrystalSheet_01 shards splashing back toward the caster, tumbling and dropping as they shrink. */
    private static ParticleEmitter shards() {
        Cone back = new Cone();
        back.setRadius(.15f);
        back.setAngle(75);
        ParticleEmitter e = layer("crystal", false, range(7, 15), back, 0xFFC4EAFF);
        // Photon cones open along local +Y; turned to −Z, back toward the source, so the body does not hide them.
        e.config.shape.setRotation(new NumberFunction3(-90, 0, 0));
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(6, 9));
        e.config.setStartSpeed(range(2.5f, 4f));
        e.config.setStartSize(new NumberFunction3(range(.15f, .21f), range(.19f, .28f), NumberFunction.constant(1)));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-18, 18));
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(.02f));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .85f, .7f, .6f, 1, .46f)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Smoke_01: a few faint SmokeSheet_04 puffs swelling outward; kept well short of Unity's second-long cloud. */
    private static ParticleEmitter mist() {
        ParticleEmitter e = layer("mist", true, range(12, 18), sphere(.15f), 0x34BAE1FF);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(3, 4));
        e.config.setStartSpeed(range(.3f, .6f));
        RandomConstant size = range(.35f, .6f);
        e.config.setStartSize(new NumberFunction3(size, size, size));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-3, 3));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .3f, .7f, .6f, .9f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** One full-bright, non-looping layer of {@code frost_damage/<texture>} bursting once at contact. */
    private static ParticleEmitter layer(String texture, boolean additive, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "frost_damage/" + texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(16);
        c.setStartColor(NumberFunction.color(color));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(1));
        c.emission.getBursts().add(b);
        return e;
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }
}
