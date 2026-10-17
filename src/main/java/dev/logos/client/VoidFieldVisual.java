package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The void field (FIELD_VFX.md): the magic circles are cuts in the ground, and through every line shows the
 * open void of every void effect - the end portal's stars in screen space over a dark nebula
 * ({@link VoidFieldParts#slit}) - with a violet glow at their lips (the shared lines through
 * {@code void_field_line.fsh}). The circles: the shared outer ring, MagicCircle 14's crescent in the middle,
 * the void charge's eye (MagicCircle 07) innermost, and MagicCircle 10's torn rift as the clock's hand. At
 * the edge, besides the shared faint light (the void beam's crackling flow), a see-through veil of the same void rising and fading
 * ({@link VoidFieldParts#CURTAIN}); hollow void runes float over it, and a few stars drift up out of the cuts.
 */
final class VoidFieldVisual extends FieldCore {
    private static final int WAVE = 14;
    private final List<Long> pulses = new ArrayList<>();

    VoidFieldVisual() {
        super(FieldStyle.of(Element.VOID)
                .withCircles("charge/circle_18", "charge/circle_14", "void_charge/circle_07", "charge/circle_10")
                .withCurtain("void_beam/flow").withRunes("void_field/runes", 3, 3)
                .withLines("core/void_field_line", "void_beam/aura"));
    }

    @Override
    public boolean tick(Level level, LiveField f) {
        boolean keep = super.tick(level, f);
        long now = ClientCharges.ticks();
        pulses.addAll(f.newPulses());
        pulses.removeIf(t -> now - t > WAVE + 2);
        return keep;
    }

    @Override
    public void submit(Level level, LiveField f, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, f, camera, pt, pose, out);
        View view = View.of(camera);
        Vec3 floor = f.floor(pt);
        float R = f.radius();
        if (view.cam().distanceTo(floor) > 96 + R) return;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float open = opened(f, pt), end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        slits(pose, out, floor, R * open, now, sweep(now), end, shown);
        curtain(pose, out, floor, R * open, now, end, shown);
        motes(pose, out, view, floor, R * open, now, end, shown, f.cameraInside(view.cam(), pt));
        pose.popPose();
    }

    /** As the shared field's: how far out the latest pulse's light has swept, in circle radii. */
    private float sweep(double now) {
        if (pulses.isEmpty()) return FieldParts.NO_SWEEP;
        float k = (float) ((now - pulses.getLast()) / WAVE);
        return k > 1.3f ? FieldParts.NO_SWEEP : k * 1.15f;
    }

    /** The cuts: the shared three circles again, just above them, turning and breaking up with them. */
    private void slits(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float sweep, float end, float shown) {
        if (R <= .01f) return;
        float[] radius = {R, R * .66f, R * .36f};
        String[] texture = {style.outer(), style.middle(), style.inner()};
        float[] turn = {(float) (now * .004), (float) (-now * .007), (float) (now * .015)};
        float[] dissolve = {smooth(0, 10, end), smooth(3, 13, end), smooth(6, 16, end)};
        for (int i = 0; i < 3; i++) {
            int layer = i;
            if (dissolve[layer] >= 1) continue;
            float own = sweep >= FieldParts.NO_SWEEP ? sweep : sweep * R / radius[layer];
            Vec3 at = floor.add(0, .034 + .01 * layer, 0);
            out.submitCustomGeometry(pose, VoidFieldParts.slit(texture[layer]), (p, buf) ->
                    FieldParts.circle(p, buf, at, radius[layer], turn[layer], argb(shown, style.main()), own, dissolve[layer]));
        }
    }

    /** The veil of void at the edge, a little inside the shared silk, flaring on a pulse. */
    private void curtain(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float end, float shown) {
        if (R <= .05f) return;
        float flare = 0;
        for (long t : pulses) flare = Math.max(flare, (float) Math.exp(-(now - t - WAVE * .8) * (now - t - WAVE * .8) / 18));
        float h = Mth.clamp(.5f + .1f * R, .7f, 2f) * 1.1f * (1 - smooth(0, 8, end));
        if (h <= .01f) return;
        int color = argb(shown * (.75f + .25f * flare), lerpColor(style.main(), style.light(), .3f * flare));
        int segments = Mth.clamp((int) (R * 10), 24, 120);
        float tiles = Math.max(1, Math.round(R * (float) (Math.PI * 2) / 4f));
        out.submitCustomGeometry(pose, VoidFieldParts.CURTAIN, (p, buf) ->
                ChargeParts.cylinder(p, buf, floor, R * .995f, h, segments, (float) (now * .0007), tiles, 0, 1, color, color));
    }

    /** A few stars rising slowly out of the cuts and going out, sparse. */
    private void motes(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown,
            boolean inside) {
        if (R <= .5f) return;
        int n = Mth.clamp(Math.round(R * 1.5f), 4, 16);
        float leave = 1 - smooth(0, 10, end);
        out.submitCustomGeometry(pose, ChargeParts.add("void_ray/star"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double life = 60 + 40 * hash(i * 5 + 3);
                double t = (now + hash(i * 11 + 7) * life) / life;
                int round = (int) Math.floor(t);
                double k = t - round;
                double phi = Math.PI * 2 * hash(i * 17 + round * 31);
                double r = R * Math.sqrt(.05 + .9 * hash(i * 23 + round * 7));
                Vec3 at = floor.add(Math.cos(phi) * r, .1 + 1.4 * k, Math.sin(phi) * r);
                float a = shown * leave * (float) Math.sin(Math.PI * k) * (.6f + .4f * Mth.sin((float) (now * .3 + i * 2.1)))
                        * fade(view.cam(), at, inside ? .8 : .4, inside ? 2 : 1.2);
                if (a <= .01f) continue;
                ChargeParts.sprite(p, buf, view, at, .07f + .04f * (float) hash(i * 3), 0,
                        argb(a * .9f, lerpColor(style.light(), style.main(), (float) hash(i * 29) * .7f)));
            }
        });
    }
}
