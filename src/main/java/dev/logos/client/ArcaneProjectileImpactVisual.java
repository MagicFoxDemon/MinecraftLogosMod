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

/** Comet's elongated star, violet-pink radial flash and lingering stardust. */
final class ArcaneProjectileImpactVisual implements ProjectileImpactVisual {
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = buildExpiry();

    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter cross = stationary("cross", .5f, .65f, 9, 0xC0F5C2FF);
        cross.config.setStartSize(new NumberFunction3(range(.5f, .65f), range(.8f, 1.02f), NumberFunction.constant(1)));
        cross.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0),
                NumberFunction.constant(0), NumberFunction.constant(0)));
        ParticleEmitter rays = stationary("rays", .8f, 1, 8, 0x65D796FF);
        ParticleEmitter halo = stationary("halo", .8f, 1, 12, 0x35D35DFF);
        ParticleEmitter ring = stationary("flash", .35f, .5f, 7, 0x38FBB6F2);
        ParticleEmitter glow = stationary("glow", .75f, .95f, 14, 0x24D35DFF);

        Cone outward = new Cone();
        outward.setRadius(.05f);
        outward.setAngle(85);
        ParticleEmitter streaks = layer(Element.ARCANE, true, 0, 14, size(.018f, .03f),
                range(.1f, .24f), range(4, 8), outward);
        mask(streaks, "spark", 0xDFFBB6F2);
        streaks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        streaks.config.renderer.setLengthScale(2.5f);
        streaks.config.renderer.setVelocityScale(.3f);

        ParticleEmitter stars = layer(Element.ARCANE, true, 0, 9, size(.07f, .13f),
                range(.018f, .06f), range(9, 18), sphere(.22f));
        mask(stars, "star", 0xDCE6A0FF);
        ParticleEmitter motes = layer(Element.ARCANE, true, 0, 16, size(.013f, .025f),
                range(.025f, .085f), range(10, 20), sphere(.1f));
        mask(motes, "mote", 0xDCFBB6F2);
        return List.of(cross, rays, halo, ring, glow, streaks, stars, motes);
    }

    private static List<ParticleEmitter> buildExpiry() {
        ParticleEmitter motes = layer(Element.ARCANE, true, 0, 6, size(.012f, .023f),
                range(.005f, .025f), range(5, 10), sphere(.06f));
        mask(motes, "mote", 0xB0FBB6F2);
        ParticleEmitter stars = layer(Element.ARCANE, true, 0, 2, size(.04f, .07f),
                range(.003f, .013f), range(5, 9), sphere(.05f));
        mask(stars, "star", 0x90E6A0FF);
        return List.of(motes, stars);
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int color) {
        ParticleEmitter emitter = layer(Element.ARCANE, true, 0, 1, size(min, max),
                range(0, 0), range(life, life), sphere(0));
        mask(emitter, texture, color);
        emitter.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0),
                NumberFunction.constant(0), range(0, 360)));
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .3f, .04f, .7f, .12f, 1, .22f, 1,
                .22f, 1, .5f, .95f, .8f, .4f, 1, .05f));
        emitter.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return emitter;
    }

    private static void mask(ParticleEmitter emitter, String texture, int color) {
        configureTexture(emitter, "arcane_impact/" + texture, true);
        emitter.config.setDuration(24);
        emitter.config.setMaxParticles(24);
        emitter.config.setStartColor(NumberFunction.color(color));
        emitter.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xDFF9DCFF, 0x80E6A0FF, 0x22C15AFF, 0x00A136EF)));
        emitter.config.physics.setEnable(false);
    }
}
