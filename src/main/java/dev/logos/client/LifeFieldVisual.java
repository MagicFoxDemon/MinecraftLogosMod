package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * The life field (FIELD_VFX.md): the shared field dressed in growth. Its circles are a ring of organic
 * flourishes, a swirl of vines and a leaf-and-bud rosette, with a sprouting seed for the clock's hand; their
 * lines are filled by {@code life_field_line}, green vines creeping round them with golden motes. The edge
 * curtain is strands of sap rising, with a fringe of grass at its foot ({@link LifeFieldGrowth}) and motes of
 * light and a few leaves drifting up off it; the runes are nature runes (Algiz, Berkano, a sprout, a leaf).
 */
final class LifeFieldVisual extends FieldCore {
    private final List<Long> pulses = new ArrayList<>();

    LifeFieldVisual() {
        super(FieldStyle.of(Element.LIFE)
                .withCircles("life_field/circle_13", "charge/circle_14", "charge/circle_28", "fire_charge/sun_circle")
                .withCurtain("life_field/curtain")
                .withRunes("life_field/runes", 3, 3)
                .withLines("core/life_field_line", "life_ray/vines"));
    }

    @Override
    public boolean tick(Level level, LiveField f) {
        boolean keep = super.tick(level, f);
        long now = ClientCharges.ticks();
        pulses.addAll(f.newPulses());
        pulses.removeIf(t -> now - t > 30);
        return keep;
    }

    @Override
    public void submit(Level level, LiveField f, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, f, camera, pt, pose, out);
        LifeFieldGrowth.submit(f, style, pulses, camera, pt, opened(f, pt), pose, out);
    }
}
