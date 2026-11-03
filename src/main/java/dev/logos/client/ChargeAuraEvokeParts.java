package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the colossal lightning and void auras ({@link ChargeAuraEvoke}, after NS_Charge_EvokeEnergy_Root) draw
 * with: materials through their own shaders (vertex stage {@code core/charge_aura_evoke}, which hands the
 * fragment stage two numbers per vertex through the second UV, in thousandths) over the Niagara textures
 * copied to {@code textures/vfx/charge_aura/}, and the geometry that carries those numbers: camera-facing
 * ribbons and quads. Meshes go through {@link ChargeAuraMesh#draw}. Every vertex carries position, colour, UV,
 * the two numbers (in the light slot) and normal; everything is submitted through the caller's collector, so
 * it goes through {@link ShaderPackOverlay}.
 */
final class ChargeAuraEvokeParts {
    private ChargeAuraEvokeParts() {}

    /**
     * The element's touch on the shared aura's materials: lightning's arcs, void's screen-space stars, or none (frost
     * and physical, whose touches are layers of their own).
     */
    enum Accent {
        ELECTRIC, STARRY, PLAIN;

        String define() { return name(); }
    }

    /** The four ribbon materials of the reference: the flipbook wind, the soft flame, the sine-line strip and the ghost hair. */
    enum Ribbon { FLAME, GLOW, STRIP, HAIR }

    /** The two mesh materials: the spiral's panned noise (UvFade_Pan) and the burst ray (BrustRay). */
    enum Mesh { SPIRAL, RAY }

    static final Identifier PORTAL = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png");
    private static final Map<String, RenderType> TYPES = new HashMap<>();

    /** Ribbon of {@code kind}, with the element's accent: alpha-blended, as the reference's translucent materials. */
    static RenderType ribbon(Ribbon kind, Accent accent) {
        return TYPES.computeIfAbsent("ribbon_" + kind + "_" + accent, k -> {
            var p = pipeline(k, "core/charge_aura_evoke_ribbon", BlendFunction.TRANSLUCENT, false).withShaderDefine(kind.name())
                    .withShaderDefine(accent.define());
            String main = switch (kind) {
                case FLAME -> "charge_aura/stamp_engrywind_big_bgroot_offset_44";
                case HAIR -> accent == Accent.ELECTRIC ? "lightning_beam/shock" : "charge_aura/uvs_flow_water";
                default -> "charge_aura/basic_dot";
            };
            if (kind == Ribbon.HAIR) p.withSampler("NoiseTex").withSampler("AlphaTex");
            if (accent == Accent.STARRY) p.withSampler("StarTex");
            var setup = RenderSetup.builder(p.build()).withTexture("Sampler0", vfx(main));
            if (kind == Ribbon.HAIR) setup.withTexture("NoiseTex", vfx("charge_aura/noise_smoke")).withTexture("AlphaTex", vfx("charge_aura/noise_01"));
            if (accent == Accent.STARRY) setup.withTexture("StarTex", PORTAL);
            return RenderType.create(name(k), setup.sortOnUpload().createRenderSetup());
        });
    }

    /** Mesh material of {@code kind} over {@code charge_aura/noise_01}: alpha-blended, as the reference's translucent materials. */
    static RenderType mesh(Mesh kind, Accent accent) {
        return TYPES.computeIfAbsent("mesh_" + kind + "_" + accent, k -> {
            var p = pipeline(k, "core/charge_aura_evoke_mesh", BlendFunction.TRANSLUCENT, false).withShaderDefine(kind.name())
                    .withShaderDefine(accent.define());
            if (accent == Accent.STARRY) p.withSampler("StarTex");
            var setup = RenderSetup.builder(p.build()).withTexture("Sampler0", vfx("charge_aura/noise_01"));
            if (accent == Accent.STARRY) setup.withTexture("StarTex", PORTAL);
            return RenderType.create(name(k), setup.sortOnUpload().createRenderSetup());
        });
    }

    /**
     * A quad over {@code textures/vfx/<texture>.png} masked by its red channel: additive (the reference's additive
     * materials), or alpha-blended when {@code translucent} (its translucent ones).
     */
    static RenderType sprite(String texture, boolean translucent) {
        return TYPES.computeIfAbsent("sprite_" + (translucent ? "blend_" : "add_") + texture, k -> {
            var p = pipeline(k, "core/charge_aura_evoke_sprite", translucent ? BlendFunction.TRANSLUCENT : BlendFunction.LIGHTNING, false);
            return RenderType.create(name(k), RenderSetup.builder(p.build()).withTexture("Sampler0", vfx(texture)).sortOnUpload()
                    .createRenderSetup());
        });
    }

    /** A pipeline through {@code core/charge_aura_evoke} and {@code fragment}; {@code depth} writes depth (opaque layers). */
    static RenderPipeline.Builder pipeline(String key, String fragment, BlendFunction blend, boolean depth) {
        return RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/charge_aura_evoke_" + key.replace('/', '_').toLowerCase())).withVertexShader(id("core/charge_aura_evoke"))
                .withFragmentShader(id(fragment)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, depth))
                .withColorTargetState(new ColorTargetState(blend));
    }

    static String name(String key) { return "logos_charge_aura_evoke_" + key.replace('/', '_').toLowerCase(); }

    static Identifier vfx(String texture) { return id("textures/vfx/" + texture + ".png"); }

    static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    // ---- geometry ----------------------------------------------------------------------------------

    /** Two numbers for the fragment stage, 0 to 65, in thousandths through the second UV. */
    static int param(float value) {
        return (int) Mth.clamp(value * 1000, 0, 65000);
    }

    static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int a, int b) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setUv2(a, b).setNormal(pose, 0, 1, 0);
    }

    /**
     * A camera-facing ribbon through {@code points} (first to last), each with its own half-width, colour, U and two
     * numbers for the fragment stage ({@link #param}); V runs 0 to 1 across. Segments with both ends clear are skipped.
     * With an {@code axis}, every cross-section lies square to it (and faces the camera) instead of square to the
     * ribbon's own bends: a broad ribbon that bends sharply then shears instead of folding over itself into hard
     * creases.
     */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3[] points, float[] half, int[] colors, float[] u,
            int[] a, int[] b, int count, Vec3 axis) {
        Vec3 prevSide = null;
        for (int i = 0; i < count; i++) {
            Vec3 tangent = axis != null ? axis : points[Math.min(i + 1, count - 1)].subtract(points[Math.max(i - 1, 0)]);
            Vec3 side = tangent.cross(points[i].subtract(view.cam()));
            side = side.lengthSqr() < 1e-12 ? (prevSide != null ? prevSide : view.right()) : side.normalize();
            if (i > 0 && (colors[i - 1] >>> 24 != 0 || colors[i] >>> 24 != 0)) {
                vertex(pose, buf, points[i - 1].add(prevSide.scale(half[i - 1])), u[i - 1], 0, colors[i - 1], a[i - 1], b[i - 1]);
                vertex(pose, buf, points[i - 1].subtract(prevSide.scale(half[i - 1])), u[i - 1], 1, colors[i - 1], a[i - 1], b[i - 1]);
                vertex(pose, buf, points[i].subtract(side.scale(half[i])), u[i], 1, colors[i], a[i], b[i]);
                vertex(pose, buf, points[i].add(side.scale(half[i])), u[i], 0, colors[i], a[i], b[i]);
            }
            prevSide = side;
        }
    }

    /** A camera-facing quad at {@code c}, {@code w} by {@code h} half-extents, turned by {@code roll}, whole texture. */
    static void sprite(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, float w, float h, float roll, int color, int a, int b) {
        float cos = Mth.cos(roll), sin = Mth.sin(roll);
        Vec3 x = view.right().scale(cos).add(view.up().scale(sin)), y = view.up().scale(cos).subtract(view.right().scale(sin));
        quad(pose, buf, c, x.scale(w), y.scale(h), color, a, b);
    }

    /**
     * A quad facing the camera as far as it can while its V axis lies along {@code along} (a velocity-aligned
     * sprite), {@code w} wide and {@code l} long (half-extents), centred {@code pivot} of the way along from its tail.
     */
    static void aligned(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, Vec3 along, float w, float l, float pivot, int color,
            int a, int b) {
        if (along.lengthSqr() < 1e-10) {
            sprite(pose, buf, view, c, w, l, 0, color, a, b);
            return;
        }
        Vec3 dir = along.normalize();
        Vec3 side = dir.cross(c.subtract(view.cam()));
        if (side.lengthSqr() < 1e-10) side = view.right();
        side = side.normalize().scale(w);
        Vec3 tail = c.subtract(dir.scale(2 * l * pivot)), head = tail.add(dir.scale(2 * l));
        vertex(pose, buf, head.add(side), 0, 0, color, a, b);
        vertex(pose, buf, tail.add(side), 0, 1, color, a, b);
        vertex(pose, buf, tail.subtract(side), 1, 1, color, a, b);
        vertex(pose, buf, head.subtract(side), 1, 0, color, a, b);
    }

    /** A quad lying flat at {@code c} (a decal on the ground), half-size {@code r}, turned by {@code turn}. */
    static void flat(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, float turn, int color) {
        float cos = Mth.cos(turn) * r, sin = Mth.sin(turn) * r;
        quad(pose, buf, c, new Vec3(cos, 0, sin), new Vec3(-sin, 0, cos), color, 0, 0);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 x, Vec3 y, int color, int a, int b) {
        vertex(pose, buf, c.subtract(x).add(y), 0, 0, color, a, b);
        vertex(pose, buf, c.subtract(x).subtract(y), 0, 1, color, a, b);
        vertex(pose, buf, c.add(x).subtract(y), 1, 1, color, a, b);
        vertex(pose, buf, c.add(x).add(y), 1, 0, color, a, b);
    }

    /** The pillar's kept outer cylinder round a colossal aura, for a caster 1.8 tall: its radius and height in blocks. */
    static final float PILLAR_RADIUS = 1.25f * 1.35f, PILLAR_HEIGHT = 4.4f * 1.15f;

    /**
     * 1 for anything well clear of the middle of a first-person view, fading to 0 within about 25 degrees of it out to
     * seven blocks: what stands round the caster (and so round the camera) must not sit on the crosshair.
     */
    static float offCentre(View view, Vec3 at) {
        Vec3 d = at.subtract(view.cam());
        double len = d.length();
        if (len < 1e-4) return 0;
        double ahead = d.dot(view.up().cross(view.right())) / len;
        return 1 - ChargeParts.smooth(.8f, .93f, (float) ahead) * (1 - ChargeParts.smooth(5f, 7f, (float) len));
    }

    /** Moves {@code p} by {@code by} blocks towards the camera (negative: away), as Niagara's CameraOffset does. */
    static Vec3 toCamera(View view, Vec3 p, double by) {
        Vec3 d = view.cam().subtract(p);
        double len = d.length();
        return len < 1e-4 ? p : p.add(d.scale(Math.min(by, len * .8) / len));
    }

    /** A Niagara curve: {@code keys} as time, value pairs (times rising), clamped at both ends. */
    static float curve(float t, float... keys) {
        if (t <= keys[0]) return keys[1];
        for (int i = 2; i < keys.length; i += 2) {
            if (t <= keys[i]) {
                float k = (t - keys[i - 2]) / Math.max(1e-6f, keys[i] - keys[i - 2]);
                return Mth.lerp(k, keys[i - 1], keys[i + 1]);
            }
        }
        return keys[keys.length - 1];
    }
}
