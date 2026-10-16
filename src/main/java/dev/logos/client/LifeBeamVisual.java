package dev.logos.client;

import static dev.logos.client.LifeBeamParts.argb;
import static dev.logos.client.LifeBeamParts.band;
import static dev.logos.client.LifeBeamParts.fade;
import static dev.logos.client.LifeBeamParts.helix;
import static dev.logos.client.LifeBeamParts.lerpColor;
import static dev.logos.client.LifeBeamParts.quad;
import static dev.logos.client.LifeBeamParts.quadRolled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A lasting life {@code beam}, after Piloto's {@code NatureRay}: a gold-white core inside a green glow, the
 * prefab's thorny vine trail (VinesTrail_Beam) streaming forward along it with two more vines twisting
 * round it, a faint silky sheath, sparks of light running down the line, and leaves and motes of light
 * drifting off it. While the end reaches out a bud of light leads it; once it holds, a slow golden-green
 * bloom turns where it ends and leaves spill from there. Everything along the line fades out next to the
 * camera, so in first person the wand's tip stays clear. When it ends, {@link LifeBeamWake} takes over its
 * leaves and lets the line wither away.
 */
final class LifeBeamVisual {
    /** The beams being drawn, by the line each last drew: how its wake finds its radius and its live leaves. */
    private static final Map<LifeBeamVisual, Last> LAST = new HashMap<>();
    /** At most this many leaves and motes in flight per beam. */
    private static final int MOTE_CAP = 360;
    /** The stretch next to the muzzle that leaves and motes keep clear of, in blocks. */
    private static final double CLEAR = 2.2;

    record Last(Vec3 from, Vec3 to, float radius, List<LifeBeamParts.Mote> motes) {}

    private final boolean defaultMotes;
    private final List<LifeBeamParts.Mote> motes = new ArrayList<>();
    private long born = Long.MIN_VALUE, lastTick;
    private double leafDue, lightDue, bloomDue;
    /** 0 while the end is still reaching out, rising to 1 once it holds. */
    private float hold;
    private double lastT;

    /** True until this element draws its own beam. */
    static boolean legacy() { return false; }

    /** {@code defaultMotes}: false when a {@code :trail} particle replaces the element's own motes. */
    LifeBeamVisual(boolean defaultMotes) { this.defaultMotes = defaultMotes; }

    /** Each frame, from the cast point to the end as it reaches out; {@code out} already goes through {@link ShaderPackOverlay}. */
    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera, PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        long tick = level.getGameTime();
        double t = tick + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (born == Long.MIN_VALUE) { born = tick; lastTick = tick; lastT = t; }
        LAST.put(this, new Last(from, to, radius, motes));
        if (len < .05) return;
        Vec3 dir = delta.scale(1 / len);
        float s = Mth.clamp(radius / .5f, .4f, 3);
        // LogosFx reaches out at 4 blocks a tick from when the beam arrived; while the drawn length keeps up with
        // that, the end is still on its way (a tick of slack for the frame it arrived in).
        boolean reaching = len > (t - born) * 4 - 4.5;
        hold = Mth.clamp(hold + (float) (t - lastT) * (reaching ? -1 : .25f), 0, 1);
        lastT = t;
        for (int k = 0; k < Math.min(3, tick - lastTick); k++) spawn(level.getRandom(), from, dir, len, s, tick - k);
        lastTick = tick;
        motes.removeIf(m -> m.death() < t);

        Vec3 cam = camera.pos;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        float breathe = 1 + .08f * (float) Math.sin(t * .21);
        stack.pushPose();
        stack.translate(-cam.x, -cam.y, -cam.z);

        // A faint silky sheath (WaterNoise_MaskedAlpha), drifting slowly forward.
        float sheathScroll = (float) (-t * .05);
        out.submitCustomGeometry(stack, LifeBeamParts.SHEATH_TINT, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, 1.45f * s, sheathScroll, 6, argb(.2f, 0x3DB04E), 2.5, .8, 4));
        out.submitCustomGeometry(stack, LifeBeamParts.SHEATH, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, 1.45f * s * breathe, sheathScroll * 1.6f, 6, argb(.22f, 0xBFFF7A), 2.5, .8, 4));
        // The prefab's own line: the thorny vine trail streaming forward, green over an alpha-blended shadow.
        float vineScroll = (float) (-t * .3);
        out.submitCustomGeometry(stack, LifeBeamParts.VINES_TINT, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, .95f * s, vineScroll, 3, argb(.55f, 0x2E9E2C), 1.5, 1, 4));
        out.submitCustomGeometry(stack, LifeBeamParts.VINES, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, .95f * s, vineScroll, 3, argb(.6f, 0x86E84A), 1.5, 1, 4));
        // Two vines twisting round it, slowly turning.
        double turn = t * .09;
        out.submitCustomGeometry(stack, LifeBeamParts.VINES_TINT, (pose, buf) -> twin(pose, buf, cam, from, dir, len, s, turn, argb(.65f, 0x3FA834)));
        out.submitCustomGeometry(stack, LifeBeamParts.VINES, (pose, buf) -> twin(pose, buf, cam, from, dir, len, s, turn, argb(.45f, 0x9FE84A)));
        // The core: green glow, warm gold, a white-hot thread.
        out.submitCustomGeometry(stack, LifeBeamParts.CORE_TINT, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, .6f * s, 0, 1, argb(.5f, 0x34C83C), 1.2, 1.2, 4.5));
        out.submitCustomGeometry(stack, LifeBeamParts.CORE, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, .42f * s * breathe, 0, 1, argb(.75f, 0xFFC64A), 1.2, 1.2, 4.5));
        out.submitCustomGeometry(stack, LifeBeamParts.CORE, (pose, buf) ->
                band(pose, buf, cam, from, dir, len, .15f * s, 0, 1, argb(.85f, 0xFFFBE0), 1.2, .6, 2.5));
        // Sparks of light running down the line (Flecks / Speckle_Cluster), and a small glow at the wand's tip.
        out.submitCustomGeometry(stack, LifeBeamParts.DOT, (pose, buf) -> {
            sparks(pose, buf, cam, right, up, from, dir, len, s, t);
            float tip = fade(cam, from, 1.5, 4);
            if (tip > 0) quad(pose, buf, from.add(dir.scale(.05)), right, up, .22f * s, 0, 0, 1, 1, argb(.7f * tip, 0xF2FFD0));
        });
        out.submitCustomGeometry(stack, LifeBeamParts.GLOW, (pose, buf) -> {
            float tip = fade(cam, from, 1.5, 4);
            if (tip > 0) quad(pose, buf, from, right, up, .45f * s, 0, 0, 1, 1, argb(.4f * tip, 0x82FF8A));
        });

        // The end: a bud of light leading it out, then the bloom once it holds.
        Vec3 end = to.subtract(dir.scale(.08));
        float near = fade(cam, end, .6, 3);
        float bud = 1 - hold;
        if (bud > 0) out.submitCustomGeometry(stack, LifeBeamParts.DOT, (pose, buf) ->
                quad(pose, buf, end, right, up, .32f * s, 0, 0, 1, 1, argb(bud * near, 0xF2FFD0)));
        out.submitCustomGeometry(stack, LifeBeamParts.GLOW, (pose, buf) ->
                quad(pose, buf, end, right, up, (.5f + .6f * hold) * s * breathe, 0, 0, 1, 1, argb((.5f + .25f * hold) * near, 0x82FFA0)));
        if (hold > 0) {
            float spin = (float) (t * .02);
            int gold = lerpColor(0xFFC86A, 0xC8FF5A, .5f + .5f * (float) Math.sin(t * .13));
            out.submitCustomGeometry(stack, LifeBeamParts.RAYS, (pose, buf) -> {
                quadRolled(pose, buf, end, right, up, 1.0f * s * breathe, spin, argb(.55f * hold * near, gold));
                quadRolled(pose, buf, end, right, up, .75f * s, -spin * 1.6f + 1, argb(.4f * hold * near, 0x8CFF6A));
            });
            out.submitCustomGeometry(stack, LifeBeamParts.FLARE, (pose, buf) ->
                    quadRolled(pose, buf, end, right, up, .8f * s, -.7f, argb(.6f * hold * near, 0xD5FF6A)));
            out.submitCustomGeometry(stack, LifeBeamParts.DOT, (pose, buf) ->
                    quad(pose, buf, end, right, up, .3f * s, 0, 0, 1, 1, argb(.9f * hold * near, 0xF6FFE0)));
        }

        LifeBeamParts.motes(stack, out, motes, t, cam, right, up);
        stack.popPose();
    }

    /** The beam is gone: stop anything still playing. */
    void end() {
        LAST.remove(this);
    }

    /**
     * How the beam drawn last along {@code from}-{@code to} looked as it ended: its radius and the leaves and
     * motes still in flight (handed on to its wake), or null if no life beam drew that line.
     */
    static Last last(Vec3 from, Vec3 to) {
        Last best = null;
        double bestD = .25;
        for (Last l : LAST.values()) {
            double d = l.to.distanceTo(to) + l.from.distanceTo(from);
            if (d < bestD) { best = l; bestD = d; }
        }
        return best;
    }

    private static void twin(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 from, Vec3 dir, double len, float s, double turn, int color) {
        helix(pose, buf, cam, from, dir, len, .36f * s, .2f * s, 2.6, turn, (float) (-turn * 2), 1.4f, color, 1.2, 4.5);
        helix(pose, buf, cam, from, dir, len, .36f * s, .2f * s, 2.6, turn + Math.PI, (float) (-turn * 2 + .5), 1.4f, color, 1.2, 4.5);
    }

    /** Sparks streaming from the wand to the end on loose spirals; stateless, placed by time alone. */
    private static void sparks(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 right, Vec3 up, Vec3 from, Vec3 dir,
            double len, float s, double t) {
        Vec3[] b = LifeBeamParts.basis(dir);
        int n = Mth.clamp((int) (len * 1.1), 3, 72);
        for (int i = 0; i < n; i++) {
            double seed = i * 12.9898;
            double speed = .5 + .4 * hash(seed + 1);
            double d = ((hash(seed) * len + t * speed) % len);
            double th = hash(seed + 2) * 6.28 + d * .9;
            double rr = (.12 + .2 * hash(seed + 3)) * s;
            Vec3 p = from.add(dir.scale(d)).add(b[0].scale(Math.cos(th) * rr)).add(b[1].scale(Math.sin(th) * rr));
            float a = (float) Math.min(1, Math.min(d / 1.5, (len - d) / 1.5)) * fade(cam, p, .8, 3.5);
            if (a <= 0) continue;
            float tw = .6f + .4f * (float) Math.sin(t * .7 + seed);
            quad(pose, buf, p, right, up, (.05f + .04f * (float) hash(seed + 4)) * s, 0, 0, 1, 1, argb(a * tw, i % 3 == 0 ? 0xFFE89A : 0xE8FFC0));
        }
    }

    private static double hash(double v) {
        double n = Math.sin(v * 127.1) * 43758.5453;
        return n - Math.floor(n);
    }

    /** One tick's worth of leaves and motes off the line, and leaves spilling from the end once it holds. */
    private void spawn(RandomSource r, Vec3 from, Vec3 dir, double len, float s, long tick) {
        if (!defaultMotes || motes.size() >= MOTE_CAP || len < CLEAR + .5) return;
        double span = len - CLEAR;
        leafDue += Mth.clamp(span * .05, .15, 2.5);
        lightDue += Mth.clamp(span * .08, .2, 4);
        bloomDue += hold * .5;
        Vec3[] b = LifeBeamParts.basis(dir);
        for (; leafDue >= 1; leafDue--) {
            Vec3 at = around(r, from, dir, b, CLEAR + span * r.nextDouble(), .35 * s);
            motes.add(LifeBeamParts.leaf(r, at, Vec3.ZERO, tick, 50 + r.nextInt(40)));
        }
        for (; lightDue >= 1; lightDue--) {
            Vec3 at = around(r, from, dir, b, CLEAR + span * r.nextDouble(), .3 * s);
            motes.add(LifeBeamParts.light(r, at, Vec3.ZERO, tick, 30 + r.nextInt(30)));
        }
        Vec3 end = from.add(dir.scale(len - .15));
        for (; bloomDue >= 1; bloomDue--) {
            // Out from the end, mostly sideways and back towards the caster.
            Vec3 push = new Vec3(r.nextGaussian(), r.nextGaussian() * .6 + .3, r.nextGaussian()).normalize()
                    .subtract(dir.scale(.6)).scale(.035 + .03 * r.nextDouble());
            if (r.nextBoolean()) motes.add(LifeBeamParts.leaf(r, end, push, tick, 40 + r.nextInt(30)));
            else motes.add(LifeBeamParts.light(r, end, push.scale(1.3), tick, 25 + r.nextInt(20)));
        }
    }

    private static Vec3 around(RandomSource r, Vec3 from, Vec3 dir, Vec3[] b, double d, double spread) {
        double th = r.nextDouble() * 6.28, rr = spread * Math.sqrt(r.nextDouble());
        return from.add(dir.scale(d)).add(b[0].scale(Math.cos(th) * rr)).add(b[1].scale(Math.sin(th) * rr));
    }
}
