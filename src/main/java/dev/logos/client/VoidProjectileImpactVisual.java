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
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.platform.DestFactor;
import dev.logos.lang.Element;
import org.joml.Vector2i;

/** Curse's violet cloud wraps a deep screen-projected starfield, then dissolves into motes. */
final class VoidProjectileImpactVisual implements ProjectileImpactVisual {
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = List.of(motes(5, .02f, .035f, .005f, .02f, 6, 12), mist(1, .22f, .3f, 8, 12));
    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter core = stationary("cloud", .65f, .75f, 18, 0xFFFFFFFF);
        core.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xFFFFFFFF, 0xC0FFFFFF, 0x40FFFFFF, 0x00FFFFFF)));
        for (var material : core.config.renderer.getMaterials()) {
            material.setMaterial(new VoidProjectileImpactMaterial());
            // The dark interior has to cover what is behind it, so the starfield blends over, not adds.
            material.getBlendMode().setDstColorFactor(DestFactor.ONE_MINUS_SRC_ALPHA);
        }
        ParticleEmitter shell = stationary("cloud", .8f, .95f, 16, 0x909A28FF);
        ParticleEmitter rim = stationary("rim", 1, 1.1f, 18, 0x709A28FF);
        ParticleEmitter glow = stationary("glow", .95f, 1.15f, 15, 0x389A28FF);
        ParticleEmitter star = stationary("star", .28f, .35f, 6, 0x70D5A8FF);
        return List.of(core, shell, rim, glow, star, motes(18, .02f, .04f, .04f, .13f, 8, 20), mist(3, .7f, .95f, 16, 24));
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int color) {
        ParticleEmitter e = layer(Element.VOID, true, 0, 1, size(min, max), range(0, 0), range(life, life), sphere(0));
        mask(e, texture, true, color);
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .2f, .04f, .6f, .16f, 1, .25f, 1,
                .25f, 1, .5f, 1, .8f, .55f, 1, .08f));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return e;
    }

    private static ParticleEmitter motes(int count, float min, float max, float speedMin, float speedMax, float lifeMin, float lifeMax) {
        ParticleEmitter e = layer(Element.VOID, true, 0, count, size(min, max), range(speedMin, speedMax), range(lifeMin, lifeMax), sphere(.12f));
        mask(e, "mote", true, 0xD0C078FF);
        return e;
    }

    private static ParticleEmitter mist(int count, float min, float max, float lifeMin, float lifeMax) {
        ParticleEmitter e = layer(Element.VOID, false, 0, count, size(min, max), range(0, .015f), range(lifeMin, lifeMax), sphere(.08f));
        mask(e, "mist", false, 0x409A28FF);
        Curve spread = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .2f, .6f, .65f, 1, 1, 1));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(spread, spread, spread));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(5, 5));
        e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return e;
    }

    private static void mask(ParticleEmitter e, String texture, boolean additive, int color) {
        configureTexture(e, "void_impact/" + texture, additive);
        e.config.setDuration(28);
        e.config.setMaxParticles(24);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xD0E4C0FF, 0x609A48FF, 0x18782BFF, 0x00651DFF)));
        e.config.physics.setEnable(false);
    }
}
