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
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import dev.logos.lang.Element;

/** Nature's yellow-green bloom, mint glow and outward drifting leaves. */
final class LifeProjectileImpactVisual implements ProjectileImpactVisual {
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = List.of(
            leaves(3, .08f, .12f, .003f, .016f, 7, 12, .06f),
            motes(5, .012f, .025f, .003f, .02f, 5, 10, .06f));

    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter glow = stationary("glow", .95f, 1.1f, 14, 0x3882FFD0);
        ParticleEmitter star = stationary("star", .45f, .6f, 8, 0xB0DFFF96);
        ParticleEmitter petal = stationary("petal", .7f, .85f, 11, 0x304AD35C);
        ParticleEmitter rays = stationary("rays", .8f, .95f, 9, 0x55FFF2BA);
        ParticleEmitter halo = stationary("halo", .85f, 1.05f, 12, 0x5844E860);
        ParticleEmitter flare = stationary("flare", .7f, .9f, 9, 0x6099FF73);
        return List.of(glow, star, petal, rays, halo, flare,
                leaves(10, .15f, .23f, .04f, .1f, 10, 22, .23f),
                motes(18, .015f, .03f, .04f, .13f, 6, 16, .12f));
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int color) {
        ParticleEmitter emitter = layer(Element.LIFE, true, 0, 1, size(min, max),
                range(0, 0), range(life, life), sphere(0));
        mask(emitter, texture, true, color);
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .25f, .04f, .7f, .12f, 1, .22f, 1,
                .22f, 1, .5f, .95f, .8f, .4f, 1, .05f));
        emitter.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return emitter;
    }

    private static ParticleEmitter leaves(int count, float min, float max, float speedMin, float speedMax,
            float lifeMin, float lifeMax, float radius) {
        Sphere spread = sphere(radius);
        if (radius > .1f) spread.setRadiusThickness(.25f);
        ParticleEmitter emitter = layer(Element.LIFE, false, 0, count, size(min, max),
                range(speedMin, speedMax), range(lifeMin, lifeMax), spread);
        // Ordinary alpha keeps the leaf silhouette and veins visible through the glow.
        mask(emitter, "leaf", false, 0xE0A3FF73);
        emitter.config.rotationOverLifetime.setEnable(true);
        emitter.config.rotationOverLifetime.setRoll(range(-220, 220));
        return emitter;
    }

    private static ParticleEmitter motes(int count, float min, float max, float speedMin, float speedMax,
            float lifeMin, float lifeMax, float radius) {
        ParticleEmitter emitter = layer(Element.LIFE, true, 0, count, size(min, max),
                range(speedMin, speedMax), range(lifeMin, lifeMax), sphere(radius));
        mask(emitter, "mote", true, 0xE0E7FFA0);
        return emitter;
    }

    private static void mask(ParticleEmitter emitter, String texture, boolean additive, int color) {
        configureTexture(emitter, "life_impact/" + texture, additive);
        emitter.config.setDuration(26);
        emitter.config.setMaxParticles(24);
        emitter.config.setStartColor(NumberFunction.color(color));
        emitter.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0),
                NumberFunction.constant(0), range(0, 360)));
        emitter.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xE0F1FFCC, 0x90CCFFAA, 0x3075DC99, 0x0047BD99)));
        emitter.config.physics.setEnable(false);
    }
}
