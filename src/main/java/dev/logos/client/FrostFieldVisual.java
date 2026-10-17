package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

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
 * The frost field (see FIELD_VFX.md): the shared field dressed as ice.
 * <ul>
 * <li>circles: frost's crystal lattice (its charge's) over the whole radius, a thin spoked circle in the middle,
 * an ice-crystal mandala inside, and the clock hand a snowflake rune; their lines filled with ice
 * ({@code frost_field_line.fsh}): cold mist drifting through, a net of cracks catching a slow refracting glint,
 * frost points twinkling; they shatter when the field ends;</li>
 * <li>the edge: over the shared wisps, a curtain of ice mist ({@code frost_field_curtain.fsh}) with a jagged
 * crust of frost on the ground, mist rising slowly and thinning, and ice glints drifting up in it;</li>
 * <li>snowflake runes floating over the circles;</li>
 * <li>a few snowflakes falling inside, and a faint low mist round the inside of the edge.</li>
 * </ul>
 */
final class FrostFieldVisual extends FieldCore {
    /** Tileable ice: bright crack-like ridges between soft cells. */
    private static final String ICE = "frost/winter_trail";
    /** Tileable soft noise. */
    private static final String MIST = "frost/cube_distortion";
    /** Ticks the shared pulse wave takes to reach the edge (as in {@link FieldCore}). */
    private static final int WAVE = 14;

    FrostFieldVisual() {
        super(FieldStyle.of(Element.FROST)
                .withCircles("frost_charge/circle_23", "charge/circle_15", "frost_field/circle_24", "frost_ray/rune")
                .withCurtain("frost_ray/sheath")
                .withRunes("frost_field/runes", 3, 3)
                .withLines("core/frost_field_line", ICE));
    }

    @Override
    public void submit(Level level, LiveField f, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, f, camera, pt, pose, out);
        View view = View.of(camera);
        Vec3 floor = f.floor(pt);
        float R = f.radius() * opened(f, pt);
        double far = view.cam().distanceTo(floor);
        if (far > 96 + f.radius() || R <= .05f) return;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        float s = f.sincePulse(pt) - WAVE * .8f;
        float flare = f.pulses() > 0 ? (float) Math.exp(-s * s / 18) : 0;
        curtain(pose, out, floor, R, far, end, shown, flare);
        mist(pose, out, floor, R, now, end, shown);
        if (far < 48 + R) snow(pose, out, view, floor, R, now, end, shown);
        pose.popPose();
    }

    /** The ice-mist curtain at the edge, drawn by its own shader; it melts down and away once the field ends. */
    private void curtain(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double far, float end, float shown, float flare) {
        float h = Mth.clamp(.5f + .1f * R, .7f, 2f) * (1 - smooth(0, 10, end));
        float a = shown * (1 - smooth(2, 16, end)) * (.42f + .3f * flare);
        if (h <= .01f || a <= .01f) return;
        int segments = Mth.clamp((int) (R * 10), 24, 120) / (far > 48 + R ? 2 : 1);
        float around = R * (float) (Math.PI * 2) / 2.5f;
        int color = argb(a, style.main());
        int hu = (int) Mth.clamp(h * 1000, 0, 65000), fu = (int) Mth.clamp(flare * 1000, 0, 65000);
        out.submitCustomGeometry(pose, FieldParts.lines(ICE, "core/frost_field_curtain", MIST), (p, buf) -> {
            for (int i = 0; i < segments; i++) {
                double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
                Vec3 p0 = floor.add(Math.cos(a0) * R, 0, Math.sin(a0) * R), p1 = floor.add(Math.cos(a1) * R, 0, Math.sin(a1) * R);
                float u0 = around * i / segments, u1 = around * (i + 1) / segments;
                vertex(p, buf, p0, u0, 0, color, hu, fu);
                vertex(p, buf, p0.add(0, h, 0), u0, 1, color, hu, fu);
                vertex(p, buf, p1.add(0, h, 0), u1, 1, color, hu, fu);
                vertex(p, buf, p1, u1, 0, color, hu, fu);
            }
        });
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int su, int sv) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setUv2(su, sv).setNormal(pose, 0, 1, 0);
    }

    /** A faint low mist lying round the inside of the edge, turning slowly. */
    private void mist(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float end, float shown) {
        float a = shown * (1 - smooth(0, 14, end)) * .09f;
        if (a <= .005f) return;
        int n = Mth.clamp(Math.round(R * 1.2f), 4, 12);
        out.submitCustomGeometry(pose, ChargeParts.add("frost_ray/mist"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double phi = Math.PI * 2 * (i + hash(i * 5 + 3) * .6) / n - now * .0015;
                double r = R * (.78 + .12 * hash(i * 9 + 1));
                Vec3 at = floor.add(Math.sin(phi) * r, .12 + .02 * (i % 3), -Math.cos(phi) * r);
                float size = R * .2f + .5f;
                ChargeParts.flat(p, buf, at, new Vec3(1, 0, 0), new Vec3(0, 0, 1), size, (float) (now * .004 + i * 1.3),
                        argb(a * (.7f + .3f * Mth.sin((float) (now * .02 + i))), lerpColor(style.main(), style.light(), .4f)));
            }
        });
    }

    /** A few snowflakes drifting down inside the field, kept off the camera. */
    private void snow(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown) {
        float leave = 1 - smooth(0, 12, end);
        if (shown * leave <= .01f) return;
        int n = Mth.clamp(Math.round(R * 2.5f), 6, 28);
        float top = 1.6f + .12f * R;
        out.submitCustomGeometry(pose, ChargeParts.add("frost_impact/snowflake"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double h1 = hash(i * 11 + 2), h2 = hash(i * 17 + 9), h3 = hash(i * 23 + 4);
                double period = 120 + 80 * h3;
                double k = (now / period + h1) % 1;
                double phi = Math.PI * 2 * h2 + i;
                double r = R * .92 * Math.sqrt(hash(i * 29 + 7));
                double sway = .25 * Math.sin(now * .03 + i * 1.9);
                Vec3 at = floor.add(Math.sin(phi) * r + sway, top * (1 - k) + .05, -Math.cos(phi) * r + sway * .6);
                float a = shown * leave * (float) (smooth(0, .12, k) * (1 - smooth(.85, 1, k)))
                        * fade(view.cam(), at, .5, 1.4) * .75f;
                if (a <= .01f) continue;
                ChargeParts.sprite(p, buf, view, at, .05f + .035f * (float) h3, (float) (now * .02 * (h2 - .5) + i), argb(a, style.light()));
            }
        });
    }
}
