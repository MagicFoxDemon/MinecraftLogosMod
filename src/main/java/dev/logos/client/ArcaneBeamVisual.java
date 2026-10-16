package dev.logos.client;

import static dev.logos.client.ArcaneRayVisual.argb;
import static dev.logos.client.ArcaneRayVisual.mix;
import static dev.logos.client.ArcaneRayVisual.polyline;
import static dev.logos.client.ArcaneRayVisual.smooth;
import static dev.logos.client.ArcaneRayVisual.sprite;
import java.util.Random;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A lasting arcane {@code beam} after Piloto Studio's {@code ArcaneRay}, in the arcane ray's rose-violet: a
 * violet body around a pink core with a white thread, a starry band and a silky veil scrolling away from
 * the caster, twin wisps twisting down it, and runes, four-point glints and motes drifting outward along it
 * while they orbit the line. A small flare sits at the wand head (shrunk and pushed out at the camera so
 * first person stays clear); the end races out with a bright leading spark, and once the beam has reached
 * its length a slowly turning halo with orbiting runes marks where it ends. Everything is custom geometry
 * submitted through {@code out}, on the arcane ray's shader and textures.
 */
final class ArcaneBeamVisual {
    static boolean legacy() { return false; }

    private static final int PINK = 0xE668EF, VIOLET = 0xCA38FF, DEEP = 0xA136EF, LILAC = 0xE6A0FF,
            BLUSH = 0xFBB6F2, HOT = 0xF9DCFF, PERIWINKLE = 0x8C72FC;

    // Same split as the arcane ray: colour layers blend normally (they stay rose-violet at noon), the rest only add light.
    private static final RenderType GLOW = material("glow", "fuzz", false, "ARCANE_RAY_CORE");
    private static final RenderType CORE = material("core", "fuzz", false, "ARCANE_RAY_CORE");
    private static final RenderType STARRY = material("starry", "starry", true, "ARCANE_RAY_BAND");
    private static final RenderType ELECTRIC = material("electric", "electric", true, "ARCANE_RAY_BAND");
    private static final RenderType SILK = material("silk", "silk", false, "ARCANE_RAY_BAND");
    private static final RenderType WIND = material("wind", "wind", true);
    private static final RenderType RUNE = material("rune", "runes", false, "ARCANE_RAY_HOT");
    private static final RenderType GLINT = material("glint", "glint", true, "ARCANE_RAY_HOT");
    private static final RenderType DOT = material("dot", "dot", true, "ARCANE_RAY_HOT");
    private static final RenderType FUZZ = material("fuzz", "fuzz", true);
    private static final RenderType FLARE = material("flare", "flare", true, "ARCANE_RAY_HOT");
    private static final RenderType END_GLOW = material("end_glow", "impact_glow", false);
    private static final RenderType END_HALO = material("end_halo", "impact_halo", true);
    private static final RenderType END_RAYS = material("end_rays", "impact_rays", true);
    private static final RenderType END_CROSS = material("end_cross", "impact_cross", true, "ARCANE_RAY_HOT");

    /** Runes, glints and motes ride a fixed 64-block conveyor so they stay put while the beam reaches out or turns. */
    private static final double PERIOD = 64;
    /** Wisps: one every this many blocks, each this long. */
    private static final double WISP_GAP = 4.5, WISP_LENGTH = 3.2;

    private record Mote(double phase, float speed, float angle, float spin, float radius, float size, float twinkle, int frame) {}

    private final boolean defaultMotes;
    private final Mote[] runes = new Mote[48], glints = new Mote[28], dots = new Mote[90];
    private long born = Long.MIN_VALUE;
    private double arrivedAt = -1;
    private Vec3 from = Vec3.ZERO, dir = new Vec3(0, 0, 1), side = new Vec3(1, 0, 0), lift = new Vec3(0, 1, 0);
    private double length;
    /** Where the beam was last drawn from (the client scene checks it sits at the wand head). */
    Vec3 lastFrom;

    /** {@code defaultMotes}: false when a {@code :trail} particle replaces the element's own motes. */
    ArcaneBeamVisual(boolean defaultMotes) {
        this.defaultMotes = defaultMotes;
        Random r = new Random(0x41524341L);
        fill(runes, r, .055f, .03f, .5f, .62f, .16f, .08f);
        fill(glints, r, .13f, .08f, .3f, 1.05f, .07f, .06f);
        fill(dots, r, .26f, .16f, .2f, 1.1f, .022f, .02f);
    }

    private static void fill(Mote[] motes, Random r, float speed, float speedSpread, float spinMax, float radius, float size, float sizeSpread) {
        for (int i = 0; i < motes.length; i++) {
            motes[i] = new Mote((i + r.nextDouble() * .8) / motes.length, speed + r.nextFloat() * speedSpread,
                    r.nextFloat() * 6.283f, (r.nextFloat() - .5f) * spinMax * .2f, radius * (.35f + .65f * r.nextFloat()),
                    size + r.nextFloat() * sizeSpread, r.nextFloat() * 6.283f, r.nextInt(9));
        }
    }

    /** Each frame, from the cast point to the end as it reaches out; {@code out} already goes through {@link ShaderPackOverlay}. */
    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera, PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        length = delta.length();
        if (length < .05) return;
        this.from = from;
        lastFrom = from;
        dir = delta.scale(1 / length);
        side = dir.cross(Math.abs(dir.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        lift = side.cross(dir).normalize();
        double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (born == Long.MIN_VALUE) born = level.getGameTime();
        double age = now - born;
        // Still reaching out while it is at least as long as LogosFx's reach could make it since it first showed.
        boolean reaching = length > age * REACH - 1;
        if (reaching) arrivedAt = -1;
        else if (arrivedAt < 0) arrivedAt = now;
        float arrived = arrivedAt < 0 ? 0 : (float) Math.min(1, (now - arrivedAt) / 3);
        float open = (float) Math.min(1, .3 + age / 3);
        float r = Math.max(.15f, radius);
        float rs = Math.max(.6f, Math.min(1.6f, r / .5f));
        float pulse = 1 + .06f * (float) Math.sin(now * .55);
        float t = (float) age;

        Vec3 cam = camera.pos;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        stack.pushPose();
        stack.translate(-cam.x, -cam.y, -cam.z);

        // ---- the body ----
        out.submitCustomGeometry(stack, SILK, (pose, buf) -> strip(pose, buf, cam, 1.3f * r * open, argb(.2f, PERIWINKLE),
                .22f, -t * .035f, arrived));
        out.submitCustomGeometry(stack, GLOW, (pose, buf) -> strip(pose, buf, cam, .9f * r * open * pulse, argb(.55f, VIOLET),
                0, 0, arrived));
        out.submitCustomGeometry(stack, STARRY, (pose, buf) -> strip(pose, buf, cam, .62f * r * open, argb(.75f, mix(PINK, DEEP, .35f)),
                .45f, -t * .11f, arrived));
        float flip = (int) (t / 2) * .37f;
        out.submitCustomGeometry(stack, ELECTRIC, (pose, buf) -> strip(pose, buf, cam, .45f * r * open, argb(.2f, 0xB98CFF),
                .5f, flip, arrived));
        out.submitCustomGeometry(stack, CORE, (pose, buf) -> strip(pose, buf, cam, .3f * r * open * pulse, argb(.95f, PINK),
                0, 0, arrived));
        out.submitCustomGeometry(stack, WIND, (pose, buf) -> wisps(pose, buf, cam, r, t));

        // ---- what flows along it ----
        if (defaultMotes) {
            out.submitCustomGeometry(stack, FUZZ, (pose, buf) -> motes(pose, buf, cam, right, up, runes, t, r, 0));
            out.submitCustomGeometry(stack, RUNE, (pose, buf) -> motes(pose, buf, cam, right, up, runes, t, r, 1));
            out.submitCustomGeometry(stack, GLINT, (pose, buf) -> motes(pose, buf, cam, right, up, glints, t, r, 2));
            out.submitCustomGeometry(stack, DOT, (pose, buf) -> motes(pose, buf, cam, right, up, dots, t, r, 3));
        }

        // ---- the wand head ----
        float close = (float) smooth(.6, 2.2, cam.distanceTo(from));
        Vec3 head = from.add(dir.scale(.1 + .45 * (1 - close)));
        float glow = (.5f * close + .1f) * rs * pulse, flare = (.55f * close + .1f) * rs;
        float headAlpha = .45f + .55f * close;
        float roll = t * 3;
        out.submitCustomGeometry(stack, FUZZ, (pose, buf) -> sprite(pose, buf, head, right, up, glow, 0,
                argb(.6f * headAlpha, mix(PERIWINKLE, DEEP, .5f)), 0, 0, 1, 1, cam, false));
        out.submitCustomGeometry(stack, FLARE, (pose, buf) -> {
            sprite(pose, buf, head, right, up, flare, roll, argb(.85f * headAlpha, PINK), 0, 0, 1, 1, cam, false);
            sprite(pose, buf, head, right, up, flare * .7f, roll + 110, argb(.6f * headAlpha, VIOLET), 0, 0, 1, 1, cam, false);
        });
        if (close > .05f) {
            // A small ring of runes turning around the wand head (Piloto's ArcaneRunes_Atlas), only away from the eye.
            out.submitCustomGeometry(stack, RUNE, (pose, buf) -> ring(pose, buf, cam, right, up, from.add(dir.scale(.2)),
                    .34f * rs, .11f * rs, .8f * close, t * .09f, 3, 0));
        }

        // ---- the end ----
        if (arrived < 1) {
            // The leading spark while it reaches out.
            float k = 1 - arrived;
            out.submitCustomGeometry(stack, FUZZ, (pose, buf) -> sprite(pose, buf, to, right, up, .55f * rs, 0,
                    argb(.7f * k, VIOLET), 0, 0, 1, 1, cam, true));
            out.submitCustomGeometry(stack, GLINT, (pose, buf) -> sprite(pose, buf, to, right, up, .42f * rs, t * 6,
                    argb(k, HOT), 0, 0, 1, 1, cam, true));
        }
        if (arrived > 0) {
            Vec3 end = to.subtract(dir.scale(Math.min(.12, length * .1)));
            float a = arrived;
            float breathe = 1 + .1f * (float) Math.sin(now * .45);
            out.submitCustomGeometry(stack, END_GLOW, (pose, buf) -> sprite(pose, buf, end, right, up, 1.0f * rs * breathe, 0,
                    argb(.55f * a, VIOLET), 0, 0, 1, 1, cam, true));
            out.submitCustomGeometry(stack, END_HALO, (pose, buf) -> sprite(pose, buf, end, right, up, .72f * rs * breathe, t * 2.2f,
                    argb(.6f * a, PINK), 0, 0, 1, 1, cam, true));
            out.submitCustomGeometry(stack, END_RAYS, (pose, buf) -> sprite(pose, buf, end, right, up, .85f * rs, -t * 1.4f,
                    argb(.45f * a, DEEP), 0, 0, 1, 1, cam, true));
            float twinkle = .75f + .25f * (float) Math.sin(now * .9);
            out.submitCustomGeometry(stack, END_CROSS, (pose, buf) -> sprite(pose, buf, end, right, up, .5f * rs * twinkle, 0,
                    argb(.85f * a, HOT), 0, 0, 1, 1, cam, true));
            out.submitCustomGeometry(stack, RUNE, (pose, buf) -> ring(pose, buf, cam, right, up, end,
                    .5f * rs, .13f * rs, .9f * a, -t * .07f, 4, 3));
        }
        stack.popPose();
    }

    /** The beam is gone: nothing keeps playing (no Photon runtimes); its wake takes over along the line. */
    void end() {}

    /** LogosFx's reach in blocks a tick (BEAM_VFX.md: 4, a 64-block beam in 0.8 s); it is private there. */
    private static final double REACH = 4;

    // ---- pieces -----------------------------------------------------------------------------------

    /** A camera-facing ribbon over the whole beam; U runs {@code uPerBlock} per block from {@code u0}. */
    private void strip(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, float halfWidth, int color, float uPerBlock, float u0, float arrived) {
        if (halfWidth <= 0) return;
        int n = (int) Math.max(2, Math.min(180, Math.ceil(length / .35)));
        Vec3[] p = new Vec3[n + 1];
        float[] w = new float[n + 1], u = new float[n + 1];
        int[] c = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            double s = length * i / n;
            p[i] = from.add(dir.scale(s));
            float shape = taper(s, arrived);
            w[i] = halfWidth * shape;
            u[i] = (float) (u0 + s * uPerBlock);
            c[i] = ArcaneRayVisual.fade(color, Math.min(1, shape * 1.4f));
        }
        polyline(pose, buf, cam, p, w, u, c);
    }

    /** Opens softly at the wand head; comes to a point while reaching out, and ends roundly in the halo once there. */
    private float taper(double s, float arrived) {
        float start = (float) Math.min(1, .3 + s / .7);
        double left = length - s;
        float point = (float) Math.min(1, .12 + left / Math.min(1.4, length * .4));
        float round = (float) Math.min(1, .45 + left / .5);
        return start * (point + (round - point) * arrived);
    }

    /** Trail_WindyGeneric wisps: twin strands twisting around the line, racing away from the caster. */
    private void wisps(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, float r, float t) {
        int count = (int) Math.ceil((length + WISP_LENGTH) / WISP_GAP);
        double shift = t * .55 % WISP_GAP;
        for (int i = 0; i < count; i++) {
            double headS = i * WISP_GAP + shift, tailS = headS - WISP_LENGTH;
            double s0 = Math.max(.4, tailS), s1 = Math.min(length, headS);
            if (s1 - s0 < .1) continue;
            int n = (int) Math.max(4, Math.min(40, (s1 - s0) / .12));
            for (int k = 0; k < 2; k++) {
                Vec3[] p = new Vec3[n + 1];
                float[] w = new float[n + 1], u = new float[n + 1];
                int[] c = new int[n + 1];
                for (int j = 0; j <= n; j++) {
                    double s = s0 + (s1 - s0) * j / n;
                    double rad = .62 * r * Math.min(1, s / 1.4);
                    double a = s / 2.1 * Math.PI * 2 + k * Math.PI + t * .3;
                    p[j] = from.add(dir.scale(s)).add(side.scale(Math.cos(a) * rad)).add(lift.scale(Math.sin(a) * rad));
                    float along = (float) ((s - tailS) / WISP_LENGTH);
                    w[j] = .14f * r;
                    u[j] = along;
                    float fade = (float) (smooth(.4, 1.6, s) * smooth(0, .8, length - s));
                    c[j] = argb(.75f * fade, mix(DEEP, 0xFF53FD, along));
                }
                polyline(pose, buf, cam, p, w, u, c);
            }
        }
    }

    /** {@code kind}: 0 the soft glow behind each rune, 1 the runes, 2 glints, 3 motes. */
    private void motes(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 right, Vec3 up, Mote[] motes, float t, float r, int kind) {
        for (Mote m : motes) {
            double s = (m.phase * PERIOD + t * m.speed) % PERIOD;
            if (s > length) continue;
            float fade = (float) (smooth(.6, 1.8, s) * smooth(0, 1.2, length - s));
            if (fade <= 0) continue;
            float a = m.angle + t * m.spin;
            double rad = m.radius * r;
            Vec3 at = from.add(dir.scale(s)).add(side.scale(Math.cos(a) * rad)).add(lift.scale(Math.sin(a) * rad));
            switch (kind) {
                case 0 -> sprite(pose, buf, at, right, up, m.size * 2.4f, 0, argb(.28f * fade, VIOLET), 0, 0, 1, 1, cam, true);
                case 1 -> {
                    float twinkle = .75f + .25f * (float) Math.sin(t * .4f + m.twinkle);
                    float u0 = m.frame % 3 / 3f, v0 = m.frame / 3 / 3f;
                    int tint = mix(HOT, mix(PINK, DEEP, (float) (s / Math.max(8, length))), .55f);
                    sprite(pose, buf, at, right, up, m.size, m.twinkle * 20 + t * m.spin * 30, argb(.9f * fade * twinkle, tint),
                            u0, v0, u0 + 1 / 3f, v0 + 1 / 3f, cam, true);
                }
                case 2 -> {
                    float twinkle = .3f + .7f * (float) Math.pow(.5 + .5 * Math.sin(t * .7f + m.twinkle), 2);
                    sprite(pose, buf, at, right, up, m.size * (.7f + .3f * twinkle), m.frame > 4 ? 45 : 0,
                            argb(fade * twinkle, mix(HOT, LILAC, .4f)), 0, 0, 1, 1, cam, true);
                }
                default -> sprite(pose, buf, at, right, up, m.size, 0, argb(.9f * fade, BLUSH), 0, 0, 1, 1, cam, true);
            }
        }
    }

    /** {@code count} runes turning around the beam's axis at {@code at}. */
    private void ring(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 right, Vec3 up, Vec3 at, float radius,
            float size, float alpha, float turn, int count, int firstFrame) {
        for (int i = 0; i < count; i++) {
            double a = turn + i * Math.PI * 2 / count;
            Vec3 p = at.add(side.scale(Math.cos(a) * radius)).add(lift.scale(Math.sin(a) * radius));
            int frame = (firstFrame + i * 2) % 9;
            float u0 = frame % 3 / 3f, v0 = frame / 3 / 3f;
            sprite(pose, buf, p, right, up, size, (float) Math.toDegrees(a) * .3f, argb(alpha, i % 2 == 0 ? HOT : LILAC),
                    u0, v0, u0 + 1 / 3f, v0 + 1 / 3f, cam, true);
        }
    }

    // ---- materials (the arcane ray's shader and textures, read only) --------------------------------

    private static RenderType material(String name, String texture, boolean additive, String... defines) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/arcane_beam_" + name)).withFragmentShader(id("core/arcane_ray_fx"))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        for (String define : defines) builder.withShaderDefine(define);
        return RenderType.create("logos_arcane_beam_" + name, RenderSetup.builder(builder.build())
                .withTexture("Sampler0", id("textures/vfx/arcane_ray/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
