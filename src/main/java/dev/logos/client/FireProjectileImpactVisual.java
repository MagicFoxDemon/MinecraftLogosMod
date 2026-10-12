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

/** Ares' brief orange-gold impact flash, expanding rays and outward sparks; expiry only sheds embers. */
final class FireProjectileImpactVisual implements ProjectileImpactVisual {
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = buildExpiry();

    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter flash = stationary("flash", .72f, .85f, 7, 1, 0xD0FFF0B0);
        ParticleEmitter rays = stationary("rays", .95f, 1.15f, 11, 2, 0xC0FF8E24);
        ParticleEmitter glow = stationary("glow", 1.1f, 1.3f, 14, 1, 0x65FFAB32);
        for (ParticleEmitter emitter : List.of(flash, rays, glow)) {
            // Reach full size early, then recede while alpha approaches zero before removal.
            Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                    0, .25f, .04f, .65f, .12f, 1, .22f, 1,
                    .22f, 1, .45f, 1, .8f, .45f, 1, .05f));
            emitter.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        }

        Cone outward = new Cone();
        outward.setRadius(.055f);
        outward.setAngle(80);
        ParticleEmitter streaks = layer(Element.FIRE, true, 0, 18, size(.02f, .035f), range(.10f, .28f), range(3, 10), outward);
        mask(streaks, "spark", 0xFFFFAE38);
        streaks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        streaks.config.renderer.setLengthScale(3);
        streaks.config.renderer.setVelocityScale(.35f);

        ParticleEmitter embers = layer(Element.FIRE, true, 0, 14, size(.018f, .03f), range(.025f, .13f), range(8, 20), sphere(.04f));
        mask(embers, "ember", 0xFFFF9D22);
        embers.config.physics.setEnable(true);
        embers.config.physics.setGravity(NumberFunction.constant(.006f));
        ParticleEmitter stars = layer(Element.FIRE, true, 0, 4, size(.07f, .11f), range(.01f, .05f), range(4, 8), sphere(.055f));
        mask(stars, "star", 0xCCFFE39A);
        return List.of(flash, rays, glow, streaks, embers, stars);
    }

    private static List<ParticleEmitter> buildExpiry() {
        ParticleEmitter embers = layer(Element.FIRE, true, 0, 6, size(.015f, .025f), range(.005f, .025f), range(5, 10), sphere(.06f));
        mask(embers, "ember", 0xB0FF8D20);
        return List.of(embers, stationary("glow", .15f, .22f, 4, 1, 0x30FF7810));
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int count, int color) {
        ParticleEmitter e = layer(Element.FIRE, true, 0, count, size(min, max), range(0, 0), range(life, life), sphere(0));
        mask(e, texture, color);
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        return e;
    }

    private static void mask(ParticleEmitter e, String texture, int color) {
        configureTexture(e, "fire_impact/" + texture, true);
        e.config.setDuration(24);
        e.config.setMaxParticles(24);
        e.config.setStartColor(NumberFunction.color(color));
        Curve taper = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, 1, .2f, 1, .65f, .65f, 1, 0));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(taper, taper, taper));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xE0FFE2AB, 0x66FFB85A, 0x14FF7D28, 0x00FF5510)));
        e.config.physics.setEnable(false);
    }
}
