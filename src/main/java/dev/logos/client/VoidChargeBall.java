package dev.logos.client;

import static dev.logos.client.ChargeParts.basis;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

import org.joml.Quaternionf;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The void charge's ball at the head, in place of the shared one: the void beam's sphere at the wand (its
 * mesh and its shader {@code void_beam.fsh}), a sphere of open void (the screen-projected starfield over a
 * dark nebula, rimmed in violet) wrapped in violet shells, more of them each tier:
 * <ul>
 * <li>ordinary: the void core and its wispy aura;</li>
 * <li>elaborate: and the veined shell;</li>
 * <li>grand: and the flowing shell;</li>
 * <li>colossal: and the dark cloud and a tilted void ring.</li>
 * </ul>
 * The core is opaque and writes depth, as the beam's sphere core does, so the shared charge's glows at the
 * head can never wash over the void whichever order the render types are drawn in. It grows as the charge
 * fills, swells and collapses into the burst on a colossal release, collapses on the others, and shrinks away
 * flickering if dropped. A colossal charge also has a see-through sphere of the same void closing in
 * ({@code void_charge_shell.fsh}). Sizes follow the shared ball: {@link LiveCharge#near()}, and in first
 * person never more than three tenths of the distance to the camera.
 */
final class VoidChargeBall {
    /** Radius of the held ball by tier, as the shared head draws it. */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};
    private static final int VIOLET = 0x9A28FF;
    private static final Mesh SPHERE = Mesh.load();
    private static final RenderType CORE = material("core", "solid", "VOID_SPHERE_CORE", "core/void_beam", false, true);
    private static final RenderType DARK = material("dark", "flow", "VOID_SPHERE_DARK", "core/void_beam", false, false);
    private static final RenderType SOLID = material("solid", "solid", "VOID_SPHERE_SOLID", "core/void_beam", true, false);
    private static final RenderType FLOW = material("flow", "flow", "VOID_SPHERE_FLOW", "core/void_beam", true, false);
    private static final RenderType AURA = material("aura", "aura", "VOID_SPHERE_AURA", "core/void_beam", true, false);
    private static final RenderType SHELL = material("shell", "solid", null, "core/void_charge_shell", false, false);

    private VoidChargeBall() {}

    static void submit(LiveCharge c, ChargeParts.View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        int tier = c.tier();
        Vec3 tip = c.tip(pt);
        double now = c.age(pt);
        float near = c.near(), p = c.progress(pt), since = c.phaseAge(pt);
        // Nothing at the head may sit on the camera: shrink what comes within reach of it.
        float clear = fade(view.cam(), tip, .15, .45);
        LiveCharge.Phase phase = c.phase();
        if (tier == 3 && phase == LiveCharge.Phase.CHARGING) shell(stack, out, view, tip, now, p, near, c.firstPerson());

        float r = BALL[tier] * near, glow = 1;
        switch (phase) {
            case CHARGING -> {
                float grow = tier == 3 ? smooth(.8f, 1f, p) : (float) Math.pow(p, 1.4);
                r *= .25f + .75f * grow;
                glow = .4f + .6f * grow;
            }
            case HELD -> r *= 1 + .06f * Mth.sin((float) now * .5f);
            case RELEASED -> {
                if (tier == 3) {
                    // Swells (less than the shared ball: it is solid void), then falls in on itself as the burst breaks out of it.
                    float k = since / 7;
                    if (k >= 1) return;
                    r = Mth.lerp(1 - (1 - k) * (1 - k), r, 1.1f * near) * (1 - smooth(.7f, 1f, k));
                } else {
                    float k = since / 4;
                    if (k >= 1) return;
                    r *= 1 - k * k;
                    glow = 1 - k;
                }
            }
            case FIZZLED -> {
                float k = since / 20;
                if (k >= 1) return;
                r *= Math.max(0, (.25f + .75f * p) * (1 - k));
                glow = (1 - k) * (.6f + .4f * Mth.sin(since * 2.3f));
            }
        }
        if (c.firstPerson()) r = Math.min(r, (float) view.cam().distanceTo(tip) * .3f);
        r *= clear;
        if (r <= .002f) return;

        float size = r;
        int violet = scale(VIOLET, glow), pale = scale(lerpColor(VIOLET, 0xD8B0FF, .5f), glow);
        Quaternionf turn = new Quaternionf().rotationYXZ((float) (now * .03), .35f, 0);
        stack.pushPose();
        stack.translate(tip.x, tip.y, tip.z);
        stack.mulPose(turn);
        layer(stack, out, CORE, size * .85f, now, 0xFF000000 | VIOLET);
        if (tier == 3) layer(stack, out, DARK, size * .9f, now, 0xFF000000 | VIOLET);
        if (tier >= 1) layer(stack, out, SOLID, size * .95f, now, pale);
        if (tier >= 2) layer(stack, out, FLOW, size * 1.04f, now, violet);
        layer(stack, out, AURA, size * 1.2f, now, violet);
        stack.popPose();
        if (tier == 3) {
            int ring = ChargeParts.argb(.6f * glow, VIOLET);
            out.submitCustomGeometry(stack, ChargeParts.add("void_ray/ring"), (pose, buf) -> {
                Vec3[] tilt = basis(new Vec3(Math.sin(now * .03), 1.2, Math.cos(now * .03)).normalize());
                ChargeParts.flat(pose, buf, tip, tilt[0], tilt[1], size * 2.1f, (float) now * .08f, ring);
            });
        }
    }

    /** Colossal: the open void closing in round the head as the charge fills, shown only once smaller than its distance to the camera. */
    private static void shell(PoseStack stack, SubmitNodeCollector out, ChargeParts.View view, Vec3 tip, double now, float p, float near,
            boolean firstPerson) {
        float show = smooth(.45f, .65f, p);
        if (show <= 0) return;
        float k = smooth(.45f, 1f, p);
        float r = Mth.lerp((float) Math.pow(k, 1.4), 4.2f, BALL[3]) * (near < 1 ? .7f : 1);
        float seen = show * fade(view.cam(), tip, r, r * 2 + .1);
        if (seen <= 0) return;
        // Thin while it is large, closer to solid void as it closes; fainter still across a first-person view.
        float alpha = seen * (.45f + .5f * k) * (firstPerson ? .45f : 1);
        stack.pushPose();
        stack.translate(tip.x, tip.y, tip.z);
        stack.mulPose(new Quaternionf().rotationY((float) (-now * .02)));
        layer(stack, out, SHELL, r, now, ChargeParts.argb(alpha, VIOLET));
        layer(stack, out, AURA, r * 1.08f, now, scale(VIOLET, seen));
        stack.popPose();
    }

    private static void layer(PoseStack stack, SubmitNodeCollector out, RenderType type, float radius, double now, int color) {
        stack.pushPose();
        stack.scale(radius, radius, radius);
        out.submitCustomGeometry(stack, type, (pose, buf) -> SPHERE.draw(pose, buf, color));
        stack.popPose();
    }

    /** Additive layers fade by their colour: the void beam's shader works out their own alpha. */
    private static int scale(int rgb, float k) {
        return 0xFF000000 | lerpColor(0, rgb, k);
    }

    /** The void beam's unit sphere ({@code models/vfx/void_beam/sphere.bin}): two big-endian counts, eight floats per vertex (XYZ, UV, normal), then triangle indices. */
    private record Mesh(float[] vertices, int[] triangles) {
        static Mesh load() {
            try (var in = new DataInputStream(Objects.requireNonNull(VoidChargeBall.class.getResourceAsStream(
                    "/assets/logos/models/vfx/void_beam/sphere.bin")))) {
                int count = in.readInt(), indices = in.readInt();
                if (count < 1 || count > 20000 || indices < 3 || indices > 100000 || indices % 3 != 0)
                    throw new IOException("Invalid void sphere mesh");
                float[] vertices = new float[count * 8];
                int[] triangles = new int[indices];
                for (int i = 0; i < vertices.length; i++) vertices[i] = in.readFloat();
                for (int i = 0; i < indices; i++) {
                    triangles[i] = in.readInt();
                    if (triangles[i] < 0 || triangles[i] >= count) throw new IOException("Invalid void sphere index");
                }
                return new Mesh(vertices, triangles);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Each triangle as a quad with its last corner repeated (the pipeline draws quads). */
        void draw(PoseStack.Pose pose, VertexConsumer buf, int color) {
            for (int i = 0; i < triangles.length; i += 3) {
                put(pose, buf, triangles[i], color);
                put(pose, buf, triangles[i + 1], color);
                put(pose, buf, triangles[i + 2], color);
                put(pose, buf, triangles[i + 2], color);
            }
        }

        private void put(PoseStack.Pose pose, VertexConsumer buf, int index, int color) {
            int k = index * 8;
            buf.addVertex(pose, vertices[k], vertices[k + 1], vertices[k + 2]).setColor(color).setUv(vertices[k + 3], vertices[k + 4])
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, vertices[k + 5], vertices[k + 6], vertices[k + 7]);
        }
    }

    /**
     * A layer through the void beam's shaders (vertex {@code core/void_projectile}, which hands the fragment its
     * view position for the rim), with the beam's textures read in place. The opaque core writes depth.
     */
    private static RenderType material(String name, String texture, String define, String fragment, boolean additive, boolean opaque) {
        boolean core = name.equals("core") || name.equals("shell");
        boolean flow = name.equals("flow");
        boolean warp = flow || name.equals("dark") || name.equals("aura");
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_charge_" + name)).withVertexShader(id("core/void_projectile"))
                .withFragmentShader(id(fragment)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, opaque))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (define != null) pipeline.withShaderDefine(define);
        if (flow) pipeline.withSampler("NoiseTex");
        if (warp) pipeline.withSampler("WarpTex");
        if (core) pipeline.withSampler("StarTex").withSampler("AuraTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/void_beam/" + texture + ".png"));
        if (flow) setup.withTexture("NoiseTex", id("textures/vfx/void_beam/dissolve.png"));
        if (warp) setup.withTexture("WarpTex", id("textures/vfx/void_beam/warp.png"));
        if (core) setup.withTexture("StarTex", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"))
                .withTexture("AuraTex", id("textures/vfx/void_beam/aura.png"));
        return RenderType.create("logos_void_charge_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
