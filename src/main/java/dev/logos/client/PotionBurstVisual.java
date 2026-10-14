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
import dev.logos.runtime.Vfx;
import org.joml.Vector2i;

/**
 * {@code potion}: a puff of potion smoke that swells around the body and is gone within a second, rebuilt
 * from Piloto's {@code 18_PoisonSkull} without the skull: a dark soft backing and a glow (the Fuzz layers),
 * smoke puffs (SmokePuff), a few bubbles that rise and pop (Toxic_Bubbles) and glints (Glowdot). The three
 * kinds share the structure and differ in colour: poison green for harmful effects, pale pink for beneficial
 * ones, light lavender for the rest (see {@link Vfx#potion}). Sizes are for a target of scale 1 (about a player).
 */
final class PotionBurstVisual implements EffectBurstVisual {
    /** One colour set: {@code tint} the smoke, {@code hot} its bright glints, {@code deep} the backing shade. */
    private record Palette(int tint, int hot, int deep) {}

    private static final List<ParticleEmitter> HARMFUL = build(new Palette(0x9AD03A, 0xE4FF6A, 0x183A04));
    private static final List<ParticleEmitter> BENEFICIAL = build(new Palette(0xFFB0D2, 0xFFE8F2, 0x5C2440));
    private static final List<ParticleEmitter> NEUTRAL = build(new Palette(0xC4A6FF, 0xEFE6FF, 0x2C1A4E));

    final Vfx.Burst kind;

    PotionBurstVisual(Vfx.Burst kind) {
        this.kind = kind;
    }

    @Override
    public List<ParticleEmitter> layers() {
        return switch (kind) {
            case POTION_HARMFUL -> HARMFUL;
            case POTION_BENEFICIAL -> BENEFICIAL;
            default -> NEUTRAL;
        };
    }

    private static List<ParticleEmitter> build(Palette p) {
        List<ParticleEmitter> layers = new ArrayList<>();
        layers.add(backing(p));
        layers.add(body(p));
        layers.add(glow(p));
        layers.add(puffs(p));
        layers.add(bubbles(p));
        layers.add(glints(p));
        return layers;
    }

    /** FuzzAlpha: a soft dark shade of the colour behind the smoke, so it reads in daylight; gone by mid-life. */
    private static ParticleEmitter backing(Palette p) {
        ParticleEmitter e = burst("fuzz", 1, range(2.1f, 2.1f), range(13, 13), sphere(0), argb(0x70, p.deep), false);
        e.config.lights.setEnable(false);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .55f, .2f, .95f, .6f, 1, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** The coloured smoke itself: a handful of puffs pushed out from the body, swelling while they thin out. */
    private static ParticleEmitter body(Palette p) {
        ParticleEmitter e = burst("smoke", 0, range(.9f, 1.2f), range(12, 18), sphere(.35f), argb(0xA0, p.tint), false);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(5, 7));
        e.config.setStartSpeed(range(.7f, 1.1f));
        smoke(e);
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x90FFFFFF, 0x00FFFFFF);
        return e;
    }

    /** FuzzAdd: a faint glow of the colour at the heart of the cloud. */
    private static ParticleEmitter glow(Palette p) {
        ParticleEmitter e = burst("fuzz", 1, range(1.6f, 1.6f), range(10, 10), sphere(0), argb(0x40, p.tint), true);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .6f, .2f, 1, .6f, 1, 1, .9f)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Smoke_Generic_Add: fainter puffs over the body, added on top so the cloud glows a little. */
    private static ParticleEmitter puffs(Palette p) {
        ParticleEmitter e = burst("smoke", 0, range(.7f, 1f), range(10, 15), sphere(.3f), argb(0x48, p.tint), true);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(3, 5));
        e.config.setStartSpeed(range(.4f, .8f));
        smoke(e);
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Toxic_Bubbles: a few bubbles over a few ticks that float up, swell and pop. */
    private static ParticleEmitter bubbles(Palette p) {
        ParticleEmitter e = burst("bubbles", 3, range(.14f, .26f), range(8, 13), sphere(.6f), argb(0xE0, p.tint), false);
        for (int t : new int[] {2, 4}) {
            EmissionSetting.Burst b = new EmissionSetting.Burst();
            b.time = t;
            b.setCount(new RandomConstant(2, 3));
            e.config.emission.getBursts().add(b);
        }
        e.config.setStartSpeed(range(.1f, .3f));
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(-.004f));
        sheet(e, 2);
        // Grows in, holds, then swells a little as it pops.
        envelope(e, new Curve(0, 1, 0, 1.3f, "age", "size", new ECBCurves(0, .3f, .15f, .77f, .8f, .77f, 1, 1)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Flare_GlowdotPickbook: bright specks that wink in and out across the cloud. */
    private static ParticleEmitter glints(Palette p) {
        ParticleEmitter e = burst("glowdots", 0, range(.25f, .4f), range(5, 10), sphere(.75f), argb(0xD0, p.hot), true);
        e.config.emission.getBursts().getFirst().setCount(new RandomConstant(6, 9));
        e.config.setStartDelay(range(0, 4));
        e.config.setStartSpeed(range(.2f, .5f));
        sheet(e, 3);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .85f, .7f, .5f, 1, 0)));
        fade(e, 0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    // ---- configuration --------------------------------------------------------------------------

    /** One full-bright, non-looping layer of {@code potion/<texture>}; {@code count} 0 means set later. */
    private static ParticleEmitter burst(String texture, int count, RandomConstant size, RandomConstant life, IShape shape,
            int color, boolean additive) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "potion/" + texture, additive);
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

    /** Shared by both smoke layers: a random puff of the 2×2 sheet, braking, slowly turning and swelling. */
    private static void smoke(ParticleEmitter e) {
        sheet(e, 2);
        slowDown(e, .15f);
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-4, 4));
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .55f, .3f, .85f, .7f, 1, 1, 1)));
    }

    /** One random fixed frame of an n×n sheet. */
    private static void sheet(ParticleEmitter e, int n) {
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(n, n));
        e.config.uvAnimation.setStartFrame(range(0, n * n - .001f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
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

    /** Unity's speed modifier: full speed at birth, {@code end} of it at death. */
    private static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .45f, .6f, end, 1, end)));
    }
}
