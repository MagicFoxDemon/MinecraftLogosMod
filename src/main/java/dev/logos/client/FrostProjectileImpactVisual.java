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
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import dev.logos.lang.Element;
import org.joml.Vector2i;

/** Winter's blue-white flash, crystal streaks, snowflakes and softly dispersing frost cloud. */
final class FrostProjectileImpactVisual implements ProjectileImpactVisual {
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = buildExpiry();

    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter glow = stationary("glow", .95f, 1.1f, 12, 0x7096CAFF);
        ParticleEmitter star = stationary("star", .5f, .6f, 8, 0xD0DCF4FF);
        ParticleEmitter flare = stationary("flare", .75f, .85f, 13, 0x9868ACFF);
        ParticleEmitter rays = stationary("rays", .65f, .75f, 9, 0x70A8D9FF);

        Cone outward = new Cone();
        outward.setRadius(.045f);
        outward.setAngle(80);
        ParticleEmitter crystals = layer(Element.FROST, true, 0, 8, size(.035f, .055f),
                range(.07f, .17f), range(5, 12), outward);
        mask(crystals, "spark", true, 0xE0A8DAFF);
        crystals.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        crystals.config.renderer.setLengthScale(2);
        crystals.config.renderer.setVelocityScale(.15f);

        ParticleEmitter stars = layer(Element.FROST, true, 0, 5, size(.08f, .13f),
                range(.01f, .045f), range(6, 13), sphere(.06f));
        mask(stars, "star", true, 0xB0DBF6FF);
        ParticleEmitter snow = snow(10, .06f, .09f, .06f, .12f, 10, 22, .32f);
        return List.of(glow, star, flare, rays, crystals, stars, snow, mist(2, .8f, 1.1f, 14, 20));
    }

    private static List<ParticleEmitter> buildExpiry() {
        return List.of(snow(4, .045f, .075f, .003f, .018f, 7, 13, .08f), mist(1, .2f, .32f, 8, 12));
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int color) {
        ParticleEmitter e = layer(Element.FROST, true, 0, 1, size(min, max), range(0, 0), range(life, life), sphere(0));
        mask(e, texture, true, color);
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .2f, .04f, .6f, .12f, 1, .22f, 1,
                .22f, 1, .5f, 1, .8f, .5f, 1, .05f));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return e;
    }

    private static ParticleEmitter snow(int count, float min, float max, float speedMin, float speedMax, float lifeMin, float lifeMax, float radius) {
        Sphere spread = sphere(radius);
        // Impact flakes start in a shell and travel radially rather than overlapping at the core.
        if (radius > .08f) spread.setRadiusThickness(.25f);
        ParticleEmitter e = layer(Element.FROST, false, 0, count, size(min, max), range(speedMin, speedMax), range(lifeMin, lifeMax), spread);
        mask(e, "snowflake", false, 0xD0B8E3FF);
        e.config.physics.setEnable(true);
        e.config.physics.setGravity(NumberFunction.constant(.0015f));
        return e;
    }

    private static ParticleEmitter mist(int count, float min, float max, float lifeMin, float lifeMax) {
        ParticleEmitter e = layer(Element.FROST, false, 0, count, size(min, max), range(0, .012f), range(lifeMin, lifeMax), sphere(.06f));
        mask(e, "mist", false, 0x487ABEFF);
        Curve spread = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .3f, .2f, .6f, .65f, 1, 1, 1));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(spread, spread, spread));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(5, 5));
        e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 24, "age", "frame",
                new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        return e;
    }

    private static void mask(ParticleEmitter e, String texture, boolean additive, int color) {
        configureTexture(e, "frost_impact/" + texture, additive);
        e.config.setDuration(26);
        e.config.setMaxParticles(24);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xD0DBF2FF, 0x609CCFFF, 0x186DABFF, 0x005B98FF)));
        e.config.physics.setEnable(false);
    }
}
