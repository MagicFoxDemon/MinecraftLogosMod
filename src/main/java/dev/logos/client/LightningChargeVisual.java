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
 * flipbook) textures, drawn after the core. A colossal charge wraps the caster in its own aura in place of the
 * shared pillar ({@link ChargeAuraEvoke}, after NS_Charge_EvokeEnergy_Root): the palette leaning further to cyan,
 * its sine-line strips jagging like arcs and its ghost hair the lightning bolt; its arcs then crackle out round
 * the pillar's kept outer cylinder instead of on the body, bigger, brighter and far more often, so the caster stays
 * clear inside them.
 */
final class LightningChargeVisual extends ChargeCore {
    /** Radius of the held ball by tier, as the core draws it ({@link ChargeHead}). */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};
    private static final int STROKE = 0x4A9BFF, GLOW = 0xBCEBFF;
    /** The aura's flame: the palette's blue taken halfway to cyan, as the reference's cyan-blue. */
    private static final int CYAN = 0x38E8FF;

    /**
     * One arc: round the body ({@code ball} false, offset from the feet; on a colossal charge out round the pillar's
     * kept cylinder, {@code outer}) or off the ball ({@code ball} true, unit direction).
     */
    private record Arc(double born, int life, boolean ball, boolean outer, Vec3 at, float size, float roll, int frame, int seed) {}

    private final List<Arc> arcs = new ArrayList<>();
    private float owedBody;
    private double nextBall;
    private final ChargeAuraEvoke aura;

    LightningChargeVisual() {
        super(ChargeStyle.colours(0xF4FBFF, 0x62A0FF, 0x2F4ED6).withOwnAura());
        aura = new ChargeAuraEvoke(new ChargeAuraEvoke.Look(style.light(), ChargeParts.lerpColor(style.main(), CYAN, .5f), style.deep(),
                ChargeAuraEvokeParts.Accent.ELECTRIC, ChargeAuraEvokeParts.sprite("charge_aura/basic_dot", true), false));
    }

    @Override
    public boolean tick(Level level, LiveCharge charge) {
        boolean core = super.tick(level, charge);
        RandomSource r = level.getRandom();
        if (charge.tier() == 3) core |= aura.tick(r, charge);
        double now = charge.age(0);
        arcs.removeIf(a -> now - a.born > a.life + 1);
        int tier = charge.tier();
        LiveCharge.Phase phase = charge.phase();
        if (tier >= 2 && !phase.over()) {
            float p = phase == LiveCharge.Phase.HELD ? 1 : charge.progress(0);
            // Round the body: a stray arc now and then, a little more often as it fills. A colossal charge throws
            // them out round the pillar's cylinder instead, many more of them.
            float rate = (tier == 3 ? 1.6f : .1f) * (.3f + .7f * p);
            for (owedBody += rate; owedBody >= 1; owedBody--) {
                if (tier == 3 && count(false) < 18) outerArc(r, now, charge.height());
                else if (tier == 2 && count(false) < 2) bodyArc(r, now, charge.height());
            }
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
        arcs.add(new Arc(now + r.nextFloat(), 3 + r.nextInt(3), false, false, at, (.22f + .16f * r.nextFloat()) * scale,
                r.nextFloat() * Mth.TWO_PI, r.nextInt(4), r.nextInt(1 << 16)));
    }

    /** Colossal: an arc somewhere round the pillar's kept cylinder, anywhere up its height, about twice the size. */
    private void outerArc(RandomSource r, double now, float height) {
        float scale = height / 1.8f;
        double angle = r.nextDouble() * Math.PI * 2, radius = ChargeAuraEvokeParts.PILLAR_RADIUS * (.9 + .2 * r.nextDouble()) * scale;
        Vec3 at = new Vec3(Math.cos(angle) * radius, (.2 + (ChargeAuraEvokeParts.PILLAR_HEIGHT - .5) * r.nextDouble()) * scale,
                Math.sin(angle) * radius);
        arcs.add(new Arc(now + r.nextFloat(), 4 + r.nextInt(4), false, true, at, (.5f + .3f * r.nextFloat()) * scale,
                r.nextFloat() * Mth.TWO_PI, r.nextInt(4), r.nextInt(1 << 16)));
    }

    private void ballArc(RandomSource r, double now) {
        arcs.add(new Arc(now, 5, true, false, ChargeParts.randomUnit(r), .9f + .4f * r.nextFloat(), r.nextFloat() * Mth.TWO_PI, 0, r.nextInt(1 << 16)));
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        if (charge.tier() == 3) {
            pose.pushPose();
            pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
            aura.submit(charge, ChargeParts.View.of(camera), partialTick, pose, out);
            pose.popPose();
        }
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
                // The caster's own arcs stay out of a first-person view (those out on the cylinder off its middle too).
                float alpha = ChargeParts.fade(view.cam(), c, .9, 1.6) * (.5f + .5f * fadeOut)
                        * (a.outer && charge.firstPerson() ? ChargeAuraEvokeParts.offCentre(view, c) : 1);
                if (alpha <= .01f) continue;
                float roll = a.roll + tick * .4f;
                // Out on the cylinder they burn brighter, a white-hot stroke over the blue.
                float stroke = a.outer ? 1 : .85f, glow = a.outer ? 1 : .7f;
                out.submitCustomGeometry(pose, ChargeParts.blend("lightning_impact/arcs"), (p, buf) ->
                        ChargeParts.frame(p, buf, view, c, a.size, roll, frame, 2, 2, argb(alpha * stroke, STROKE)));
                out.submitCustomGeometry(pose, ChargeParts.add("lightning_impact/arcs"), (p, buf) ->
                        ChargeParts.frame(p, buf, view, c, a.size * 1.04f, roll, frame, 2, 2, argb(alpha * glow, GLOW)));
                if (a.outer) out.submitCustomGeometry(pose, ChargeParts.add("lightning_impact/arcs"), (p, buf) ->
                        ChargeParts.frame(p, buf, view, c, a.size * .98f, roll, frame, 2, 2, argb(alpha * .8f, 0xFFFFFF)));
            }
        }
        pose.popPose();
    }

    @Override
    public void end() {
        super.end();
        aura.end();
    }
}
