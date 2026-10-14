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
 * Physical damage: the shared hit core in white gold, cut through by two quick blade strokes that cross in an X —
 * Impact_01 of UniqueProjectilesVol_5 {@code vfx_Hit_Slash01–03}. The core already has Slash's spikes, sparks and
 * lens flare, so only the slash is added.
 */
final class PhysicalDamageVisual implements EffectBurstVisual {
    private static final int TINT = 0xFFF0C6;
    private static final int FRAMES = 8;
    private static final List<ParticleEmitter> LAYERS = build();

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }

    private static List<ParticleEmitter> build() {
        List<ParticleEmitter> layers = new ArrayList<>(DamageBurstCore.layers(TINT, 0xFFFFF5));
        layers.add(slash(0, 1, range(18, 42)));
        layers.add(slash(2, .8f, range(-42, -18)));
        return layers;
    }

    /**
     * One blade stroke sliding through its window within a quarter second. Unity scrolls SlashShape_01 under the
     * VerticalBeam_03 mask by custom data; {@code physical_damage/slash} bakes that scroll into {@value #FRAMES}
     * frames played once over the particle's life.
     */
    private static ParticleEmitter slash(int delay, float scale, RandomConstant roll) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "physical_damage/slash", true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(4);
        c.setStartDelay(NumberFunction.constant(delay));
        c.setStartColor(NumberFunction.color(0xFF000000 | TINT));
        c.setStartSize(new NumberFunction3(range(2.1f * scale, 2.3f * scale), range(.21f * scale, .24f * scale), NumberFunction.constant(1)));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(range(5, 5));
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), roll));
        c.shape.setShape(sphere(0));
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(1));
        c.emission.getBursts().add(b);
        // Full size throughout; the stroke's own travel and the fade carry the motion.
        c.sizeOverLifetime.setEnable(false);
        c.colorOverLifetime.setEnable(true);
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF)));
        c.uvAnimation.setEnable(true);
        c.uvAnimation.setTiles(new Vector2i(1, FRAMES));
        c.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, FRAMES - .01f, "age", "frame",
                new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return e;
    }
}
