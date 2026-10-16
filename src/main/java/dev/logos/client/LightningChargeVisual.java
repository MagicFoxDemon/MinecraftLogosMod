package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The lightning charge: the shared charge in a slightly cooler, cyan-leaning blue, and for grand and colossal
 * charges two light touches after it (like Megumin charging, crackling with blue-white arcs). A few small arcs
 * flash round the caster's body, each lasting a few ticks and blinking on and off, sparse rather than a
 * constant cage; and once full, the held ball now and then throws one or two tiny arcs off its surface. Arcs
 * use the existing {@code lightning_impact/arcs} (2x2 strokes) and {@code lightning_ray/electric} (4x2
 * flipbook) textures, drawn after the core.
 */
final class LightningChargeVisual extends ChargeCore {
    /** Radius of the held ball by tier, as the core draws it ({@link ChargeHead}). */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};
    private static final int STROKE = 0x4A9BFF, GLOW = 0xBCEBFF;

    /** One arc: round the body ({@code ball} false, offset from the feet) or off the ball ({@code ball} true, unit direction). */
    private record Arc(double born, int life, boolean ball, Vec3 at, float size, float roll, int frame, int seed) {}

    private final List<Arc> arcs = new ArrayList<>();
    private float owedBody;
    private double nextBall;

    LightningChargeVisual() {
        super(ChargeStyle.colours(0xF4FBFF, 0x62A0FF, 0x2F4ED6));
    }

    @Override
    public boolean tick(Level level, LiveCharge charge) {
        boolean core = super.tick(level, charge);
        RandomSource r = level.getRandom();
        double now = charge.age(0);
        arcs.removeIf(a -> now - a.born > a.life + 1);
        int tier = charge.tier();
        LiveCharge.Phase phase = charge.phase();
        if (tier >= 2 && !phase.over()) {
            float p = phase == LiveCharge.Phase.HELD ? 1 : charge.progress(0);
            // Round the body: a stray arc now and then, a little more often as it fills and on a colossal charge.
            float rate = (tier == 3 ? .22f : .1f) * (.3f + .7f * p);
            for (owedBody += rate; owedBody >= 1; owedBody--) if (count(false) < (tier == 3 ? 4 : 2)) bodyArc(r, now, charge.height());
            // Off the held ball: one or two tiny arcs every so often.
            if (phase == LiveCharge.Phase.HELD) {
                if (charge.entered()) nextBall = now + 3;
                if (now >= nextBall) {
                    int n = r.nextInt(3) == 0 ? 2 : 1;
                    for (int i = 0; i < n; i++) ballArc(r, now + i * 2);
                    nextBall = now + (tier == 3 ? 7 : 11) + r.nextInt(8);
                }
            }
        }
        return core || !arcs.isEmpty();
    }

    private int count(boolean ball) {
        int n = 0;
        for (Arc a : arcs) if (a.ball == ball) n++;
        return n;
    }

    private void bodyArc(RandomSource r, double now, float height) {
        float scale = height / 1.8f;
        double angle = r.nextDouble() * Math.PI * 2, radius = (.35 + .35 * r.nextDouble()) * scale;
        Vec3 at = new Vec3(Math.cos(angle) * radius, (.15 + .75 * r.nextDouble()) * height, Math.sin(angle) * radius);
        arcs.add(new Arc(now + r.nextFloat(), 3 + r.nextInt(3), false, at, (.22f + .16f * r.nextFloat()) * scale,
                r.nextFloat() * Mth.TWO_PI, r.nextInt(4), r.nextInt(1 << 16)));
    }

    private void ballArc(RandomSource r, double now) {
        arcs.add(new Arc(now, 5, true, ChargeParts.randomUnit(r), .9f + .4f * r.nextFloat(), r.nextFloat() * Mth.TWO_PI, 0, r.nextInt(1 << 16)));
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        if (arcs.isEmpty()) return;
        ChargeParts.View view = ChargeParts.View.of(camera);
        double now = charge.age(partialTick);
        Vec3 feet = charge.feet(partialTick), tip = charge.tip(partialTick);
        float ball = BALL[charge.tier()] * charge.near();
        if (charge.firstPerson()) ball = Math.min(ball, (float) view.cam().distanceTo(tip) * .3f);
        float ballRadius = ball;
        // Off the ball only while it is held; let go or dropped, they stop with it.
        boolean held = charge.phase() == LiveCharge.Phase.HELD;
        float clear = ChargeParts.fade(view.cam(), tip, .15, .45);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (Arc a : arcs) {
            double age = now - a.born;
            if (age < 0 || age > a.life) continue;
            float fadeOut = 1 - (float) (age / a.life);
            if (a.ball) {
                if (!held) continue;
                int frame = Mth.clamp((int) (age / a.life * 8), 0, 7);
                Vec3 c = tip.add(a.at.scale(ballRadius * 1.05f));
                float size = ballRadius * a.size, alpha = clear * (.6f + .4f * fadeOut);
                out.submitCustomGeometry(pose, ChargeParts.add("lightning_ray/electric"), (p, buf) ->
                        ChargeParts.frame(p, buf, view, c, size, a.roll, frame, 4, 2, argb(alpha, GLOW)));
            } else {
                // Blinking: dark on some ticks, and the stroke jumps to another shape now and then.
                int tick = (int) Math.floor(age);
                if ((tick * 7 + a.seed) % 4 == 0 && age > 1) continue;
                int frame = (a.frame + (tick / 2) * (1 + a.seed % 3)) % 4;
                Vec3 c = feet.add(a.at);
                // The caster's own arcs stay out of a first-person view.
                float alpha = ChargeParts.fade(view.cam(), c, .9, 1.6) * (.5f + .5f * fadeOut);
                if (alpha <= .01f) continue;
                float roll = a.roll + tick * .4f;
                out.submitCustomGeometry(pose, ChargeParts.blend("lightning_impact/arcs"), (p, buf) ->
                        ChargeParts.frame(p, buf, view, c, a.size, roll, frame, 2, 2, argb(alpha * .85f, STROKE)));
                out.submitCustomGeometry(pose, ChargeParts.add("lightning_impact/arcs"), (p, buf) ->
                        ChargeParts.frame(p, buf, view, c, a.size * 1.04f, roll, frame, 2, 2, argb(alpha * .7f, GLOW)));
            }
        }
        pose.popPose();
    }
}
