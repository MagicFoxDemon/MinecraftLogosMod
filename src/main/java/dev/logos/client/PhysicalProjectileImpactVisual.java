package dev.logos.client;

import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import dev.logos.lang.Element;
import org.joml.Vector2i;

/** Priest's sharp star flash, white-gold sparks and scattering feather-shaped flecks. */
final class PhysicalProjectileImpactVisual implements ProjectileImpactVisual {
    private static final float PLAYBACK_SPEED = 2;
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = buildExpiry();

    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter star = stationary("star", .6f, .72f, 8, 0xE0FFFFF5);
        // The upright four-point star is the brief, sharp contact cue.
        star.config.setStartRotation(new NumberFunction3(0, 0, 0));
        ParticleEmitter cross = stationary("cross", 1, 1, 14, 0xE8FFFFF5);
        cross.config.setStartSize(new NumberFunction3(range(1.08f, 1.2f), range(.68f, .76f), NumberFunction.constant(1)));
        cross.config.setStartRotation(new NumberFunction3(0, 0, 0));
        flashEnvelope(cross);
        ParticleEmitter spiral = stationary("spiral_noise", .608f, .672f, 14, 0xDCFFF1D4);
        spiral.config.setStartRotation(new NumberFunction3(0, 0, 0));
        flashEnvelope(spiral);
        for (var material : spiral.config.renderer.getMaterials())
            material.setMaterial(new PhysicalProjectileImpactMaterial());

        Cone outward = new Cone();
        outward.setRadius(.06f);
        outward.setAngle(80);
        ParticleEmitter streaks = layer(Element.PHYSICAL, true, 0, 12, size(.015f, .027f),
                range(.12f * PLAYBACK_SPEED, .26f * PLAYBACK_SPEED), range(4 / PLAYBACK_SPEED, 8 / PLAYBACK_SPEED), outward);
        mask(streaks, "spark", true, 0xE0FFF0C6);
        streaks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        streaks.config.renderer.setLengthScale(2);
        streaks.config.renderer.setVelocityScale(.2f / PLAYBACK_SPEED);

        ParticleEmitter motes = motes(14, .04f, .13f, 7, 18, .12f);
        ParticleEmitter stars = layer(Element.PHYSICAL, true, 0, 4, size(.07f, .12f),
                range(.03f * PLAYBACK_SPEED, .07f * PLAYBACK_SPEED), range(5 / PLAYBACK_SPEED, 11 / PLAYBACK_SPEED), sphere(.16f));
        mask(stars, "star", true, 0xC0FFFFEB);
        return List.of(star, cross, spiral, streaks, motes, stars,
                feathers(7, .07f, .12f, .06f, .14f, 8, 18, .18f), mist(2, .65f, .9f, 12, 19));
    }

    private static List<ParticleEmitter> buildExpiry() {
        return List.of(motes(4, .003f, .02f, 5, 10, .06f),
                feathers(2, .04f, .065f, .003f, .015f, 5, 10, .06f));
    }

    private static void flashEnvelope(ParticleEmitter emitter) {
        emitter.config.sizeOverLifetime.setEnable(false);
        // At double speed, peak after 2 ticks and fade over the remaining 5 ticks.
        emitter.config.colorOverLifetime.setEnable(true);
        emitter.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0x90585858, 0xD8B0B0B0, 0xFFFFFFFF, 0xD0D8D8D8,
                0xA0ABABAB, 0x68747474, 0x30383838, 0x00000000)));
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int color) {
        ParticleEmitter e = layer(Element.PHYSICAL, true, 0, 1, size(min, max), range(0, 0),
                range(life / PLAYBACK_SPEED, life / PLAYBACK_SPEED), sphere(0));
        mask(e, texture, true, color);
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .25f, .03f, .65f, .1f, 1, .2f, 1,
                .2f, 1, .45f, .9f, .75f, .35f, 1, .02f));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return e;
    }

    private static ParticleEmitter motes(int count, float speedMin, float speedMax, float lifeMin, float lifeMax, float radius) {
        ParticleEmitter e = layer(Element.PHYSICAL, true, 0, count, size(.012f, .023f),
                range(speedMin * PLAYBACK_SPEED, speedMax * PLAYBACK_SPEED),
                range(lifeMin / PLAYBACK_SPEED, lifeMax / PLAYBACK_SPEED), sphere(radius));
        mask(e, "mote", true, 0xDCFFE9AE);
        return e;
    }

    private static ParticleEmitter feathers(int count, float min, float max, float speedMin, float speedMax, float lifeMin, float lifeMax, float radius) {
        ParticleEmitter e = layer(Element.PHYSICAL, true, 0, count, size(min, max),
                range(speedMin * PLAYBACK_SPEED, speedMax * PLAYBACK_SPEED),
                range(lifeMin / PLAYBACK_SPEED, lifeMax / PLAYBACK_SPEED), sphere(radius));
        mask(e, "feathers", true, 0xB8FFF1D4);
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 1));
        e.config.uvAnimation.setStartFrame(range(0, 1.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        return e;
    }

    private static ParticleEmitter mist(int count, float min, float max, float lifeMin, float lifeMax) {
        ParticleEmitter e = layer(Element.PHYSICAL, false, 0, count, size(min, max),
                range(.004f * PLAYBACK_SPEED, .015f * PLAYBACK_SPEED),
                range(lifeMin / PLAYBACK_SPEED, lifeMax / PLAYBACK_SPEED), sphere(.08f));
        mask(e, "mist", false, 0x18FFF1D4);
        Curve spread = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .2f, .6f, .65f, 1, 1, 1));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(spread, spread, spread));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(5, 5));
        e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame",
                new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return e;
    }

    private static void mask(ParticleEmitter e, String texture, boolean additive, int color) {
        configureTexture(e, "physical_impact/" + texture, additive);
        e.config.setDuration((int)(24 / PLAYBACK_SPEED));
        e.config.setMaxParticles(24);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xDFFFF9E8, 0x80FFF0C6, 0x22FFE9AE, 0x00FFE9AE)));
        e.config.physics.setEnable(false);
    }
}
