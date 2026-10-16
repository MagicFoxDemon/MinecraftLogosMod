package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the life field ({@link LifeFieldVisual}) draws over the shared field: a fringe of glowing grass at the
 * curtain's foot, two rows of uneven blades swaying in the wind; motes of light (mostly green, some gold)
 * rising up off the curtain and a few hanging in the air over the circles; and now and then a leaf carried up
 * off the edge, turning as it goes. All of it is worked out from the clock (nothing is kept between frames),
 * kept sparse, dropped for a far field, and kept off the camera when it stands inside. Three submissions a
 * frame, every vertex written in full through {@link ChargeParts}.
 */
final class LifeFieldGrowth {
    private LifeFieldGrowth() {}

    /** Leaf tints from {@link LifeBeamParts#LEAF}, without the rare brown. */
    private static final int[] LEAF = {0x2FB83A, 0x58E83A, 0x8FE83A, 0xB8F25A, 0x46C95A};
    private static final int GOLD = 0xF2D45A;
    private static final int WAVE = 14;

    static void submit(LiveField f, FieldStyle style, List<Long> pulses, CameraRenderState camera, float pt, float open, PoseStack pose,
            SubmitNodeCollector out) {
        View view = View.of(camera);
        Vec3 floor = f.floor(pt);
        float R = f.radius() * open;
        double dist = view.cam().distanceTo(floor);
        if (dist > 96 + f.radius() || R <= .1f) return;
        float end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        float flare = 0;
        for (long t : pulses) flare = Math.max(flare, (float) Math.exp(-(now - t - WAVE * .8) * (now - t - WAVE * .8) / 18));
        boolean inside = f.cameraInside(view.cam(), pt);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        grass(pose, out, style, floor, R, now, end, shown, flare);
        // Far off, the motes and leaves would only be specks.
        if (dist < 48 + R) {
            motes(pose, out, view, style, floor, R, now, end, shown, flare, inside);
            leaves(pose, out, view, floor, R, now, end, shown, inside);
        }
        pose.popPose();
    }

    /** A point at angle {@code phi} on a ring of radius {@code r} round {@code c}. */
    private static Vec3 onRing(Vec3 c, double r, double phi) {
        return c.add(Math.sin(phi) * r, 0, -Math.cos(phi) * r);
    }

    /** Two rows of grass at the curtain's foot, their tips swaying; brighter on a pulse. */
    private static void grass(PoseStack pose, SubmitNodeCollector out, FieldStyle style, Vec3 floor, float R, double now, float end,
            float shown, float flare) {
        float h = Mth.clamp(.25f + .05f * R, .3f, .65f) * (1 - smooth(0, 10, end));
        if (h <= .01f) return;
        float a = shown * (.38f + .4f * flare);
        int segments = Mth.clamp((int) (R * 10), 24, 120);
        out.submitCustomGeometry(pose, ChargeParts.add("life_field/grass"), (p, buf) -> {
            for (int row = 0; row < 2; row++) {
                float r = R * (row == 0 ? 1.004f : .985f);
                float hr = h * (row == 0 ? 1 : .8f);
                float tiles = Math.max(2, Math.round(r * (float) (Math.PI * 2) / (row == 0 ? 1.4f : 1.1f)));
                int bottom = argb(a * (row == 0 ? 1 : .7f), style.main());
                int top = argb(a * (row == 0 ? .9f : .6f), lerpColor(style.main(), style.light(), .45f));
                ring(p, buf, floor, r, hr, segments, row * .37f, tiles, now, bottom, top);
            }
        });
    }

    /** An upright band round {@code c}, its top swaying sideways with a wind that runs round the ring. */
    private static void ring(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, float h, int segments, float u0, float tiles,
            double now, int bottom, int top) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            Vec3 b0 = onRing(c, r, a0).add(0, .02, 0), b1 = onRing(c, r, a1).add(0, .02, 0);
            Vec3 t0 = b0.add(sway(a0, now, h)).add(0, h, 0), t1 = b1.add(sway(a1, now, h)).add(0, h, 0);
            float s0 = u0 + tiles * i / segments, s1 = u0 + tiles * (i + 1) / segments;
            ChargeParts.vertex(pose, buf, b0, s0, 1, bottom);
            ChargeParts.vertex(pose, buf, t0, s0, 0, top);
            ChargeParts.vertex(pose, buf, t1, s1, 0, top);
            ChargeParts.vertex(pose, buf, b1, s1, 1, bottom);
        }
    }

    /** The wind at angle {@code phi}: a gentle lean along the ring, in gusts running round it. */
    private static Vec3 sway(double phi, double now, float h) {
        double k = (Math.sin(phi * 5 - now * .05) * .7 + Math.sin(phi * 11 + now * .083) * .3) * h * .22;
        return new Vec3(Math.cos(phi) * k, 0, Math.sin(phi) * k);
    }

    /**
     * Motes of light: some rising off the curtain (each in its own cycle, starting somewhere new each time),
     * a few hanging over the circles and twinkling. A tinted halo and a pale core, both additive.
     */
    private static void motes(PoseStack pose, SubmitNodeCollector out, View view, FieldStyle style, Vec3 floor, float R, double now,
            float end, float shown, float flare, boolean inside) {
        float leave = 1 - smooth(0, 12, end);
        if (leave <= 0) return;
        int rising = Mth.clamp(Math.round(R * 2.5f), 8, 32), hanging = Mth.clamp(Math.round(R * .8f), 3, 10);
        float h = Mth.clamp(.5f + .1f * R, .7f, 2f);
        out.submitCustomGeometry(pose, ChargeParts.add("life_ray/dot"), (p, buf) -> {
            for (int i = 0; i < rising + hanging; i++) {
                Vec3 at;
                float alpha, size;
                int tint;
                if (i < rising) {
                    double period = 60 + 50 * FieldCore.hash(i * 17 + 3);
                    double t = now / period + FieldCore.hash(i * 5 + 11);
                    int cycle = (int) Math.floor(t);
                    float k = (float) (t - cycle);
                    double phi = Math.PI * 2 * FieldCore.hash(i * 31 + cycle * 7) + k * .25;
                    double r = R * (.98 - .06 * FieldCore.hash(i * 3 + cycle * 13));
                    at = onRing(floor, r, phi).add(0, .1 + k * h * 1.5, 0)
                            .add(Math.sin(k * 6 + i) * .08, 0, Math.cos(k * 5 + i) * .08);
                    alpha = (float) Math.sin(Math.PI * k) * (.7f + .3f * flare);
                    size = .05f + .04f * (float) FieldCore.hash(i * 9 + cycle);
                    tint = FieldCore.hash(i * 23 + cycle) < .3 ? GOLD : lerpColor(style.main(), style.light(), .3f);
                } else {
                    int j = i - rising;
                    double phi = Math.PI * 2 * FieldCore.hash(j * 41 + 2) + now * .002;
                    double r = R * (.15 + .7 * FieldCore.hash(j * 19 + 6));
                    at = onRing(floor, r, phi).add(0, .3 + .9 * FieldCore.hash(j * 7 + 1) + .1 * Math.sin(now * .03 + j * 2.1), 0);
                    alpha = .45f + .45f * Mth.sin((float) (now * .11 + j * 2.7));
                    size = .045f;
                    tint = j % 2 == 0 ? GOLD : style.light();
                }
                float a = alpha * shown * leave * fade(view.cam(), at, inside ? .8 : .4, inside ? 2.2 : 1.2);
                if (a <= .01f) continue;
                ChargeParts.sprite(p, buf, view, at, size * 2.4f, 0, argb(.45f * a, tint));
                ChargeParts.sprite(p, buf, view, at, size, 0, argb(a, 0xF6FFE0));
            }
        });
    }

    /** Now and then a leaf carried up off the edge, swirling and turning over, fading out as it goes. */
    private static void leaves(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown,
            boolean inside) {
        float leave = 1 - smooth(0, 10, end);
        if (leave <= 0) return;
        int n = Mth.clamp(Math.round(R * .6f), 2, 8);
        float h = Mth.clamp(.5f + .1f * R, .7f, 2f);
        out.submitCustomGeometry(pose, ChargeParts.blend("life_ray/leaves"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double period = 110 + 60 * FieldCore.hash(i * 29 + 4);
                double t = now / period + FieldCore.hash(i * 37 + 8);
                int cycle = (int) Math.floor(t);
                float k = (float) (t - cycle);
                double phi = Math.PI * 2 * FieldCore.hash(i * 53 + cycle * 11) + k * .6;
                double r = R * (1 - .12 * k);
                Vec3 at = onRing(floor, r, phi).add(0, .15 + k * h * 1.7, 0);
                float a = (float) Math.min(1, Math.min(k / .15, (1 - k) / .5)) * shown * leave
                        * fade(view.cam(), at, inside ? .9 : .5, inside ? 2.4 : 1.4);
                if (a <= .01f) continue;
                int tint = LEAF[(int) (FieldCore.hash(i * 61 + cycle) * LEAF.length)];
                float roll = (float) (k * 5 + i * 1.3);
                ChargeParts.frame(p, buf, view, at, .11f + .04f * (float) FieldCore.hash(i * 3 + cycle), roll,
                        (int) (FieldCore.hash(i * 71 + cycle) * 4), 2, 2, argb(.9f * a, tint));
            }
        });
    }
}
