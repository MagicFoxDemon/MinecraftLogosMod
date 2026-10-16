package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The fire charge: the shared charge (see CHARGE_VFX.md) in hotter colours (a white-gold heart, orange-gold,
 * a dark red), with the sun-rayed circle (Unreal MagicCircle 06) innermost under a colossal charge, and two
 * light touches of its own for the grand and colossal tiers: a few embers drifting up off the circle and
 * dying out, and a flickering corona round the held ball.
 */
final class FireChargeVisual extends ChargeCore {
    /** Radius of the held ball by tier, as {@link ChargeHead} draws it. */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};

    FireChargeVisual() {
        super(ChargeStyle.colours(0xFFF4D6, 0xFF7414, 0xA81C00).withCircles("charge/circle_17", "charge/circle_18",
                "charge/circle_16", "charge/circle_03", "charge/circle_11", "fire_charge/sun_circle", "charge/circle_17"));
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        if (charge.tier() < 2) return;
        ChargeParts.View view = ChargeParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        embers(charge, view, partialTick, pose, out);
        corona(charge, view, partialTick, pose, out);
        pose.popPose();
    }

    /**
     * A few embers rising off the circle round the caster, wavering as they go and dying out on the way up.
     * Deterministic from the time, so nothing needs keeping.
     */
    private void embers(LiveCharge c, ChargeParts.View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        int tier = c.tier();
        float since = c.phaseAge(pt);
        float show = switch (c.phase()) {
            case CHARGING -> smooth(.15f, .5f, c.progress(pt));
            case HELD -> 1;
            case RELEASED -> Math.max(0, 1 - since / 14);
            case FIZZLED -> Math.max(0, 1 - since / 8);
        };
        if (show <= 0) return;
        Vec3 feet = c.feet(pt);
        double now = c.age(pt);
        float scale = c.height() / 1.8f;
        int count = tier == 3 ? 11 : 6;
        float spread = (tier == 3 ? 2.8f : 1.6f) * scale, rise = (tier == 3 ? 4.6f : 3f) * scale;
        out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/ember"), (pose, buf) -> {
            for (int i = 0; i < count; i++) {
                double period = 34 + (i * 11 % 17);
                double t = now + i * 19.3;
                double k = (t % period) / period;
                int round = (int) Math.floor(t / period);
                // Each round starts from a fresh place on the circle.
                double th = i * 2.399 + round * 1.7;
                double rr = spread * (.45 + .55 * (((i * 37 + round * 13) % 10) / 10.0));
                // Quick off the ground, slowing as it cools.
                double y = rise * (1 - (1 - k) * (1 - k));
                double sway = .18 * scale * Math.sin(t * .21 + i);
                Vec3 at = feet.add(Math.cos(th) * (rr + sway), .2 + y, Math.sin(th) * (rr + sway));
                float flicker = .75f + .25f * Mth.sin((float) t * 1.3f + i * 2);
                float life = (float) Math.min(k / .12, Math.pow(1 - k, 1.5));
                float a = show * life * flicker * ChargeParts.fade(view.cam(), at, .8, 2.5);
                if (a <= .01f) continue;
                int color = k < .35 ? lerpColor(style.light(), style.main(), (float) (k / .35))
                        : lerpColor(style.main(), style.deep(), (float) ((k - .35) / .65));
                float size = (.045f + .02f * (i % 3)) * scale * (1 - .5f * (float) k);
                ChargeParts.sprite(pose, buf, view, at, size, 0, argb(a, color));
            }
        });
    }

    /** Held grand and colossal balls: a corona of tongues of fire licking round the rim, turning and flickering. */
    private void corona(LiveCharge c, ChargeParts.View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        float since = c.phaseAge(pt), show;
        switch (c.phase()) {
            case HELD -> show = smooth(0, 5, since);
            case RELEASED -> show = Math.max(0, 1 - since / 3);
            default -> show = 0;
        }
        if (show <= 0) return;
        int tier = c.tier();
        Vec3 tip = c.tip(pt);
        double now = c.age(pt);
        float r = BALL[tier] * c.near() * (1 + .06f * Mth.sin((float) now * .5f));
        // The same cap as the ball's in first person, and fainter: it must not cover the view.
        if (c.firstPerson()) r = Math.min(r, (float) view.cam().distanceTo(tip) * .3f);
        float clear = ChargeParts.fade(view.cam(), tip, .15, .45);
        float flicker = .8f + .12f * Mth.sin((float) now * 1.7f) + .08f * Mth.sin((float) now * 3.1f + 1);
        float a = show * clear * (c.firstPerson() ? .6f : 1) * (tier == 3 ? .7f : .55f);
        float size = r * (tier == 3 ? 2.5f : 2.2f) * (.97f + .05f * Mth.sin((float) now * 2.3f));
        float spin = (float) now * .03f;
        int hot = lerpColor(style.light(), style.main(), .4f);
        out.submitCustomGeometry(stack, ChargeParts.add("fire_beam/spiky_ring"), (pose, buf) -> {
            ChargeParts.sprite(pose, buf, view, tip, size, spin, argb(a * flicker, style.main()));
            ChargeParts.sprite(pose, buf, view, tip, size * .88f, -spin * 1.4f + 1, argb(a * .7f * (1.8f - flicker), hot));
        });
    }
}
