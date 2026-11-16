package dev.logos.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Circle;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;

import dev.logos.lang.Element;

/**
 * What the generic spell effects are made of: each element's palette, the {@link #layer} every Photon emitter
 * is built from with its sizes and shapes, and the effects {@link LogosFx} plays where an element draws nothing
 * of its own (burst, ring, pulse, bead, mark, execute). Element visuals build their own effects from the same
 * layers, through {@link LogosFx}.
 */
final class FxLayers {
    /** {@code bright}: the hot centre a particle is born with; {@code core}: the element's colour. */
    record Palette(int bright, int core) {}

    static final Map<Element, Palette> PALETTES = new EnumMap<>(Element.class);

    /** The element's colour (opaque RGB), for other renderers that follow the same palette. */
    static int coreColor(Element e) {
        return PALETTES.get(e).core() & 0xFFFFFF;
    }

    static {
        PALETTES.put(Element.FIRE, new Palette(0xFFFFF2B0, 0xFFFF5A00));
        PALETTES.put(Element.FROST, new Palette(0xFFF0FFFF, 0xFF38B6FF));
        PALETTES.put(Element.LIGHTNING, new Palette(0xFFFFFFFF, 0xFF6C8CFF));
        PALETTES.put(Element.VOID, new Palette(0xFFF0C8FF, 0xFF9A28FF));
        PALETTES.put(Element.PHYSICAL, new Palette(0xFFFFFFFF, 0xFFCFCFCF));
        PALETTES.put(Element.ARCANE, new Palette(0xFFFFD8FF, 0xFFE83CFF));
        PALETTES.put(Element.LIFE, new Palette(0xFFF2FFD8, 0xFF40E050));
    }

    private FxLayers() {}

    static RandomConstant range(float min, float max) {
        return new RandomConstant(min, max);
    }

    static NumberFunction3 size(float min, float max) {
        return new NumberFunction3(range(min, max), range(min, max), range(min, max));
    }

    static Sphere sphere(float radius) {
        Sphere s = new Sphere();
        s.setRadius(radius);
        return s;
    }

    private static Circle circle() {
        Circle c = new Circle();
        c.setRadius(1);
        c.setRadiusThickness(0);
        return c;
    }

    /**
     * One emitter layer. {@code halo} layers blend additively at low alpha and are much larger;
     * core layers are opaque and fade from the palette's hot centre to the element colour.
     * {@code rate > 0} makes a looping emitter (trails); otherwise it bursts {@code count} once.
     */
    static ParticleEmitter layer(Element el, boolean halo, float rate, int count, NumberFunction3 size,
                                         RandomConstant speed, RandomConstant life, IShape shape) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        Palette p = PALETTES.get(el);
        int core = p.core & 0x00FFFFFF;
        if (halo) {
            c.setStartColor(NumberFunction.color(0x70000000 | core));
            c.colorOverLifetime.setEnable(true);
            c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0x00FFFFFF)));
            for (var m : c.renderer.getMaterials()) {
                m.getBlendMode().setSrcColorFactor(SourceFactor.SRC_ALPHA);
                m.getBlendMode().setDstColorFactor(DestFactor.ONE);
            }
        } else {
            c.setStartColor(NumberFunction.color(0xFFFFFFFF));
            c.colorOverLifetime.setEnable(true);
            c.colorOverLifetime.setColor(new Gradient(new GradientColor(p.bright, p.core, 0x00000000 | core)));
        }
        // Full-bright: unaffected by night or caves.
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setStartSize(size);
        c.setStartSpeed(speed);
        c.setStartLifetime(life);
        c.shape.setShape(shape);
        c.setDuration(20);
        if (rate > 0) {
            c.setLooping(true);
            c.emission.setEmissionRate(NumberFunction.constant(rate));
        } else {
            c.setLooping(false);
            c.emission.setEmissionRate(NumberFunction.constant(0));
            EmissionSetting.Burst b = new EmissionSetting.Burst();
            b.time = 0;
            b.setCount(NumberFunction.constant(count));
            c.emission.getBursts().add(b);
        }
        if (el == Element.FIRE) {
            c.physics.setEnable(true);
            c.physics.setGravity(NumberFunction.constant(-0.012f));
        } else if (el == Element.FROST) {
            c.physics.setEnable(true);
            c.physics.setGravity(NumberFunction.constant(0.008f));
        }
        return e;
    }

    static List<ParticleEmitter> burst(Element el) {
        return List.of(
                layer(el, false, 0, 44, size(0.08f, 0.2f), range(0.04f, 0.15f), range(8, 16), sphere(0.2f)),
                layer(el, true, 0, 6, size(0.7f, 1.2f), range(0, 0.01f), range(6, 10), sphere(0.1f)),
                // sparks: tiny, fast, short
                layer(el, false, 0, 18, size(0.03f, 0.06f), range(0.2f, 0.35f), range(4, 8), sphere(0.1f)));
    }

    static List<ParticleEmitter> execute() {
        return List.of(
                layer(Element.VOID, false, 0, 90, size(0.1f, 0.25f), range(0.08f, 0.22f), range(10, 20), sphere(0.3f)),
                layer(Element.VOID, true, 0, 12, size(1.2f, 2.0f), range(0, 0.02f), range(8, 14), sphere(0.2f)),
                layer(Element.VOID, false, 0, 30, size(0.04f, 0.08f), range(0.3f, 0.5f), range(6, 10), sphere(0.2f)));
    }

    /** The outline of a field; radius comes from the root scale. */
    static List<ParticleEmitter> ring(Element el) {
        return List.of(
                layer(el, false, 0, 80, size(0.1f, 0.2f), range(0, 0.01f), range(12, 20), circle()),
                layer(el, true, 0, 24, size(0.5f, 0.8f), range(0, 0.005f), range(8, 14), circle()));
    }

    /** A field's heartbeat: a bloom filling its volume. */
    static List<ParticleEmitter> pulse(Element el) {
        return List.of(
                layer(el, false, 0, 44, size(0.08f, 0.18f), range(0.01f, 0.04f), range(10, 16), sphere(1)),
                layer(el, true, 0, 10, size(0.8f, 1.3f), range(0, 0.01f), range(8, 12), sphere(0.8f)));
    }

    /** One bead of a ray; many beads in a row read as a beam. */
    static List<ParticleEmitter> bead(Element el) {
        return List.of(
                layer(el, false, 0, 6, size(0.1f, 0.18f), range(0, 0.012f), range(6, 11), sphere(0.05f)),
                layer(el, true, 0, 1, size(0.45f, 0.65f), range(0, 0.002f), range(5, 8), sphere(0.02f)));
    }

    /** A mark being written: motes rising off the target. */
    static List<ParticleEmitter> mark(Element el) {
        List<ParticleEmitter> layers = new ArrayList<>();
        ParticleEmitter motes = layer(el, false, 0, 16, size(0.06f, 0.12f), range(0.02f, 0.05f), range(10, 18), sphere(0.3f));
        motes.config.physics.setEnable(true);
        motes.config.physics.setGravity(NumberFunction.constant(-0.01f));
        layers.add(motes);
        layers.add(layer(el, true, 0, 3, size(0.5f, 0.7f), range(0, 0.005f), range(6, 10), sphere(0.1f)));
        return layers;
    }
}
