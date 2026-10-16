package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
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

import dev.logos.client.ChargeParts.View;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The physical field: no colour of its own, only white and a cold steel blue, pure force and tension. Over the
 * shared field ({@link FieldCore}):
 * <ul>
 * <li>hard-lined circles (Unreal T_MagicCircle_M_27, _07, _28) whose lines hold squeezed air: fine white wind
 * streaks race round them over steel blue, each line edged with a thin hard white line
 * ({@code physical_field_line.fsh} flowing {@code physical_ray/wind});</li>
 * <li>a pressure wall at the edge: see-through refraction ripples rising through it, smeared by wind running
 * round it, a hard white line at its foot ({@code physical_field_wall.fsh}); on each pulse it flares and a
 * shock front is thrown outwards off it, as if the wave had pushed the air out;</li>
 * <li>geometric runes (force arrows, pressure bars, wedges);</li>
 * <li>a few thin white gusts circling low inside the field.</li>
 * </ul>
 */
final class PhysicalFieldVisual extends FieldCore {
    /** The shared field's pulse wave: ticks from the centre to the edge. */
    private static final int WAVE = 14;
    /** Ticks the shock front thrown off the wall lives. */
    private static final int SHOCK = 12;

    private static final RenderType WALL = wall();

    private final List<Long> pulses = new ArrayList<>();

    PhysicalFieldVisual() {
        super(FieldStyle.of(Element.PHYSICAL).withColours(0xFFFFFF, 0xC4D2EC, 0x6E84AA)
                .withCircles("physical_field/circle_27", "physical_field/circle_07", "charge/circle_28", "charge/circle_16")
                .withCurtain("physical_ray/wind").withRunes("physical_field/runes", 4, 4)
                .withLines("core/physical_field_line", "physical_ray/wind"));
    }

    @Override
    public boolean tick(Level level, LiveField f) {
        boolean keep = super.tick(level, f);
        long now = ClientCharges.ticks();
        pulses.addAll(f.newPulses());
        pulses.removeIf(t -> now - t > WAVE + SHOCK + 4);
        return keep;
    }

    @Override
    public void submit(Level level, LiveField f, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        View view = View.of(camera);
        Vec3 floor = f.floor(pt);
        float R = f.radius();
        if (view.cam().distanceTo(floor) > 96 + R) return;
        float open = R * opened(f, pt), end = f.sinceEnd(pt);
        float shown = smooth(0, 3, f.age(pt) + (f.phase() == LiveField.Phase.ENDED ? 3 : 0));
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        // Under everything, a faint steel-blue shade on the ground: the white lines stay readable on bright ground by day.
        float shade = .6f * shown * (1 - smooth(0, 14, end));
        if (open > .05f && shade > .01f) {
            pose.pushPose();
            pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
            out.submitCustomGeometry(pose, ChargeParts.blend("heal/glow"), (p, buf) ->
                    ChargeParts.flat(p, buf, floor.add(0, .015, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), open * 1.2f, 0,
                            argb(shade, 0x2E3C58)));
            pose.popPose();
        }
        super.submit(level, f, camera, pt, pose, out);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        wall(pose, out, floor, open, now, end, shown);
        gusts(pose, out, view, floor, open, now, end, shown, f.cameraInside(view.cam(), pt));
        pose.popPose();
    }

    /** How hard the wall is flaring: the pulse wave reaching the edge, sharp on the way up, slower down. */
    private float flare(double now) {
        float flare = 0;
        for (long t : pulses) {
            double k = now - t - WAVE * .8;
            flare = Math.max(flare, (float) Math.exp(-k * k / (k < 0 ? 6 : 20)));
        }
        return flare;
    }

    private void wall(PoseStack pose, SubmitNodeCollector out, Vec3 floor, float R, double now, float end, float shown) {
        if (R <= .05f) return;
        float flare = flare(now);
        float h = Mth.clamp(.6f + .12f * R, .9f, 2.2f) * (1 - smooth(0, 8, end)) * (1 + .15f * flare);
        if (h <= .01f) return;
        float a = shown * (.14f + .5f * flare);
        int segments = Mth.clamp((int) (R * 10), 24, 120);
        float tiles = Math.max(1, Math.round(R * (float) (Math.PI * 2) / 3f));
        // Pushed out a little by the pulse.
        float r = R * (1 + .025f * flare);
        out.submitCustomGeometry(pose, WALL, (p, buf) ->
                ChargeParts.cylinder(p, buf, floor, r, h, segments, 0, tiles, 0, 1, argb(a, style.main()), argb(0, style.main())));
        // The shock fronts thrown outwards off it: lower, fading as they run out.
        for (long t : pulses) {
            float k = (float) ((now - t - WAVE * .8) / SHOCK);
            if (k <= 0 || k >= 1) continue;
            float e = 1 - (1 - k) * (1 - k);
            float rs = R * (1.02f + .28f * e), hs = h * (.85f - .45f * k), as = shown * .75f * (1 - k) * (1 - k);
            out.submitCustomGeometry(pose, WALL, (p, buf) ->
                    ChargeParts.cylinder(p, buf, floor, rs, hs, segments, .37f, tiles, .3f, 1, argb(as, style.light()), argb(0, style.main())));
        }
    }

    /** A few thin white gusts circling low inside the field, each a short arc that comes and goes. */
    private void gusts(PoseStack pose, SubmitNodeCollector out, View view, Vec3 floor, float R, double now, float end, float shown,
            boolean inside) {
        if (R <= .6f) return;
        int n = Mth.clamp(Math.round(1 + R * .6f), 2, 8);
        int points = 12;
        float leave = 1 - smooth(0, 8, end);
        if (shown * leave <= .01f) return;
        out.submitCustomGeometry(pose, ChargeParts.add("force/streak"), (p, buf) -> {
            Vec3[] at = new Vec3[points];
            float[] half = new float[points], u = new float[points];
            int[] colors = new int[points];
            for (int i = 0; i < n; i++) {
                double h1 = hash(i * 11 + 3), h2 = hash(i * 17 + 9), h3 = hash(i * 5 + 21);
                // Each gust lives one cycle, then comes back somewhere else.
                double period = 26 + 14 * h3;
                double cycle = (now + h1 * period) / period;
                int round = (int) Math.floor(cycle);
                float k = (float) (cycle - round);
                float life = (float) Math.sin(Math.PI * k);
                double rr = R * (.35 + .55 * hash(i * 31 + round * 7));
                double y = .12 + .45 * hash(i * 19 + round * 3);
                double dir = (i & 1) == 0 ? 1 : -1;
                double head = Math.PI * 2 * hash(i * 23 + round * 13) + dir * k * 2.2;
                double span = (.5 + .5 * h2) * Math.min(1.1, 3.5 / rr) * (.4 + .6 * life);
                for (int j = 0; j < points; j++) {
                    float s = j / (float) (points - 1);
                    double phi = head - dir * span * (1 - s);
                    at[j] = floor.add(Math.cos(phi) * rr, y + .04 * Math.sin(phi * 3 + now * .2), Math.sin(phi) * rr);
                    float taper = (float) Math.sin(Math.PI * s);
                    half[j] = .025f + .02f * taper;
                    u[j] = s;
                    float a = shown * leave * life * taper * .85f * fade(view.cam(), at[j], inside ? .8 : .4, inside ? 2.2 : 1.2);
                    colors[j] = argb(a, lerpColor(style.main(), style.light(), s));
                }
                ChargeParts.ribbon(p, buf, view, at, half, colors, u, points);
            }
        });
    }

    private static RenderType wall() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/physical_field_wall")).withFragmentShader(id("core/physical_field_wall")).withCull(false)
                .withSampler("FillTex")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
        return RenderType.create("logos_physical_field_wall", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/physical/trail_noise.png"))
                .withTexture("FillTex", id("textures/vfx/physical_ray/wind.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
