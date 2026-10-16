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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The field every element shares (see FIELD_VFX.md), laid flat on the ground under the field's centre and as
 * wide as its radius:
 * <ul>
 * <li>three magic circles over the whole radius, turning at their own pace (the outer slowest, the middle
 * against it, the inner fastest), their lines filled by the style's line shader, and a thin ring at the
 * edge; on opening they spread from the centre to full size in half a second, and on ending they break up
 * from the outside in;</li>
 * <li>a clock: a small circle going once round a ring between the inner and middle circles over the
 * field's whole life, leaving a glowing trail behind it;</li>
 * <li>a low see-through curtain of light standing at the edge, its light running upwards;</li>
 * <li>on each pulse, a wave of light running out from the centre to the edge, lighting the lines as it
 * passes, and the curtain flaring; where a body enters, a ripple at its feet;</li>
 * <li>runes floating over the circles, bobbing and drifting round.</li>
 * </ul>
 * Coloured and dressed by a {@link FieldStyle}. An element's {@code <Element>FieldVisual} extends it with its
 * own style, and adds its own layers by overriding {@link #tick}, {@link #submit} and {@link #end} and
 * calling these first.
 */
public class FieldCore implements FieldVisual {
    /** Ticks to spread from the centre to full size. */
    protected static final int OPEN = 10;
    /** Ticks it takes to break up and fade after it ends. */
    protected static final int CLOSE = 20;
    /** Ticks a pulse's wave takes to run from the centre to the edge. */
    protected static final int WAVE = 14;
    private static final int RIPPLE = 16;

    protected final FieldStyle style;
    private final List<Long> pulses = new ArrayList<>();
    private final List<LiveField.Entry> entries = new ArrayList<>();

    protected FieldCore(FieldStyle style) {
        this.style = style;
    }

    @Override
    public final boolean legacy() { return false; }

    @Override
    public boolean tick(Level level, LiveField f) {
        long now = ClientCharges.ticks();
        pulses.addAll(f.newPulses());
        pulses.removeIf(t -> now - t > WAVE + 2);
        entries.addAll(f.newEntries());
        entries.removeIf(e -> now - e.tick() > RIPPLE + 6);
        return f.phase() == LiveField.Phase.ACTIVE || f.sinceEnd(0) < CLOSE;
    }

    /** Client ticks of the pulses still showing (the latest last), for an element that times its own layers to them. */
    protected final List<Long> pulseTimes() {
        return java.util.Collections.unmodifiableList(pulses);
    }

    /** How many runes float over a field of radius {@code R}. */
    protected static int runeCount(float R) {
        return Mth.clamp(Math.round(2 + R * .8f), 3, 10);
    }

    /**
     * Where rune {@code i} of {@code n} floats over a field lying at {@code floor} with (opened) radius {@code R},
     * {@code end} ticks after it ended (0 while it lasts), so an element can dress the runes it draws.
     */
    protected static Vec3 runeAt(int i, int n, Vec3 floor, float R, double now, float end) {
        double h1 = hash(i * 7 + 1), h2 = hash(i * 13 + 5);
        double phi = Math.PI * 2 * i / n + now * .003 + h1;
        double r = R * (.3 + .5 * h2);
        double y = .7 + .5 * hash(i * 3 + 2) + .08 * Math.sin(now * .05 + i * 1.7) - .6 * smooth(0, 10, end);
        return onRing(floor, r, phi).add(0, y, 0);
    }

    /** How far the field has spread out, 0 to 1, easing out over {@link #OPEN} ticks. */
    protected static float opened(LiveField f, float pt) {
        float k = Mth.clamp(f.age(pt) / OPEN, 0, 1);
        return 1 - (1 - k) * (1 - k) * (1 - k);
    }

    @Override
    public void submit(Level level, LiveField f, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        View view = View.of(camera);
        Vec3 floor = f.floor(pt);
        float R = f.radius();
        // Too far to make out: nothing to draw.
        if (view.cam().distanceTo(floor) > 96 + R) return;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float open = opened(f, pt), end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        float sweep = sweep(now);
        circles(pose, out, floor, R * open, now, sweep, end, shown);
        clock(pose, out, floor, R * open, f.progress(pt), now, end, shown);
        curtain(pose, out, view, floor, R * open, now, end, shown);
        waves(pose, out, floor, R, now);
        ripples(pose, out, f, now);
        runes(pose, out, view, floor, R * open, now, end, shown, f.cameraInside(view.cam(), pt));
        pose.popPose();
    }

    /** How far out the latest pulse's light has swept, in circle radii ({@link FieldParts#NO_SWEEP} for none). */
    private float sweep(double now) {
        if (pulses.isEmpty()) return FieldParts.NO_SWEEP;
        float k = (float) ((now - pulses.getLast()) / WAVE);
        return k > 1.3f ? FieldParts.NO_SWEEP : k * 1.15f;
    }

    private void circles(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float sweep, float end, float shown) {
        if (R <= .01f) return;
        int tint = lerpColor(style.main(), style.light(), .2f);
        float[] radius = {R, R * .66f, R * .36f};
        String[] texture = {style.outer(), style.middle(), style.inner()};
        float[] turn = {(float) (now * .004), (float) (-now * .007), (float) (now * .015)};
        float[] bright = {.55f, .5f, .7f};
        // Breaking up from the outside in once it has ended.
        float[] dissolve = {smooth(0, 10, end), smooth(3, 13, end), smooth(6, 16, end)};
        for (int i = 0; i < 3; i++) {
            int layer = i;
            if (dissolve[layer] >= 1) continue;
            // Each circle's own sweep: the light reaches it when the wave passes its radius.
            float own = sweep >= FieldParts.NO_SWEEP ? sweep : sweep * R / radius[layer];
            Vec3 at = floor.add(0, .03 + .01 * layer, 0);
            out.submitCustomGeometry(pose, FieldParts.lines(texture[layer], style.lineShader(), style.fill()), (p, buf) ->
                    FieldParts.circle(p, buf, at, radius[layer], turn[layer], argb(bright[layer] * shown, layer == 2 ? lerpColor(style.main(), style.light(), .55f) : tint), own,
                            dissolve[layer]));
        }
        // The thin ring at the edge, and a soft glow under the whole circle.
        float rim = shown * (1 - smooth(0, 6, end));
        out.submitCustomGeometry(pose, ChargeParts.add("void_ray/ring"), (p, buf) ->
                ChargeParts.flat(p, buf, floor.add(0, .05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R / .84f, 0, argb(.45f * rim, style.main())));
        out.submitCustomGeometry(pose, ChargeParts.add("heal/glow"), (p, buf) ->
                ChargeParts.flat(p, buf, floor.add(0, .02, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R * 1.15f, 0,
                        argb(.1f * shown * (1 - smooth(0, 14, end)), style.deep())));
    }

    /** A point at angle {@code phi} (0 north, turning clockwise seen from above) on a ring of radius {@code r}. */
    protected static Vec3 onRing(Vec3 c, double r, double phi) {
        return c.add(Math.sin(phi) * r, 0, -Math.cos(phi) * r);
    }

    private void clock(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, float progress, double now, float end, float shown) {
        if (R <= .01f) return;
        float a = shown * (1 - smooth(0, 8, end));
        if (a <= 0) return;
        float rc = R * .51f;
        Vec3 c = floor.add(0, .07, 0);
        double phi = progress * Math.PI * 2;
        // The track, faint.
        out.submitCustomGeometry(pose, ChargeParts.add("void_ray/ring"), (p, buf) ->
                ChargeParts.flat(p, buf, c, new Vec3(1, 0, 0), new Vec3(0, 0, 1), rc / .84f, 0, argb(.25f * a, style.main())));
        // The trail: brightest just behind the hand, fading back to where it began.
        float w = Math.max(.1f, R * .05f);
        int n = Math.max(2, (int) (96 * progress) + 2);
        out.submitCustomGeometry(pose, ChargeParts.add("force/streak"), (p, buf) -> {
            for (int i = 0; i < n - 1; i++) {
                double p0 = phi * i / (n - 1), p1 = phi * (i + 1) / (n - 1);
                float k0 = (float) i / (n - 1), k1 = (float) (i + 1) / (n - 1);
                band(p, buf, c, rc, w, p0, p1, argb(a * (.3f + .7f * k0 * k0), lerpColor(style.main(), style.light(), k0)),
                        argb(a * (.3f + .7f * k1 * k1), lerpColor(style.main(), style.light(), k1)));
            }
        });
        // The hand: a small circle spinning, with a glow under it.
        Vec3 hand = onRing(c, rc, phi).add(0, .01, 0);
        float size = Math.max(.35f, R * .13f);
        out.submitCustomGeometry(pose, ChargeParts.add("heal/glow"), (p, buf) ->
                ChargeParts.flat(p, buf, hand, new Vec3(1, 0, 0), new Vec3(0, 0, 1), size * 2.2f, 0, argb(.8f * a, style.main())));
        out.submitCustomGeometry(pose, FieldParts.lines(style.clock(), style.lineShader(), style.fill()), (p, buf) ->
                FieldParts.circle(p, buf, hand, size, (float) (now * .06), argb(a, style.light()), FieldParts.NO_SWEEP, 0));
    }

    /** One segment of a flat band along a ring, {@code w} wide, from angle {@code p0} to {@code p1}; V across, U fixed in the texture's middle. */
    private static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, double r, double w, double p0, double p1, int c0, int c1) {
        ChargeParts.vertex(pose, buf, onRing(c, r - w, p0), .5f, 0, c0);
        ChargeParts.vertex(pose, buf, onRing(c, r + w, p0), .5f, 1, c0);
        ChargeParts.vertex(pose, buf, onRing(c, r + w, p1), .5f, 1, c1);
        ChargeParts.vertex(pose, buf, onRing(c, r - w, p1), .5f, 0, c1);
    }

    private void curtain(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown) {
        if (R <= .05f) return;
        float flare = 0;
        for (long t : pulses) flare = Math.max(flare, (float) Math.exp(-(now - t - WAVE * .8) * (now - t - WAVE * .8) / 18));
        float h = Mth.clamp(.5f + .1f * R, .7f, 2f) * (1 - smooth(0, 8, end));
        if (h <= .01f) return;
        if (style.curtainAlpha() <= 0) return;
        float a = shown * style.curtainAlpha() * (1 + 2.7f * flare);
        int segments = Mth.clamp((int) (R * 10), 24, 120);
        float tiles = Math.max(1, Math.round(R * (float) (Math.PI * 2) / 2.5f));
        float v = (float) (-now * .02);
        out.submitCustomGeometry(pose, ChargeParts.add(style.curtain()), (p, buf) ->
                ChargeParts.cylinder(p, buf, floor, R, h, segments, (float) (now * .001), tiles, v, .8f, argb(a, style.main()), argb(0, style.deep())));
    }

    /**
     * Each pulse: a thin ring of light running out over the ground from the centre to the edge, with a soft
     * glow trailing it once it is out of the middle (a soft ring that small would be a solid white disc).
     */
    private void waves(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now) {
        if (pulses.isEmpty()) return;
        int color = lerpColor(style.main(), style.light(), .5f);
        out.submitCustomGeometry(pose, ChargeParts.add("void_ray/ring"), (p, buf) -> {
            for (long t : pulses) {
                float k = (float) ((now - t) / WAVE);
                if (k < 0 || k > 1) continue;
                float r = R * (.08f + 1.02f * (1 - (1 - k) * (1 - k)));
                ChargeParts.flat(p, buf, floor.add(0, .09, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r / .84f, t * .7f,
                        argb((1 - k) * smooth(0, .2f, k) * .8f, color));
            }
        });
        out.submitCustomGeometry(pose, ChargeParts.add("heal/ring"), (p, buf) -> {
            for (long t : pulses) {
                float k = (float) ((now - t) / WAVE);
                if (k < 0 || k > 1) continue;
                float r = R * (.08f + 1.02f * (1 - (1 - k) * (1 - k)));
                ChargeParts.flat(p, buf, floor.add(0, .08, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, t * .7f,
                        argb((1 - k) * smooth(.3f, .55f, k) * .3f, style.main()));
            }
        });
    }

    /** Where a body entered: two small ripples spreading at its feet. */
    private void ripples(PoseStack pose, SubmitNodeCollector out, LiveField f, double now) {
        if (entries.isEmpty()) return;
        double ground = f.ground();
        out.submitCustomGeometry(pose, ChargeParts.add("heal/ring"), (p, buf) -> {
            for (LiveField.Entry e : entries) {
                for (int i = 0; i < 2; i++) {
                    float k = (float) ((now - e.tick() - i * 4) / RIPPLE);
                    if (k < 0 || k > 1) continue;
                    Vec3 at = new Vec3(e.at().x, ground + .1, e.at().z);
                    ChargeParts.flat(p, buf, at, new Vec3(1, 0, 0), new Vec3(0, 0, 1), .25f + 1.1f * k, 0,
                            argb((1 - k) * .9f, i == 0 ? style.light() : style.main()));
                }
            }
        });
    }

    private void runes(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown, boolean inside) {
        if (R <= .3f) return;
        int n = runeCount(R);
        int frames = style.runeCols() * style.runeRows();
        float leave = smooth(0, 10, end);
        out.submitCustomGeometry(pose, ChargeParts.add(style.runes()), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double h1 = hash(i * 7 + 1);
                Vec3 at = runeAt(i, n, floor, R, now, end);
                float size = Math.min(.36f, .18f + .03f * R);
                float twinkle = .7f + .3f * Mth.sin((float) (now * .09 + i * 2.3));
                // Kept off the camera, above all when standing in the field.
                float a = shown * (1 - leave) * twinkle * fade(view.cam(), at, inside ? .9 : .5, inside ? 2.4 : 1.6);
                if (a <= .01f) continue;
                ChargeParts.frame(p, buf, view, at, size, (float) Math.sin(now * .02 + i) * .25f, (i * 4) % frames, style.runeCols(),
                        style.runeRows(), argb(a, lerpColor(style.light(), style.main(), (float) h1 * .6f)));
            }
        });
    }

    /** A steady pseudo-random number in [0, 1) for {@code i}. */
    protected static double hash(int i) {
        double x = Math.sin(i * 12.9898 + 78.233) * 43758.5453;
        return x - Math.floor(x);
    }
}
