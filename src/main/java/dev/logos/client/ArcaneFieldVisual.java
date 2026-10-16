package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.client.ChargeParts.View;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The arcane field: bright, ornate starlight magic rather than void's dark depths (see FIELD_VFX.md).
 * <ul>
 * <li>circles: the zodiac astrolabe ({@code arcane_charge/circle_12}) over the whole radius and the star
 * ({@code arcane_charge/circle_04}) at the heart, the shared middle circle between them; the clock is a
 * small starburst circle;</li>
 * <li>lines ({@code arcane_field_line.fsh}): strands of mana (the arcane silk) drifting round in pink and
 * violet, bright threads running round, and a dust of stars twinkling along them;</li>
 * <li>curtain: a veil of thin threads of mana beaded with stars ({@code arcane_field/veil}) rising up the
 * edge, with faint runes rising up it and star glints twinkling on it, all flaring with each pulse;</li>
 * <li>runes: arcane's own runes, each with a soft halo and now and then a glint;</li>
 * <li>a few motes of starlight rising inside the field, and glints riding each pulse's wave.</li>
 * </ul>
 */
final class ArcaneFieldVisual extends FieldCore {
    private static final int PINK = 0xFF8AEE;
    /** Must match the shared core's wave, so the glints ride it. */
    private static final int WAVE = 14;

    private final List<Long> pulses = new ArrayList<>();

    ArcaneFieldVisual() {
        super(FieldStyle.of(Element.ARCANE)
                .withCircles("arcane_charge/circle_12", "charge/circle_11", "arcane_charge/circle_04", "charge/circle_15")
                .withLines("core/arcane_field_line", "arcane_ray/silk")
                .withCurtain("arcane_field/veil")
                .withRunes("arcane_ray/runes", 3, 3));
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
        float full = f.radius();
        // The extras are small: past this they would not be made out.
        if (view.cam().distanceTo(floor) > 48 + full) return;
        float R = full * opened(f, pt), end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        if (R <= .3f || shown <= 0) return;
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        boolean inside = f.cameraInside(view.cam(), pt);
        float flare = f.pulses() == 0 ? 0 : (float) Math.exp(-Math.pow(f.sincePulse(pt) - WAVE * .8, 2) / 18);
        float leave = 1 - smooth(0, 10, end);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (leave > 0) {
            curtainRunes(pose, out, view, floor, R, now, shown * leave, flare, inside);
            curtainGlints(pose, out, view, floor, R, now, shown * leave, flare, inside);
            runeHalos(pose, out, view, floor, R, now, end, shown * leave, inside);
            motes(pose, out, view, floor, R, now, shown * leave, inside);
        }
        waveGlints(pose, out, view, floor, full, now);
        pose.popPose();
    }

    @Override
    public void end() {
        super.end();
        pulses.clear();
    }

    /** The shared curtain's height. */
    private static float curtainHeight(float R) {
        return Mth.clamp(.5f + .1f * R, .7f, 2f);
    }

    /** Faint runes written on the curtain, each rising slowly up it and fading out at the top. */
    private void curtainRunes(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float shown, float flare,
            boolean inside) {
        int n = Mth.clamp(Math.round(R * 3), 8, 36);
        float h = curtainHeight(R);
        int frames = style.runeCols() * style.runeRows();
        out.submitCustomGeometry(pose, ChargeParts.add(style.runes()), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double period = 70 + 40 * hash(i * 5 + 11);
                double cycle = (now + period * hash(i * 9 + 3)) / period;
                double k = cycle % 1;
                double phi = Math.PI * 2 * (i + .5 * hash(i * 17 + 2)) / n + now * .0015;
                float size = .1f + .04f * (float) hash(i * 23 + 7) + .02f * Math.min(R, 8) / 8;
                Vec3 at = floor.add(Math.cos(phi) * R, .15 + k * (h - .1), Math.sin(phi) * R);
                float a = (float) Math.sin(Math.PI * k) * (.35f + .5f * flare) * shown;
                a *= fade(view.cam(), at, inside ? .9 : .5, inside ? 2.6 : 1.6);
                if (a <= .01f) continue;
                // Lying on the curtain: across it along the ring, upright.
                Vec3 across = new Vec3(-Math.sin(phi), 0, Math.cos(phi)).scale(size), up = new Vec3(0, size, 0);
                int frame = (i * 5 + (int) cycle) % frames;
                glyph(p, buf, at, across, up, frame, style.runeCols(), style.runeRows(),
                        argb(a, lerpColor(PINK, style.light(), (float) hash(i * 31 + 1))));
            }
        });
    }

    /** Star glints twinkling on the curtain and drifting up it. */
    private void curtainGlints(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float shown, float flare,
            boolean inside) {
        int n = Mth.clamp(Math.round(R * 2.5f), 6, 30);
        float h = curtainHeight(R);
        out.submitCustomGeometry(pose, ChargeParts.add("arcane_impact/star"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double h1 = hash(i * 7 + 40), h2 = hash(i * 11 + 41);
                double phi = Math.PI * 2 * h1 - now * .002;
                double k = (now * .006 + h2) % 1;
                Vec3 at = floor.add(Math.cos(phi) * R, .1 + k * h * .9, Math.sin(phi) * R);
                float tw = (float) Math.pow(.5 + .5 * Math.sin(now * (.08 + .07 * h2) + h1 * 40), 4);
                float a = shown * (float) Math.sin(Math.PI * k) * (.15f + .85f * tw) * (.7f + .6f * flare);
                a *= fade(view.cam(), at, inside ? .9 : .5, inside ? 2.6 : 1.6);
                if (a <= .01f) continue;
                float size = (.12f + .1f * tw) * (1 + .5f * flare);
                ChargeParts.sprite(p, buf, view, at, size, 0, argb(a, lerpColor(PINK, 0xFFFFFF, .5f + .5f * tw)));
            }
        });
    }

    /**
     * Under each of the shared core's floating runes, a soft halo, and now and then a glint across it. The
     * runes' places follow {@link FieldCore}'s own (same count, hash and motion).
     */
    private void runeHalos(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown,
            boolean inside) {
        int n = Mth.clamp(Math.round(2 + R * .8f), 3, 10);
        float leave = smooth(0, 10, end);
        Vec3[] at = new Vec3[n];
        float[] alpha = new float[n], size = new float[n];
        for (int i = 0; i < n; i++) {
            double h1 = hash(i * 7 + 1), h2 = hash(i * 13 + 5);
            double phi = Math.PI * 2 * i / n + now * .003 + h1;
            double r = R * (.3 + .5 * h2);
            double y = .7 + .5 * hash(i * 3 + 2) + .08 * Math.sin(now * .05 + i * 1.7) - .6 * leave;
            at[i] = floor.add(Math.sin(phi) * r, y, -Math.cos(phi) * r);
            size[i] = Math.min(.36f, .18f + .03f * R);
            alpha[i] = shown * fade(view.cam(), at[i], inside ? .9 : .5, inside ? 2.4 : 1.6);
        }
        out.submitCustomGeometry(pose, ChargeParts.add("arcane/glow"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                if (alpha[i] <= .01f) continue;
                ChargeParts.sprite(p, buf, view, at[i], size[i] * 2.2f, 0, argb(.32f * alpha[i], style.main()));
            }
        });
        out.submitCustomGeometry(pose, ChargeParts.add("arcane_ray/glint"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                // A glint every few seconds, each rune on its own beat.
                double k = ((now + 97 * hash(i * 29 + 4)) / 55) % 1;
                float g = (float) Math.exp(-Math.pow((k - .5) / .07, 2));
                // Its long arms would streak across the view up close.
                float a = .9f * g * alpha[i] * (inside ? fade(view.cam(), at[i], 1.6, 4) : 1);
                if (a <= .01f) continue;
                ChargeParts.sprite(p, buf, view, at[i], size[i] * (1.1f + .9f * g), (float) (Math.PI / 4 * hash(i)), argb(a, 0xFFF0FF));
            }
        });
    }

    /** A few motes of starlight rising slowly from the circles and going out. */
    private void motes(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float shown, boolean inside) {
        int n = Mth.clamp(Math.round(R * 2), 5, 20);
        out.submitCustomGeometry(pose, ChargeParts.add("arcane/spark"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double period = 80 + 50 * hash(i * 3 + 70);
                double cycle = (now + period * hash(i * 5 + 71)) / period;
                double k = cycle % 1;
                int round = (int) cycle;
                // Each time round, somewhere new.
                double phi = Math.PI * 2 * hash(i * 13 + round * 7 + 72);
                double r = R * Math.sqrt(hash(i * 19 + round * 11 + 73)) * .92;
                Vec3 at = floor.add(Math.cos(phi) * r + .15 * Math.sin(now * .03 + i), .1 + k * 1.8, Math.sin(phi) * r);
                float tw = .6f + .4f * Mth.sin((float) (now * .2 + i * 3.1));
                float a = shown * (float) Math.sin(Math.PI * k) * tw * .55f * fade(view.cam(), at, inside ? .7 : .4, inside ? 2 : 1.2);
                if (a <= .01f) continue;
                ChargeParts.sprite(p, buf, view, at, .035f + .02f * (float) hash(i + 74), 0,
                        argb(a, lerpColor(PINK, 0xFFFFFF, (float) hash(i * 2 + 75))));
            }
        });
    }

    /** Glints riding each pulse's wave out to the edge. */
    private void waveGlints(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now) {
        if (pulses.isEmpty()) return;
        int n = Mth.clamp(Math.round(8 + R * 2), 10, 32);
        out.submitCustomGeometry(pose, ChargeParts.add("arcane_impact/star"), (p, buf) -> {
            for (long t : pulses) {
                float k = (float) ((now - t) / WAVE);
                if (k < 0 || k > 1) continue;
                float r = R * (.08f + 1.02f * (1 - (1 - k) * (1 - k)));
                for (int i = 0; i < n; i++) {
                    double phi = Math.PI * 2 * (i + hash((int) t * 3 + i)) / n;
                    Vec3 at = floor.add(Math.cos(phi) * r, .12 + .1 * hash(i + 90), Math.sin(phi) * r);
                    float tw = .5f + .5f * Mth.sin((float) (now * .9 + i * 2.7));
                    float a = (1 - k) * smooth(0, .15f, k) * (.4f + .6f * tw);
                    ChargeParts.sprite(p, buf, view, at, .18f + .12f * tw, 0, argb(a, lerpColor(PINK, 0xFFFFFF, tw)));
                }
            }
        });
    }

    /** A quad round {@code c} spanned by {@code a} and {@code b} (half-extents) showing one frame of a flipbook. */
    private static void glyph(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, int frame, int cols, int rows, int color) {
        float u0 = (frame % cols) / (float) cols, v0 = (frame / cols) / (float) rows, u1 = u0 + 1f / cols, v1 = v0 + 1f / rows;
        ChargeParts.vertex(pose, buf, c.subtract(a).add(b), u0, v0, color);
        ChargeParts.vertex(pose, buf, c.subtract(a).subtract(b), u0, v1, color);
        ChargeParts.vertex(pose, buf, c.add(a).subtract(b), u1, v1, color);
        ChargeParts.vertex(pose, buf, c.add(a).add(b), u1, v0, color);
    }
}
