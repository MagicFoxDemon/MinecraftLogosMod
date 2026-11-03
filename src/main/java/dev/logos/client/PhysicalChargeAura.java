package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the colossal physical aura adds to the shared one ({@link ChargeAuraEvoke}, after NS_Charge_EvokeEnergy_Root):
 * colourless, the pressure of the air, as the physical charge squeezes rings of air onto its held ball. Two touches,
 * in existing textures read in place:
 * <ul>
 * <li>rings of compressed air ({@code force/ring}) closing in on the caster, two at a time, a steel-grey haze under a
 * faint white edge;</li>
 * <li>streaks of air ({@code physical_ray/wind}) drawn up the flame, thin and steel-grey.</li>
 * </ul>
 * Blended in the palette's steel shade under the white, so they still read against a bright sky. Deterministic from
 * the charge's clock. They follow {@link ChargeBody#aura} and {@link ChargeAuraEvoke#AURA_SCALE} like the rest of
 * the aura. Both wrap the caster, so in first person, like the flames, they are left out.
 */
final class PhysicalChargeAura {
    private static final int RING_PERIOD = 24, RINGS = 2, STREAKS = 6;

    private PhysicalChargeAura() {}

    static void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out, ChargeStyle style) {
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        if (aura == null) return;
        float s = Mth.clamp(aura.strength(), 0, 1), widen = aura.widen();
        Vec3 feet = c.feet(pt);
        double now = c.age(pt);
        float scale = c.height() / 1.8f * ChargeAuraEvoke.AURA_SCALE;
        boolean fp = c.firstPerson();
        // From inside, rings closing on the camera would sweep across the whole view.
        if (fp) return;
        rings(stack, out, view, feet, c.height(), now, scale, s, widen, style);
        streaks(stack, out, view, feet, now, scale, s, widen, style);
    }

    /**
     * Rings of compressed air closing in on the caster's middle one after another, square to the camera (as the
     * physical charge's rings close on its held ball), so they read from any side.
     */
    private static void rings(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, float height, double now, float scale,
            float s, float widen, ChargeStyle style) {
        Vec3 middle = feet.add(0, height * .55, 0);
        for (int pass = 0; pass < 2; pass++) {
            boolean edge = pass == 1;
            out.submitCustomGeometry(stack, edge ? ChargeParts.add("force/ring") : ChargeParts.blend("force/ring"), (pose, buf) -> {
                for (int i = 0; i < RINGS; i++) {
                    double k = ((now + i * RING_PERIOD / (double) RINGS) % RING_PERIOD) / RING_PERIOD;
                    // Fast at first, slowing as the air packs in round the body.
                    float r = (float) Mth.lerp(1 - (1 - k) * (1 - k), 2.1, .6) * scale * widen;
                    float a = s * (float) Math.sin(k * Math.PI) * (edge ? .12f : .5f);
                    if (a > .01f) ChargeParts.sprite(pose, buf, view, middle, r, (float) (now * .01 + i), argb(a, edge ? style.light() : style.deep()));
                }
            });
        }
    }

    /** Thin streaks of air drawn up round the flame, wavering. */
    private static void streaks(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float s, float widen,
            ChargeStyle style) {
        out.submitCustomGeometry(stack, ChargeParts.blend("physical_ray/wind"), (pose, buf) -> {
            for (int i = 0; i < STREAKS; i++) {
                double period = 22 + (i * 7 % 9);
                double k = ((now + i * 11.3) % period) / period;
                double th = i * 2.399 + now * .02;
                double r = (.7 + .25 * Math.sin(i * 1.7 + now * .05)) * scale * widen;
                Vec3 base = feet.add(Math.cos(th) * r, (.2 + 1.6 * k) * scale, Math.sin(th) * r);
                Vec3 head = base.add(0, (.9 + .4 * (i % 3) / 2.0) * scale, 0);
                float a = s * .5f * (float) Math.sin(k * Math.PI) * ChargeParts.fade(view.cam(), base, .8, 2);
                if (a > .01f) ChargeParts.streak(pose, buf, view, base, head, .2f * scale, argb(a, style.deep()));
            }
        });
    }
}
