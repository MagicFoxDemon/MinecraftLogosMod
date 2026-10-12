package dev.logos.client;

import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
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

/** QFX Lightning's blue-white flash, repeated arc discharge and a soft fading electric afterglow. */
final class LightningProjectileImpactVisual implements ProjectileImpactVisual {
    private static final List<ParticleEmitter> IMPACT = buildImpact();
    private static final List<ParticleEmitter> EXPIRY = buildExpiry();

    @Override
    public List<ParticleEmitter> impact() { return IMPACT; }
    @Override
    public List<ParticleEmitter> expiry() { return EXPIRY; }

    private static List<ParticleEmitter> buildImpact() {
        ParticleEmitter flash = burst("flash", .55f, .7f, 4, 1, 0x30C9E9FF);
        ParticleEmitter rays = burst("rays", .8f, 1, 6, 1, 0x2865C9FF);
        ParticleEmitter halo = burst("halo", .75f, .9f, 9, 1, 0x185493FF);
        ParticleEmitter glow = burst("glow", .9f, 1.1f, 14, 1, 0x185890FF);

        ParticleEmitter arcs = layer(Element.LIGHTNING, true, 0, 4, size(.22f, .4f),
                range(.015f, .045f), range(9, 14), sphere(.35f));
        mask(arcs, "arcs", 0xFFBCEBFF);
        // Alpha blending preserves separate white-cyan strokes instead of summing them into a white ball.
        configureTexture(arcs, "lightning_impact/arcs", false);
        arcs.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xFFFFFFFF, 0xBBD8EEFF, 0x005DA0FF)));
        rotate(arcs);
        animate(arcs, 2, 2, 3);
        arcs.config.uvAnimation.setStartFrame(range(0, 4));
        repeat(arcs, 3, 2);
        repeat(arcs, 5, 2);
        envelope(arcs);

        Cone outward = new Cone();
        outward.setRadius(.05f);
        outward.setAngle(85);
        ParticleEmitter streaks = layer(Element.LIGHTNING, true, 0, 12, size(.015f, .025f),
                range(.12f, .32f), range(4, 8), outward);
        mask(streaks, "spark", 0xFF70BFFF);
        streaks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        streaks.config.renderer.setLengthScale(3);
        streaks.config.renderer.setVelocityScale(.35f);
        repeat(streaks, 1, 6);

        ParticleEmitter motes = layer(Element.LIGHTNING, true, 0, 16, size(.018f, .03f),
                range(.04f, .13f), range(8, 16), sphere(.05f));
        mask(motes, "mote", 0xFF6AAEFF);
        ParticleEmitter stars = layer(Element.LIGHTNING, true, 0, 3, size(.05f, .09f),
                range(.005f, .025f), range(4, 8), sphere(.12f));
        mask(stars, "star", 0x60BDDFFF);
        repeat(stars, 2, 2);

        ParticleEmitter mist = layer(Element.LIGHTNING, true, 0, 3, size(.8f, 1.1f),
                range(.003f, .012f), range(12, 17), sphere(.06f));
        mask(mist, "mist", 0x10517FC9);
        rotate(mist);
        animate(mist, 4, 4, 15);
        envelope(mist);
        return List.of(flash, rays, halo, glow, arcs, streaks, motes, stars, mist);
    }

    private static List<ParticleEmitter> buildExpiry() {
        ParticleEmitter motes = layer(Element.LIGHTNING, true, 0, 6, size(.012f, .022f),
                range(.005f, .025f), range(5, 10), sphere(.05f));
        mask(motes, "mote", 0xA077C6FF);
        return List.of(motes, burst("glow", .13f, .19f, 5, 1, 0x305890FF));
    }

    private static ParticleEmitter burst(String texture, float min, float max, float life, int count, int color) {
        ParticleEmitter emitter = layer(Element.LIGHTNING, true, 0, count, size(min, max),
                range(0, 0), range(life, life), sphere(0));
        mask(emitter, texture, color);
        rotate(emitter);
        envelope(emitter);
        return emitter;
    }

    private static void mask(ParticleEmitter emitter, String texture, int color) {
        configureTexture(emitter, "lightning_impact/" + texture, true);
        emitter.config.setDuration(22);
        emitter.config.setMaxParticles(32);
        emitter.config.setStartColor(NumberFunction.color(color));
        emitter.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xDDE8F5FF, 0x779FCFFF, 0x225B8EFF, 0x003C60FF)));
        emitter.config.physics.setEnable(false);
    }

    private static void envelope(ParticleEmitter emitter) {
        Curve shape = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .4f, .025f, .75f, .08f, 1, .16f, 1,
                .16f, 1, .4f, 1, .85f, .5f, 1, .08f));
        emitter.config.sizeOverLifetime.setSize(new NumberFunction3(shape, shape, shape));
    }

    private static void rotate(ParticleEmitter emitter) {
        emitter.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0),
                NumberFunction.constant(0), range(0, 360)));
    }

    private static void repeat(ParticleEmitter emitter, int tick, int count) {
        EmissionSetting.Burst burst = new EmissionSetting.Burst();
        burst.time = tick;
        burst.setCount(NumberFunction.constant(count));
        emitter.config.emission.getBursts().add(burst);
    }

    private static void animate(ParticleEmitter emitter, int columns, int rows, int lastFrame) {
        emitter.config.uvAnimation.setEnable(true);
        emitter.config.uvAnimation.setTiles(new Vector2i(columns, rows));
        emitter.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, lastFrame, "age", "frame",
                new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
    }
}
