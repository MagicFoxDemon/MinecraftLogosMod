package dev.logos.client;

import static dev.logos.client.LifeBeamParts.argb;
import static dev.logos.client.LifeBeamParts.band;
import static dev.logos.client.LifeBeamParts.helix;
import static dev.logos.client.LifeBeamParts.lerpColor;
import static dev.logos.client.LifeBeamParts.quad;
import static dev.logos.client.LifeBeamParts.smooth;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * What a life beam leaves when it ends, in the manner of the life ray's afterglow: the gold core goes out at
 * once, the vine trail narrows and withers to a thin green line that fades over about six seconds, the
 * twisting vines loosen and let go, and leaves fall and motes of light rise from where the beam was (the
 * beam's own leaves still in the air drift on). All gone in under seven seconds.
 */
final class LifeBeamWake implements RayVisual {
    /** Ticks until the withered line is gone; leaves and motes last at most about 135. */
    private static final int LINE = 120;
    private static final double CLEAR = 2.2;

    private Vec3 from = Vec3.ZERO, dir = new Vec3(0, 0, 1);
    private double length;
    private float s = 1;
    /** Game tick it started: its clock is the level's, so a frozen tick holds it still for a sample. */
    private long born;
    private double lastDeath;
    private final List<LifeBeamParts.Mote> motes = new ArrayList<>();

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        Vec3 d = to.subtract(from);
        length = d.length();
        dir = length > 1e-4 ? d.scale(1 / length) : new Vec3(0, 0, 1);
        born = level.getGameTime();
        LifeBeamVisual.Last last = LifeBeamVisual.last(from, to);
        if (last != null) {
            s = Mth.clamp(last.radius() / .5f, .4f, 3);
            motes.addAll(last.motes());
        }
        RandomSource r = level.getRandom();
        if (length > CLEAR + .5) {
            double span = length - CLEAR;
            Vec3[] b = LifeBeamParts.basis(dir);
            int leaves = Mth.clamp((int) (span * 1.8), 6, 96);
            for (int i = 0; i < leaves; i++) motes.add(LifeBeamParts.leaf(r, around(r, b, CLEAR + span * r.nextDouble(), .4 * s),
                    Vec3.ZERO, born + 1 + r.nextInt(22), 80 + r.nextInt(38)));
            int lights = Mth.clamp((int) (span * 2), 8, 120);
            for (int i = 0; i < lights; i++) motes.add(LifeBeamParts.light(r, around(r, b, CLEAR + span * r.nextDouble(), .35 * s),
                    Vec3.ZERO, born + 1 + r.nextInt(40), 60 + r.nextInt(48)));
        }
        for (LifeBeamParts.Mote m : motes) lastDeath = Math.max(lastDeath, m.death() - born);
    }

    private void twin(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Vec3 cam,
            float rad, float w, double turn, int color) {
        helix(pose, buf, cam, from, dir, length, rad, w, 2.6, turn, (float) (-turn * 2), 1.4f, color, 1.2, 4.5);
        helix(pose, buf, cam, from, dir, length, rad, w, 2.6, turn + Math.PI, (float) (-turn * 2 + .5), 1.4f, color, 1.2, 4.5);
    }

    private Vec3 around(RandomSource r, Vec3[] b, double d, double spread) {
        double th = r.nextDouble() * 6.28, rr = spread * Math.sqrt(r.nextDouble());
        return from.add(dir.scale(d)).add(b[0].scale(Math.cos(th) * rr)).add(b[1].scale(Math.sin(th) * rr));
    }

    @Override
    public boolean tick(Level level) {
        return level.getGameTime() - born <= Math.max(lastDeath, LINE) + 2;
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack stack, SubmitNodeCollector out) {
        if (length < .05) return;
        double a = level.getGameTime() - born + partialTick, t = born + a;
        Vec3 cam = camera.pos;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        stack.pushPose();
        stack.translate(-cam.x, -cam.y, -cam.z);

        // The gold core going out in about half a second.
        if (a < 12) {
            float k = (float) (1 - a / 12);
            out.submitCustomGeometry(stack, LifeBeamParts.CORE, (pose, buf) ->
                    band(pose, buf, cam, from, dir, length, .42f * s * k, 0, 1, argb(.85f * k * k, 0xFFC64A), 1.2, 1.2, 4.5));
        }
        // The vine trail withering: narrowing, losing its gold, fading over three and a half seconds.
        if (a < 72) {
            float k = (float) smooth(0, 72, a);
            float w = .95f * s * (1 - .7f * k);
            float alpha = (1 - k) * (1 - k * .3f);
            int c = lerpColor(0x9BFF55, 0x4FC84A, k * 1.5f);
            float scroll = (float) (-born * .3 - a * .3 * (1 - k));
            out.submitCustomGeometry(stack, LifeBeamParts.VINES_TINT, (pose, buf) ->
                    band(pose, buf, cam, from, dir, length, w, scroll, 3, argb(.55f * alpha, 0x2E9E2C), 1.5, 1, 4));
            out.submitCustomGeometry(stack, LifeBeamParts.VINES, (pose, buf) ->
                    band(pose, buf, cam, from, dir, length, w, scroll, 3, argb(.75f * alpha, c), 1.5, 1, 4));
        }
        // The twisting vines loosening, swinging wide as they let go.
        if (a < 40) {
            float k = (float) smooth(0, 40, a);
            double turn = born * .09 + a * .09 * (1 - k);
            float rad = .36f * s * (1 + .6f * k), w = .2f * s * (1 - .5f * k);
            out.submitCustomGeometry(stack, LifeBeamParts.VINES_TINT, (pose, buf) -> twin(pose, buf, cam, rad, w, turn,
                    argb(.65f * (1 - k), 0x3FA834)));
            out.submitCustomGeometry(stack, LifeBeamParts.VINES, (pose, buf) -> twin(pose, buf, cam, rad, w, turn,
                    argb(.45f * (1 - k), lerpColor(0x9FE84A, 0x5FC84A, k))));
        }
        // What stays: a thin green line with a faint glow round it, fading slowly (the life ray's afterglow).
        if (a < LINE) {
            // Holds most of its strength for two seconds, then fades out slowly.
            float alpha = (float) (Mth.clamp(a / 6, 0, 1) * (1 - smooth(30, LINE, a)));
            float w = .16f * s * (1 - .55f * (float) smooth(15, LINE, a));
            float pulse = 1 + .12f * (float) Math.sin(t * .35);
            out.submitCustomGeometry(stack, LifeBeamParts.CORE_TINT, (pose, buf) ->
                    band(pose, buf, cam, from, dir, length, w * 2.2f, 0, 1, argb(.7f * alpha, 0x2BB33A), 1.2, .6, 3));
            out.submitCustomGeometry(stack, LifeBeamParts.CORE, (pose, buf) ->
                    band(pose, buf, cam, from, dir, length, w, 0, 1, argb(.85f * alpha * pulse, 0x8DFF6E), 1.2, .6, 3));
            float scroll = (float) (-t * .04);
            out.submitCustomGeometry(stack, LifeBeamParts.SHEATH_TINT, (pose, buf) ->
                    band(pose, buf, cam, from, dir, length, .75f * s, scroll, 6, argb(.3f * alpha, 0x3DB04E), 2.5, .8, 4));
        }
        // The bloom at the end closing.
        if (a < 20) {
            float k = (float) (1 - a / 20);
            Vec3 end = from.add(dir.scale(Math.max(0, length - .08)));
            float near = LifeBeamParts.fade(cam, end, .6, 3);
            out.submitCustomGeometry(stack, LifeBeamParts.GLOW, (pose, buf) ->
                    quad(pose, buf, end, right, up, 1.1f * s * (.5f + .5f * k), 0, 0, 1, 1, argb(.7f * k * near, 0x82FFA0)));
        }

        LifeBeamParts.motes(stack, out, motes, t, cam, right, up);
        stack.popPose();
    }
}
