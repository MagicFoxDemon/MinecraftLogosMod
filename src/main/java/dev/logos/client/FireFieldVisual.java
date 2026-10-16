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
 * The fire field (FIELD_VFX.md): the shared field dressed in fire.
 * <ul>
 * <li>Lines: fire rolls along inside every line of the circles ({@code fire_field_line.fsh} over
 * {@code fire_ray/noise}), gold at the heart of a line and dark red at its edges, with a heat shimmer through
 * it; on ending the circles burn away along the fire's own pattern.</li>
 * <li>Circles: a swirling ring of flame tongues outside ({@code fire_field/circle_05}), the shared ornamental
 * circle in the middle, fire's sun circle inside ({@code fire_charge/sun_circle}); the clock's hand is the
 * alchemical fire triangle ({@code fire_field/circle_25}).</li>
 * <li>Edge: over the shared curtain of light a low see-through curtain of flame
 * ({@code fire_field_curtain.fsh}), a ragged line of tongues licking upwards, gold at the foot and dark red at
 * the tips, leaping up when the field pulses.</li>
 * <li>Runes: fire's own ({@code fire_field/runes}).</li>
 * <li>A few embers rising off the circles.</li>
 * </ul>
 */
final class FireFieldVisual extends FieldCore {
    private static final String NOISE = "fire_ray/noise";
    private static final int FLARE = 18;

    private final List<Long> pulses = new ArrayList<>();

    FireFieldVisual() {
        super(FieldStyle.of(Element.FIRE)
                .withCircles("fire_field/circle_05", "charge/circle_11", "fire_charge/sun_circle", "fire_field/circle_25")
                .withLines("core/fire_field_line", NOISE)
                .withRunes("fire_field/runes", 3, 3)
                // The flame curtain below is the edge; the core's own curtain is left out.
                .withCurtain("charge/pillar", 0));
    }

    @Override
    public boolean tick(Level level, LiveField f) {
        boolean alive = super.tick(level, f);
        long now = ClientCharges.ticks();
        pulses.addAll(f.newPulses());
        pulses.removeIf(t -> now - t > FLARE);
        return alive;
    }

    @Override
    public void submit(Level level, LiveField f, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, f, camera, pt, pose, out);
        View view = View.of(camera);
        Vec3 floor = f.floor(pt);
        float R = f.radius() * opened(f, pt);
        double dist = view.cam().distanceTo(floor);
        if (dist > 96 + f.radius() || R <= .05f) return;
        float end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        flames(pose, out, view, floor, R, now, end, shown);
        // Too far to make out single embers.
        if (dist < 40 + f.radius()) embers(pose, out, view, floor, R, now, end, shown, f.cameraInside(view.cam(), pt));
        pose.popPose();
    }

    /** The curtain of flame at the edge: an open ring of strips, the shader drawing the tongues. */
    private void flames(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown) {
        float flare = 0;
        for (long t : pulses) {
            double k = now - t - 9;
            flare = Math.max(flare, (float) Math.exp(-k * k / 16));
        }
        // Sinking into the ground as it ends.
        float h = Mth.clamp(.45f + .1f * R, .65f, 1.2f) * (1 - smooth(0, 10, end));
        float a = shown * .45f;
        if (h <= .02f || a <= .01f) return;
        int segments = Mth.clamp((int) (R * 10), 24, 120);
        float tiles = Math.max(2, Math.round(R * (float) (Math.PI * 2) / 2f));
        int sf = (int) (flare * 1000);
        Vec3 base = floor.add(0, .02, 0);
        out.submitCustomGeometry(pose, FieldParts.lines(NOISE, "core/fire_field_curtain", NOISE), (p, buf) -> {
            for (int i = 0; i < segments; i++) {
                double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
                Vec3 p0 = base.add(Math.cos(a0) * R, 0, Math.sin(a0) * R), p1 = base.add(Math.cos(a1) * R, 0, Math.sin(a1) * R);
                // Kept off the camera when standing at the edge.
                int c0 = argb(a * fade(view.cam(), p0.add(0, h * .4, 0), .6, 1.8), style.main());
                int c1 = argb(a * fade(view.cam(), p1.add(0, h * .4, 0), .6, 1.8), style.main());
                if (c0 >>> 24 == 0 && c1 >>> 24 == 0) continue;
                float u0 = tiles * i / segments, u1 = tiles * (i + 1) / segments;
                vertex(p, buf, p0, u0, 0, c0, sf);
                vertex(p, buf, p0.add(0, h, 0), u0, 1, c0, sf);
                vertex(p, buf, p1.add(0, h, 0), u1, 1, c1, sf);
                vertex(p, buf, p1, u1, 0, c1, sf);
            }
        });
    }

    /** One vertex of the flame curtain: the flare goes to the shader as {@code fieldParam.x}, in thousandths. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int flare) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setUv2(flare, 0).setNormal(pose, 0, 1, 0);
    }

    /** A few embers rising off the circles, each drifting up and dying out, then starting again elsewhere. */
    private void embers(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown,
            boolean inside) {
        int n = Mth.clamp(Math.round(R * 2.5f), 6, 28);
        float leave = 1 - smooth(0, 12, end);
        if (leave * shown <= .01f) return;
        out.submitCustomGeometry(pose, ChargeParts.add("fire_impact/ember"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double life = 50 + 30 * hash(i * 5 + 3);
                double t = now / life + hash(i * 11 + 7);
                int round = (int) Math.floor(t);
                float k = (float) (t - round);
                int seed = i * 977 + round * 131;
                double r = R * .95 * Math.sqrt(hash(seed + 1)), phi = Math.PI * 2 * hash(seed + 2);
                double rise = (1.4 + 1.4 * hash(seed + 3)) * k;
                double sway = .18 * Math.sin(now * .07 + i * 2.1) * k;
                Vec3 at = floor.add(Math.cos(phi) * r + sway, .1 + rise, Math.sin(phi) * r + sway * .6);
                float a = leave * shown * smooth(0, .12f, k) * (1 - smooth(.55f, 1, k))
                        * fade(view.cam(), at, inside ? .8 : .4, inside ? 2.2 : 1.2);
                if (a <= .01f) continue;
                float size = .05f + .04f * (float) hash(seed + 4);
                ChargeParts.sprite(p, buf, view, at, size * (1 - .4f * k), 0, argb(a, lerpColor(style.light(), style.deep(), k)));
            }
        });
    }
}
