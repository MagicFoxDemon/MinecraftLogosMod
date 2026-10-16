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
 * The arcane charge: the shared charge (CHARGE_VFX.md) in arcane's colours, with an astrolabe (Unreal
 * MagicCircle 12) for the grand outer circle and the hexagram ringed with rune medallions (MagicCircle 04)
 * in the middle of the colossal ones. On top, only for grand and colossal charges: a few of the arcane
 * ray's runes ({@code arcane_ray/runes}) slowly circling the ball as it fills and while it is held, each
 * glowing and dimming on its own, with two four-point glints ({@code arcane_ray/glint}) winking between
 * them in turn. Let go, the runes are flung out and go out; dropped, they dim away with the ball. They are
 * not drawn in first person, where the orbit would cross the middle of the view.
 */
final class ArcaneChargeVisual extends ChargeCore {
    /** Runes round the ball, by tier (none below grand). */
    private static final int[] RUNES = {0, 0, 4, 6};
    /** Their orbit and size, by tier, in blocks at full size (halved in first person like the rest of the head). */
    private static final float[] ORBIT = {0, 0, .62f, .95f}, SIZE = {0, 0, .095f, .13f};
    private static final float RELEASE = 9, FIZZLE = 14;

    ArcaneChargeVisual() {
        super(ChargeStyle.of(Element.ARCANE).withCircles("charge/circle_17", "arcane_charge/circle_12", "charge/circle_16",
                "charge/circle_03", "arcane_charge/circle_04", "charge/circle_15", "charge/circle_17"));
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        int tier = charge.tier();
        // In first person the orbit would cross the middle of the view: the runes are only for onlookers.
        if (RUNES[tier] == 0 || charge.firstPerson()) return;
        float show = 1, spread = 1;
        float since = charge.phaseAge(partialTick);
        switch (charge.phase()) {
            // They gather only over the last part of the charge, after the core's own build-up.
            case CHARGING -> show = smooth(.7f, .95f, charge.progress(partialTick));
            case HELD -> show = 1;
            case RELEASED -> {
                float k = since / RELEASE;
                if (k >= 1) return;
                show = (1 - k) * (1 - k);
                spread = 1 + 1.6f * (1 - (1 - k) * (1 - k));
            }
            case FIZZLED -> {
                float k = since / FIZZLE;
                if (k >= 1) return;
                show = (1 - k) * (.6f + .4f * Mth.sin(since * 2.3f)) * smooth(.5f, .9f, charge.progress(partialTick));
                spread = 1 - .35f * k;
            }
        }
        if (show <= .003f) return;
        View view = View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        runes(charge, view, partialTick, pose, out, tier, show, spread);
        pose.popPose();
    }

    private void runes(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out, int tier, float show, float spread) {
        Vec3 tip = c.tip(pt);
        float near = c.near();
        double now = c.age(pt);
        float orbit = ORBIT[tier] * near * spread, size = SIZE[tier] * near;
        float a0 = show;
        int n = RUNES[tier];
        // A slowly turning ring, tipped a little and wobbling, so it reads as an orbit from every side.
        double turn = now * .035;
        Vec3 axis = new Vec3(Math.sin(now * .011) * .35, 1, Math.cos(now * .013) * .35).normalize();
        Vec3[] plane = ChargeParts.basis(axis);
        Vec3[] at = new Vec3[n];
        float[] alpha = new float[n];
        for (int i = 0; i < n; i++) {
            double a = turn + i * Math.PI * 2 / n;
            at[i] = tip.add(plane[0].scale(Math.cos(a) * orbit)).add(plane[1].scale(Math.sin(a) * orbit));
            // Each glows and dims on its own; none quite goes out while held.
            float twinkle = .45f + .55f * (float) Math.pow(.5 + .5 * Math.sin(now * .11 + i * 2.4), 1.5);
            alpha[i] = a0 * twinkle * ChargeParts.fade(view.cam(), at[i], .25, .6);
        }
        int light = style.light(), main = style.main(), deep = style.deep();
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_ray/fuzz"), (pose, buf) -> {
            for (int i = 0; i < n; i++) if (alpha[i] > .003f)
                ChargeParts.sprite(pose, buf, view, at[i], size * 2.3f, 0, argb(alpha[i] * .3f, deep));
        });
        // A blended copy in the deep colour first, so the glyphs still read against a bright sky (as the arcane ray's do).
        out.submitCustomGeometry(stack, ChargeParts.blend("arcane_ray/runes"), (pose, buf) -> {
            for (int i = 0; i < n; i++) if (alpha[i] > .003f)
                ChargeParts.frame(pose, buf, view, at[i], size, (float) Math.sin(now * .02 + i) * .3f, (i * 4 + tier) % 9, 3, 3,
                        argb(alpha[i] * .55f, deep));
        });
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_ray/runes"), (pose, buf) -> {
            for (int i = 0; i < n; i++) if (alpha[i] > .003f)
                ChargeParts.frame(pose, buf, view, at[i], size, (float) Math.sin(now * .02 + i) * .3f, (i * 4 + tier) % 9, 3, 3,
                        argb(alpha[i] * .95f, lerpColor(light, main, .35f + .3f * (i % 2))));
        });
        // Two glints winking between the runes, each in turn.
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_ray/glint"), (pose, buf) -> {
            for (int g = 0; g < 2; g++) {
                double a = turn + (g * 2 + 1) * Math.PI / n + g * .4;
                Vec3 p = tip.add(plane[0].scale(Math.cos(a) * orbit * 1.12)).add(plane[1].scale(Math.sin(a) * orbit * 1.12))
                        .add(0, (g == 0 ? .08 : -.06) * orbit, 0);
                float wink = (float) Math.pow(Math.max(0, Math.sin(now * .09 + g * Math.PI)), 3);
                float a1 = a0 * wink * ChargeParts.fade(view.cam(), p, .25, .6);
                if (a1 > .003f) ChargeParts.sprite(pose, buf, view, p, size * (.8f + .5f * wink), g * Mth.HALF_PI * .5f, argb(a1, light));
            }
        });
    }
}
