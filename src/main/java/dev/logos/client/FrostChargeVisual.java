package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The frost charge: the shared charge (CHARGE_VFX.md) in frost's colours, its colossal middle circle the
 * crystal lattice (Unreal MagicCircle 23), and three light touches of its own, only on the big tiers:
 * a few snowflakes drifting slowly down round a grand or colossal caster, a faint cold mist lying round the
 * colossal circles, and small ice crystals circling a grand or colossal ball while it is held. All of it is
 * deterministic from the charge's clock, so nothing is kept between frames.
 */
final class FrostChargeVisual extends ChargeCore {
    /** The held ball's radius by tier, as {@link ChargeHead} draws it. */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};

    FrostChargeVisual() {
        super(frost());
    }

    private static ChargeStyle frost() {
        ChargeStyle s = ChargeStyle.of(Element.FROST);
        return s.withCircles(s.elaborate(), s.grandOuter(), s.grandInner(), s.colossalOuter(), "frost_charge/circle_23",
                s.colossalInner(), s.raised());
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        int tier = charge.tier();
        if (tier < 2) return;
        View view = View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float show = show(charge, partialTick);
        if (show > 0) {
            if (tier == 3) mist(pose, out, view, charge, partialTick, show);
            snow(pose, out, view, charge, partialTick, tier, show);
        }
        crystals(pose, out, view, charge, partialTick, tier);
        pose.popPose();
    }

    /** How strongly the body's frost shows: building with the charge, steady when held, melting away after. */
    private static float show(LiveCharge c, float pt) {
        float since = c.phaseAge(pt);
        return switch (c.phase()) {
            case CHARGING -> smooth(.1f, .4f, c.progress(pt));
            case HELD -> 1;
            case RELEASED -> Math.max(0, 1 - since / 14);
            default -> Math.max(0, 1 - since / 10);
        };
    }

    /** Sparse snowflakes drifting slowly down round the caster, turning as they fall. */
    private void snow(PoseStack stack, SubmitNodeCollector out, View view, LiveCharge c, float pt, int tier, float show) {
        Vec3 feet = c.feet(pt);
        double now = c.age(pt);
        float scale = c.height() / 1.8f;
        int count = tier == 3 ? 11 : 6;
        float top = (tier == 3 ? 5.2f : 3.2f) * scale, inner = (tier == 3 ? 1.2f : .8f) * scale, outer = (tier == 3 ? 4.2f : 2.2f) * scale;
        int color = lerpColor(style.light(), style.main(), .3f);
        // In first person flakes drift past at eye height: keep them well off the camera.
        double lo = c.firstPerson() ? 2 : .8, hi = c.firstPerson() ? 4 : 2.5;
        out.submitCustomGeometry(stack, ChargeParts.add("frost_impact/snowflake"), (pose, buf) -> {
            for (int i = 0; i < count; i++) {
                double period = 90 + (i * 23 % 40);
                double k = ((now + i * 31.7) % period) / period;
                double th = i * 2.399 + now * .006 + .25 * Math.sin(now * .03 + i);
                double rr = inner + (outer - inner) * ((i * 37 % 10) / 9.0);
                Vec3 at = feet.add(Math.cos(th) * rr, .2 * scale + top * (1 - k), Math.sin(th) * rr);
                float a = show * .6f * smooth(0, .15f, (float) k) * (1 - smooth(.8f, 1, (float) k)) * ChargeParts.fade(view.cam(), at, lo, hi);
                if (a <= 0) continue;
                float size = (.07f + .05f * ((i * 53 % 7) / 6f)) * (tier == 3 ? 1.3f : 1) * scale;
                ChargeParts.sprite(pose, buf, view, at, size, (float) (now * .02 * (i % 2 == 0 ? 1 : -1) + i), argb(a, color));
            }
        });
    }

    /** Colossal: a faint cold mist lying low round the circles, slowly turning. */
    private void mist(PoseStack stack, SubmitNodeCollector out, View view, LiveCharge c, float pt, float show) {
        Vec3 feet = c.feet(pt);
        double now = c.age(pt);
        float scale = c.height() / 1.8f;
        int color = lerpColor(0xFFFFFF, style.light(), .5f);
        out.submitCustomGeometry(stack, ChargeParts.blend("frost_damage/mist"), (pose, buf) -> {
            for (int i = 0; i < 9; i++) {
                double th = i * Math.PI * 2 / 9 + now * .004;
                double rr = (3.1 + .5 * Math.sin(i * 1.7)) * scale;
                Vec3 at = feet.add(Math.cos(th) * rr, .35 * scale, Math.sin(th) * rr);
                float a = show * (.13f + .04f * Mth.sin((float) now * .05f + i)) * ChargeParts.fade(view.cam(), at, 1.5, 4);
                if (a <= 0) continue;
                ChargeParts.frame(pose, buf, view, at, .9f * scale, (float) (now * .005 + i), i % 4, 2, 2, argb(a, color));
            }
        });
    }

    /** Grand and colossal: a few small ice crystals circling the ball while it is held, flung off as it goes. */
    private void crystals(PoseStack stack, SubmitNodeCollector out, View view, LiveCharge c, float pt, int tier) {
        if (c.firstPerson()) return;
        float since = c.phaseAge(pt), a, spread = 1;
        switch (c.phase()) {
            case HELD -> a = smooth(0, 8, since);
            case RELEASED -> {
                float k = since / 5;
                if (k >= 1) return;
                a = 1 - k;
                spread = 1 + 1.2f * k;
            }
            default -> { return; }
        }
        Vec3 tip = c.tip(pt);
        double now = c.age(pt);
        float ball = BALL[tier] * c.near();
        float orbit = ball * 2.3f * spread, size = ball * (tier == 3 ? .32f : .38f);
        int count = tier == 3 ? 4 : 3;
        float alpha = a * .85f * ChargeParts.fade(view.cam(), tip, .5, 1.2);
        if (alpha <= 0) return;
        Vec3 axis = new Vec3(.25, 1, .15).normalize();
        Vec3[] plane = ChargeParts.basis(axis);
        int color = lerpColor(style.light(), style.main(), .2f);
        out.submitCustomGeometry(stack, ChargeParts.add("frost_damage/crystal"), (pose, buf) -> {
            for (int i = 0; i < count; i++) {
                double th = i * Math.PI * 2 / count + now * .09;
                Vec3 at = tip.add(plane[0].scale(Math.cos(th) * orbit)).add(plane[1].scale(Math.sin(th) * orbit))
                        .add(axis.scale(.25 * orbit * Math.sin(now * .07 + i * 2)));
                ChargeParts.frame(pose, buf, view, at, size, (float) (now * .05 + i * 1.3), i % 4, 2, 2, argb(alpha, color));
            }
        });
    }
}
