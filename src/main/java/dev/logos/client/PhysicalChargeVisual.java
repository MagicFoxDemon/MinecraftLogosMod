package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The physical charge: the shared charge in colourless white over a cool steel-blue shade (so it still reads
 * against a bright sky), with the pressure of the air round the big ones. Held grand and colossal balls sit in
 * a faint ring of squeezed air that breathes with them, and the colossal one keeps pulling rings of air in onto
 * itself; a colossal release cracks out in a short white cross flash (the physical impact's), as it bursts.
 */
final class PhysicalChargeVisual extends ChargeCore {
    /** The shared ball's radius once full, by tier (as {@link ChargeHead} draws it). */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};

    PhysicalChargeVisual() {
        super(ChargeStyle.colours(0xFFFFFF, 0xC4D2EC, 0x6E84AA));
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        int tier = charge.tier();
        if (tier < 2) return;
        LiveCharge.Phase phase = charge.phase();
        if (phase != LiveCharge.Phase.HELD && phase != LiveCharge.Phase.RELEASED) return;
        ChargeParts.View view = ChargeParts.View.of(camera);
        Vec3 tip = charge.tip(partialTick);
        float near = charge.near(), since = charge.phaseAge(partialTick);
        float clear = fade(view.cam(), tip, .15, .45);
        double now = charge.age(partialTick);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (phase == LiveCharge.Phase.HELD) pressure(pose, out, view, charge, tip, now, since, near, clear);
        else if (tier == 3) cross(pose, out, view, charge, tip, since - 5, near, clear);
        pose.popPose();
    }

    /** Held: a faint ring of compressed air round the ball, breathing with it; colossal ones draw rings of air in onto it. */
    private void pressure(PoseStack stack, SubmitNodeCollector out, ChargeParts.View view, LiveCharge c, Vec3 tip, double now,
            float since, float near, float clear) {
        int tier = c.tier();
        float r = BALL[tier] * near;
        float show = clear * smooth(0f, 6f, since) * (c.firstPerson() ? .5f : 1);
        // Up close it stays round the ball, which the core keeps to three tenths of the way to the camera.
        float cap = c.firstPerson() ? (float) view.cam().distanceTo(tip) * .3f * 2.6f : Float.MAX_VALUE;
        int air = lerpColor(style.main(), style.deep(), .35f);
        float breathe = Mth.sin((float) now * .5f);
        float size = Math.min(r * 2.7f * (1 - .05f * breathe), cap);
        // A thin steel-grey haze just outside it (blended, not added), so the pressure still shows against a bright sky.
        out.submitCustomGeometry(stack, ChargeParts.blend("force/ring"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, size * 1.12f, 0, argb(show * (tier == 3 ? .2f : .15f), style.deep())));
        out.submitCustomGeometry(stack, ChargeParts.add("force/ring"), (pose, buf) -> {
            ChargeParts.sprite(pose, buf, view, tip, size, 0, argb(show * (tier == 3 ? .26f : .2f) * (.85f + .15f * breathe), air));
            if (tier == 3) {
                // Air squeezed in: every 16 ticks a ring closes from far out onto the pressure ring and is gone.
                float period = 16;
                float k = (float) ((now % period) / period);
                float s = Math.min(r * (4.6f - 1.9f * k * (2 - k)), cap * 1.6f);
                ChargeParts.sprite(pose, buf, view, tip, s, 0, argb(show * .16f * Mth.sin(k * Mth.PI), air));
            }
        });
    }

    /** Colossal release: a short white cross flash as the swollen ball bursts (the core's explosion starts on the same tick). */
    private void cross(PoseStack stack, SubmitNodeCollector out, ChargeParts.View view, LiveCharge c, Vec3 tip, float t, float near, float clear) {
        float life = 6;
        if (t < 0 || t > life) return;
        float k = t / life;
        boolean fp = c.firstPerson();
        // From the caster's own eyes it stays a glint at the head, like the core's flash.
        float size = fp ? Math.min(.4f, (2.4f + 1.4f * k) * near) : (2.4f + 1.4f * k) * near;
        float a = (1 - k) * (1 - k) * (fp ? .3f : 1) * clear;
        out.submitCustomGeometry(stack, ChargeParts.add("physical_impact/cross"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, size, 0, argb(a, style.light())));
    }
}
