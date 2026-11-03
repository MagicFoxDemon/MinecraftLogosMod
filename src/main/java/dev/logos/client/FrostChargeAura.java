package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the colossal frost aura adds to the shared one ({@link ChargeAuraEvoke}, after NS_Charge_EvokeEnergy_Root),
 * two light touches in the frost charge's own textures, read in place:
 * <ul>
 * <li>ice crystals ({@code frost_damage/crystal}, the frost charge's held-ball crystals) hanging round the pillar's
 * kept outer cylinder, spread round it and up its height, drifting and turning, so the body inside stays clear;</li>
 * <li>a cold mist ({@code frost_damage/mist}) spilling off the flame and rolling out low over the ground.</li>
 * </ul>
 * Both are alpha-blended in the palette's blue rather than added, so by day they read as ice and mist instead of
 * whitening the caster. Deterministic from the charge's clock, so nothing is kept. They follow
 * {@link ChargeBody#aura} like the rest of the aura; the mist takes its {@link ChargeAuraEvoke#AURA_SCALE}, the crystals
 * the cylinder's size. In first person the crystals keep off the camera and the crosshair, and the mist only shows
 * further out.
 */
final class FrostChargeAura {
    private static final int CRYSTALS = 24, MISTS = 7;

    private FrostChargeAura() {}

    static void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out, ChargeStyle style) {
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        if (aura == null) return;
        float s = Mth.clamp(aura.strength(), 0, 1), widen = aura.widen();
        Vec3 feet = c.feet(pt);
        double now = c.age(pt);
        float scale = c.height() / 1.8f * ChargeAuraEvoke.AURA_SCALE;
        boolean fp = c.firstPerson();
        crystals(stack, out, view, feet, now, c.height(), s, widen, fp, style);
        mist(stack, out, view, feet, now, scale, s, widen, fp, style);
    }

    /**
     * Ice crystals out round the pillar's kept cylinder (not on the body, which stays clear inside), spread round it
     * and all the way up it, each drifting slowly up and round while it lasts, turning and catching the light.
     */
    private static void crystals(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float height, float s,
            float widen, boolean fp, ChargeStyle style) {
        float scale = height / 1.8f;
        int ice = lerpColor(style.light(), style.main(), .15f);
        double lo = fp ? 1.2 : .7, hi = fp ? 2.4 : 1.8;
        for (int pass = 0; pass < 2; pass++) {
            boolean glint = pass == 1;
            out.submitCustomGeometry(stack, glint ? ChargeParts.add("frost_damage/crystal") : ChargeParts.blend("frost_damage/crystal"),
                    (pose, buf) -> {
                        for (int i = 0; i < CRYSTALS; i++) {
                            double period = 50 + (i * 17 % 29);
                            double k = ((now + i * 29.3) % period) / period;
                            // Evenly round the ring and up the height, each drifting up a fifth of it and round a little.
                            double th = i * Mth.TWO_PI / CRYSTALS + (i % 2 == 0 ? .5 : -.5) * k + now * .004;
                            double r = ChargeAuraEvokeParts.PILLAR_RADIUS * (.92 + .16 * ((i * 37 % 10) / 9.0)) * scale * widen;
                            double up = ((i * .618) % 1) * .78 + .2 * k;
                            Vec3 at = feet.add(Math.cos(th) * r, (.2 + up * (ChargeAuraEvokeParts.PILLAR_HEIGHT - .3)) * scale, Math.sin(th) * r);
                            float a = s * smooth(0, .15f, (float) k) * (1 - smooth(.75f, 1, (float) k)) * ChargeParts.fade(view.cam(), at, lo, hi);
                            if (fp) a *= ChargeAuraEvokeParts.offCentre(view, at);
                            if (a <= .01f) continue;
                            float size = (.28f + .14f * ((i * 53 % 7) / 6f)) * scale;
                            float roll = (float) (now * .05 * (i % 2 == 0 ? 1 : -1) + i);
                            ChargeParts.frame(pose, buf, view, at, size, roll, i % 4, 2, 2,
                                    glint ? argb(a * .9f * (.6f + .4f * Mth.sin((float) now * .4f + i)), style.light()) : argb(a, ice));
                        }
                    });
        }
    }

    /** Cold mist spilling off the flame and rolling out low over the ground, thinning as it goes. */
    private static void mist(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float s, float widen,
            boolean fp, ChargeStyle style) {
        int cold = lerpColor(style.light(), style.main(), .45f);
        double lo = fp ? 2.2 : 1.2, hi = fp ? 4.5 : 3;
        out.submitCustomGeometry(stack, ChargeParts.blend("frost_damage/mist"), (pose, buf) -> {
            for (int i = 0; i < MISTS; i++) {
                double period = 70 + (i * 13 % 25);
                double k = ((now + i * 41.7) % period) / period;
                double th = i * Math.PI * 2 / MISTS + .4 * Math.sin(i * 1.9) + now * .003;
                double r = (.45 + 1.15 * k) * scale * widen;
                Vec3 at = feet.add(Math.cos(th) * r, (.22 + .1 * (1 - k)) * scale, Math.sin(th) * r);
                float a = s * .2f * smooth(0, .2f, (float) k) * (1 - smooth(.55f, 1, (float) k)) * ChargeParts.fade(view.cam(), at, lo, hi);
                if (a <= .01f) continue;
                ChargeParts.frame(pose, buf, view, at, (.45f + .35f * (float) k) * scale, (float) (now * .004 + i), i % 4, 2, 2, argb(a, cold));
            }
        });
    }
}
