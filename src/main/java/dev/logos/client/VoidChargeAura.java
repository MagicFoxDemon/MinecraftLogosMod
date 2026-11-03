package dev.logos.client;

import static dev.logos.client.ChargeAuraEvokeParts.curve;
import static dev.logos.client.ChargeAuraEvokeParts.param;
import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeAuraEvokeParts.Accent;
import dev.logos.client.ChargeAuraEvokeParts.Ribbon;
import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * What the colossal void aura adds to the shared one ({@link ChargeAuraEvoke}): the particles of NS_Charge_DarkSummon
 * that fly in to the caster (broken down in {@code art/charge/gather/evoke/notes.md}), as open void.
 * <ul>
 * <li>Mother: ten a second (the reference's fifteen, thinned: solid void weighs more than smoke), born anywhere
 * in a wide ellipsoid round the caster's middle (2.5 m across, 1.7 m up and down), pulled in to the middle over
 * 0.75 s, swirling a little early on: wisps of open void, solid, eaten away at the edges into violet;</li>
 * <li>Fountain and Double: a violet ghost-hair trail behind each; Fountain001: a narrower trail of the same void;</li>
 * <li>NE_RadialSpawn: dark specks shed along the way, drifting off.</li>
 * </ul>
 * The void ({@code void_charge_aura_wisp.fsh}) is the screen-space starfield over a dark nebula, as in every void
 * effect, and writes depth. It also gives the shared aura its smoke ({@link #WISP}). Only while charging or held;
 * whatever is in flight then finishes. Close to the camera (in first person, the caster's own middle) the void
 * eats itself away.
 */
final class VoidChargeAura {
    /** Solid open void on a quad, eaten away by its vertex alpha. */
    static final RenderType WISP = wisp(false);
    /** The same along a trail. */
    private static final RenderType TRAIL = wisp(true);
    /** Mother's TravelTime 0.75 s, and the ticks its trail takes to fold in after it. */
    private static final int TRAVEL = 15, TAIL = 8;

    private static RenderType wisp(boolean ribbon) {
        String key = ribbon ? "trail" : "wisp";
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(ChargeAuraEvokeParts.id("pipeline/void_charge_aura_" + key))
                .withVertexShader(ChargeAuraEvokeParts.id("core/charge_aura_evoke"))
                .withFragmentShader(ChargeAuraEvokeParts.id("core/void_charge_aura_wisp")).withCull(false)
                .withSampler("NoiseTex").withSampler("StarTex").withSampler("AuraTex")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        if (ribbon) pipeline.withShaderDefine("RIBBON");
        return RenderType.create("logos_void_charge_aura_" + key, RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", ChargeAuraEvokeParts.vfx("charge_aura/noise_cloudy"))
                .withTexture("NoiseTex", ChargeAuraEvokeParts.vfx("charge_aura/noise_fire04"))
                .withTexture("StarTex", ChargeAuraEvokeParts.PORTAL)
                .withTexture("AuraTex", ChargeAuraEvokeParts.vfx("void_beam/aura")).sortOnUpload().createRenderSetup());
    }

    /** A Mother particle: where it starts, relative to the caster's middle, for a caster 1.8 tall. */
    private record Mover(double born, Vec3 start, float size, float swirl, float rnd) {
        /** Where it is {@code age} ticks on: slow to leave, quicker and quicker in, in the middle at TRAVEL. */
        Vec3 at(double age) {
            float k = Mth.clamp((float) (age / TRAVEL), 0, 1);
            float in = k * k * (2.2f - 1.2f * k);
            // The curl noise of its first half: a turn round the middle, gone by the time it lands.
            double turn = swirl * Math.sin(k * Math.PI) * .9;
            Vec3 s = start.scale(1 - in);
            double c = Math.cos(turn), sn = Math.sin(turn);
            return new Vec3(s.x * c - s.z * sn, s.y + Math.sin(k * Math.PI) * .12 * swirl, s.x * sn + s.z * c);
        }
    }

    /** A speck shed by a mover, drifting off in caster-relative units. */
    private record Speck(double born, float life, Vec3 at, Vec3 drift, float size, float rnd) {}

    private final List<Mover> movers = new ArrayList<>();
    private final List<Speck> specks = new ArrayList<>();
    private float owed;

    /** One client tick of a colossal charge. False once everything in flight has landed. */
    boolean tick(RandomSource r, LiveCharge c) {
        double now = c.age(0);
        movers.removeIf(m -> now - m.born > TRAVEL + TAIL);
        specks.removeIf(s -> now - s.born > s.life);
        if (!c.phase().over()) {
            for (owed += .5f; owed >= 1; owed--) if (movers.size() < 11) spawn(r, now);
        }
        // NE_RadialSpawn: specks shed along each mover's way (here a few a tick between them all).
        for (Mover m : movers) {
            double age = now - m.born;
            if (age < 0 || age > TRAVEL * .9 || specks.size() >= 80 || r.nextFloat() > .4f) continue;
            specks.add(new Speck(now, 8 + r.nextInt(3), m.at(age), ChargeParts.randomUnit(r).scale(.015 + .02 * r.nextFloat()),
                    .6f + .4f * r.nextFloat(), r.nextFloat()));
        }
        return !movers.isEmpty() || !specks.isEmpty();
    }

    /** Drops whatever is still in flight (the charge is gone). */
    void end() {
        movers.clear();
        specks.clear();
    }

    private void spawn(RandomSource r, double now) {
        // Sphere radius 150 x size x ExtentRadius cm, scaled (1.5, 1.5, 1), anywhere inside it.
        Vec3 d = ChargeParts.randomUnit(r).scale(Math.cbrt(.25 + .75 * r.nextDouble()) * 150 * 1.125 / 100);
        // Not under the ground: the middle is 0.9 up, so no lower than 0.15 above the feet once scaled.
        Vec3 start = new Vec3(d.x * 1.5, Math.max(d.y, -.75 / ChargeAuraEvoke.AURA_SCALE), d.z * 1.5);
        // (6-12) x 5 x size cm across.
        movers.add(new Mover(now + r.nextFloat(), start, (6 + 6 * r.nextFloat()) * 5 * 1.5f / 100 * .85f, (r.nextFloat() - .5f) * 2.4f, r.nextFloat()));
    }

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out, int violet) {
        if (movers.isEmpty() && specks.isEmpty()) return;
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        float s = aura == null ? 0 : Mth.clamp(aura.strength(), 0, 1);
        if (s <= 0) return;
        double now = c.age(pt);
        float scale = c.height() / 1.8f * ChargeAuraEvoke.AURA_SCALE;
        Vec3 middle = c.feet(pt).add(0, c.height() * .5, 0);
        int glow = lerpColor(violet, 0xD8B0FF, .25f);
        // In first person they fly in round the camera itself: gone well before they could cover the view.
        float clear = c.firstPerson() ? 1.4f * ChargeAuraEvoke.AURA_SCALE : 0;
        // The wisps: velocity-aligned, growing in fast and shrinking as they land.
        out.submitCustomGeometry(stack, WISP, (pose, buf) -> {
            for (Mover m : movers) {
                double age = now - m.born;
                if (age < 0 || age > TRAVEL) continue;
                float k = (float) (age / TRAVEL);
                Vec3 at = world(middle, m.at(age), scale);
                Vec3 dir = at.subtract(world(middle, m.at(Math.max(0, age - 1)), scale));
                float size = m.size * scale * curve(k, 0, 0, .14f, .98f, 1, 0) * .5f;
                float left = s * curve(k, 0, .7f, .2f, 1, .85f, 1, 1, .3f) * ChargeParts.fade(view.cam(), at, size + .5 + clear, size + 1.8 + clear)
                        * offCentre(view, at, clear);
                if (size > .01f && left > .05f) ChargeAuraEvokeParts.aligned(pose, buf, view, at, dir, size, size * 1.15f, .4f,
                        argb(left, glow), param(m.rnd * 9), 0);
            }
        });
        // The trails behind them: a narrow one of the same void and a wider violet ghost hair over it.
        trails(stack, out, view, middle, now, scale, s, clear, TRAIL, .22f, violet, true);
        trails(stack, out, view, middle, now, scale, s, clear, ChargeAuraEvokeParts.ribbon(Ribbon.HAIR, Accent.STARRY), .55f, glow, false);
        if (!specks.isEmpty()) out.submitCustomGeometry(stack, WISP, (pose, buf) -> {
            for (Speck p : specks) {
                float k = (float) ((now - p.born) / p.life);
                if (k <= 0 || k >= 1) continue;
                Vec3 at = world(middle, p.at.add(p.drift.scale(now - p.born)), scale);
                float size = (.05f + .04f * p.size) * scale * Mth.sin(k * Mth.PI);
                float left = s * ChargeParts.fade(view.cam(), at, .6 + clear, 1.4 + clear) * offCentre(view, at, clear);
                if (size > .008f && left > .05f) ChargeAuraEvokeParts.sprite(pose, buf, view, at, size, size, p.rnd * 6.3f, argb(left, violet),
                        param(p.rnd * 9), 0);
            }
        });
    }

    private void trails(PoseStack stack, SubmitNodeCollector out, View view, Vec3 middle, double now, float scale, float s, float clear,
            RenderType type, float width, int rgb, boolean solid) {
        out.submitCustomGeometry(stack, type, (pose, buf) -> {
            int n = 9;
            Vec3[] pts = new Vec3[n];
            float[] half = new float[n], u = new float[n];
            int[] colors = new int[n], a = new int[n], b = new int[n];
            for (Mover m : movers) {
                double age = now - m.born;
                if (age < 0) continue;
                Vec3 head = world(middle, m.at(age), scale);
                float near = ChargeParts.fade(view.cam(), head, .8 + clear, 2.2 + clear) * offCentre(view, head, clear);
                for (int i = 0; i < n; i++) {
                    double back = Math.max(0, age - i * 1.1);
                    pts[i] = world(middle, m.at(back), scale);
                    float j = i / (n - 1f);
                    u[i] = j;
                    // The trail's width: thin at its head, widest a little behind, folding in once it has landed.
                    float landed = 1 - smooth(TRAVEL, TRAVEL + TAIL, (float) age);
                    half[i] = width * m.size / .675f * scale * curve(j, 0, .35f, .25f, 1, 1, .2f) * landed * .5f;
                    float k = (float) Math.min(1, back / TRAVEL);
                    float shows = s * near * landed * curve(k, 0, .2f, .3f, 1, 1, .8f);
                    colors[i] = solid ? argb(shows * (1 - .5f * j), rgb) : argb(shows * .55f * (1 - j), rgb);
                    a[i] = solid ? param(m.rnd * 9) : 0;
                    b[i] = param(m.rnd);
                }
                ChargeAuraEvokeParts.ribbon(pose, buf, view, pts, half, colors, u, a, b, n, null);
            }
        });
    }

    /**
     * In first person ({@code clear} above 0) a solid wisp crossing the middle of the view, a few blocks out, would
     * blot it out: faded there, whatever its distance up to seven blocks.
     */
    private static float offCentre(View view, Vec3 at, float clear) {
        if (clear <= 0) return 1;
        Vec3 d = at.subtract(view.cam());
        double len = d.length();
        if (len < 1e-4) return 0;
        double ahead = d.dot(view.up().cross(view.right())) / len;
        return 1 - ChargeParts.smooth(.8f, .93f, (float) ahead) * (1 - ChargeParts.smooth(5f, 7f, (float) len));
    }

    private static Vec3 world(Vec3 middle, Vec3 local, float scale) {
        return middle.add(local.scale(scale));
    }
}
