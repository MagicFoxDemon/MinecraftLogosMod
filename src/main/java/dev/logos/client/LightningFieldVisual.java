package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
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
 * The lightning field (FIELD_VFX.md): the shared field, charged.
 * <ul>
 * <li>Circles: a burst of sharp rays outside, fine spokes in the middle, a ring crackling with lightning inside, and the same
 * small crackling ring as the clock's hand. Their lines carry current ({@code lightning_field_line.fsh}): a web of
 * fine cyan-white arcs jumping about many times a second over {@code lightning/trail_noise}, sparks racing round,
 * stretches of line flaring and dying out.</li>
 * <li>Curtain: a faint veil of static threads rising, and over it thin upright arcs striking at random round
 * the edge ({@code lightning_field_curtain.fsh}), more of them as the curtain flares on a pulse.</li>
 * <li>Runes: angular thunder runes.</li>
 * <li>Its own extras: on a pulse, a few arcs crackling on the ground where the wave of light passes; and now
 * and then a small crackle on the ground somewhere in the field.</li>
 * </ul>
 */
final class LightningFieldVisual extends FieldCore {
    /** As the shared core's: the ticks a pulse's wave takes to reach the edge. */
    private static final int WAVE = 14;

    private final List<Long> pulses = new ArrayList<>();

    LightningFieldVisual() {
        super(FieldStyle.of(Element.LIGHTNING)
                .withCircles("lightning_field/circle_01", "charge/circle_18", "charge/circle_10", "charge/circle_10")
                .withLines("core/lightning_field_line", "lightning/trail_noise")
                .withCurtain("lightning_field/veil")
                .withRunes("lightning_field/runes", 3, 3));
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
        double far = view.cam().distanceTo(floor);
        if (far > 96 + R) return;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float open = opened(f, pt), end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        arcs(pose, out, floor, R * open, now, end, shown);
        if (far < 48 + R) crackles(pose, out, floor, R * open, now, end, shown);
        bursts(pose, out, floor, R, now);
        pose.popPose();
    }

    /** The arcs striking on the edge curtain, just outside the shared curtain, as tall as it. */
    private void arcs(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float end, float shown) {
        if (R <= .05f) return;
        float h = Mth.clamp(.5f + .1f * R, .7f, 2f) * (1 - smooth(0, 8, end));
        if (h <= .01f) return;
        float flare = 0;
        for (long t : pulses) flare = Math.max(flare, (float) Math.exp(-(now - t - WAVE * .8) * (now - t - WAVE * .8) / 18));
        // The vertex alpha: up to 0.5 how much shows, above it how many columns strike (see the shader).
        int color = argb(shown * (.42f + .55f * flare), lerpColor(style.main(), style.light(), .35f));
        int segments = Mth.clamp((int) (R * 10), 24, 120);
        float columns = Math.max(10, Math.round(R * (float) (Math.PI * 2) / 1.1f));
        out.submitCustomGeometry(pose, LightningFieldParts.CURTAIN_ARCS, (p, buf) ->
                ChargeParts.cylinder(p, buf, floor, R + .02f, h * 1.05f, segments, 0, columns, 0, 1, color, color));
    }

    /** Now and then a small crackle somewhere on the ground in the field, never more than a few at once. */
    private void crackles(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float end, float shown) {
        if (R <= .5f) return;
        int n = Mth.clamp(Math.round(R * .5f), 2, 6);
        float fade = shown * (1 - smooth(0, 6, end));
        if (fade <= 0) return;
        out.submitCustomGeometry(pose, ChargeParts.add("lightning_impact/arcs"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double t = (now + i * 3.7) / 7;
                int beat = (int) Math.floor(t);
                double phase = t - beat;
                int seed = i * 131 + beat * 17;
                if (phase > .35 || hash(seed) < .45) continue;
                double phi = Math.PI * 2 * hash(seed + 1);
                double r = R * (.15 + .75 * Math.sqrt(hash(seed + 2)));
                Vec3 at = floor.add(Math.sin(phi) * r, .06, -Math.cos(phi) * r);
                float a = fade * (float) ((1 - phase / .35) * (.55 + .45 * hash(seed + (int) (now * 3))));
                LightningFieldParts.flatFrame(p, buf, at, .35f + .25f * (float) hash(seed + 3), (float) (Math.PI * 2 * hash(seed + 4)),
                        (int) (hash(seed + 5) * 4), 2, 2, argb(a * .8f, lerpColor(style.main(), style.light(), .6f)));
            }
        });
    }

    /** On a pulse, a few arcs crackling on the ground as the wave of light passes over them. */
    private void bursts(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now) {
        if (pulses.isEmpty()) return;
        int m = Mth.clamp(Math.round(R * 1.1f), 5, 12);
        float size = Math.min(1.1f, .45f + .04f * R);
        out.submitCustomGeometry(pose, ChargeParts.add("lightning_impact/arcs"), (p, buf) -> {
            for (long t : pulses) {
                float k = (float) ((now - t) / WAVE);
                if (k < 0 || k > 1) continue;
                for (int i = 0; i < m; i++) {
                    int seed = (int) (t * 31) + i * 57;
                    // Each arc strikes as the wave reaches it, for a moment.
                    float at = .15f + .75f * (float) hash(seed);
                    float near = 1 - Math.abs(k - at) / .09f;
                    if (near <= 0) continue;
                    float r = R * (.08f + 1.02f * (1 - (1 - at) * (1 - at)));
                    double phi = Math.PI * 2 * (i + hash(seed + 1) * .8) / m;
                    Vec3 c = floor.add(Math.sin(phi) * r, .08, -Math.cos(phi) * r);
                    LightningFieldParts.flatFrame(p, buf, c, size, (float) phi + (float) hash(seed + 2) * 2, (int) (hash(seed + 3) * 4), 2, 2,
                            argb(near * .9f, lerpColor(style.main(), style.light(), .7f)));
                }
            }
        });
    }
}
