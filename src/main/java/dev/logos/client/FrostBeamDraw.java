package dev.logos.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared pieces of the frost beam and the frost it leaves: the materials (custom geometry only, drawn
 * through {@code out} so shader packs get it from {@link ShaderPackOverlay}), the line frame, camera-facing
 * ribbons, sprites and ice crystals. Every vertex is written relative to the camera with position, colour,
 * UV, light and normal. Masks are referenced read-only from the existing frost, frost-ray, frost-impact
 * and fire-beam folders; only what was missing lives in {@code frost_beam/}.
 */
final class FrostBeamDraw {
    private FrostBeamDraw() {}

    static final RenderType HAZE = material("haze", "frost_ray/sheath", null, false),
            ICE = material("ice", "frost_beam/ice", "FROST_BEAM_ICE", false),
            LASER = material("laser", "frost_ray/laser", "FROST_BEAM_LASER", true),
            STARRY = material("starry", "frost_ray/starry", null, true),
            WISP = material("wisp", "frost_ray/wisp", null, true),
            SMOKE = material("smoke", "fire_beam/smoke_harsh", null, true),
            GLINTS = material("glints", "frost_ray/glints", null, true),
            FLECKS = material("flecks", "frost_beam/flecks", null, false),
            CRYSTAL = material("crystal", "frost_ray/noise", "FROST_BEAM_SOLID", false),
            CROSS = material("cross", "fire_beam/cross", null, true),
            GLOW = material("glow", "frost_ray/glow", null, true),
            RUNE = material("rune", "frost_ray/rune", null, true),
            SPIKES = material("spikes", "frost_beam/spikes", null, true),
            STAR = material("star", "frost_impact/star", null, true),
            SNOW = material("snow", "frost_ray/snowflake", null, true),
            MIST = material("mist", "frost_ray/mist", null, false),
            RIME = material("rime", "frost_ray/noise", "FROST_BEAM_RIME", true),
            RING = material("ring", "frost_beam/ring", null, true);

    /** The frost palette (frost projectile, impact and ray). */
    static final int PALE = 0xD8F2FF, ICY = 0xB4E2FF, BLUE = 0x8CCBFF, DEEP = 0x5FA8FF, CYAN = 0x98E6F2;

    /** The line a beam (or its wake) is drawn along, with a frame around it. */
    static final class Line {
        final Vec3 from, to, axis, right, up;
        final double length;

        Line(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            Vec3 d = to.subtract(from);
            length = d.length();
            axis = length > 1e-5 ? d.scale(1 / length) : new Vec3(0, 0, 1);
            Vec3 r = axis.cross(new Vec3(0, 1, 0));
            right = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize();
            up = right.cross(axis).normalize();
        }

        Vec3 at(double s) { return from.add(axis.scale(s)); }

        /** A unit vector across the line at angle {@code a}. */
        Vec3 radial(double a) { return right.scale(Math.cos(a)).add(up.scale(Math.sin(a))); }
    }

    interface Width { double at(double s); }
    interface Tint { int at(double s); }
    interface Offset { Vec3 at(double s); }

    /** A camera-facing band along the line from {@code s0} to {@code s1}; within {@code near} of the camera it keeps only {@code floor} of its alpha. */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Line line, double s0, double s1, Width width, Tint tint,
            float uOffset, float uScale, double near, double far, float floor) {
        ribbon(pose, buf, eye, line, s0, s1, null, width, tint, uOffset, uScale, near, far, floor, Math.max(8, Math.min(128, (int) ((s1 - s0) * 2))));
    }

    /** As above, bent sideways by {@code offset} (a helix, a ripple), with as many segments as asked. */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Line line, double s0, double s1, Offset offset, Width width,
            Tint tint, float uOffset, float uScale, double near, double far, float floor, int segments) {
        if (s1 - s0 < 1e-3) return;
        Vec3 prevA = null, prevB = null;
        int prevColor = 0;
        float prevU = 0;
        for (int i = 0; i <= segments; i++) {
            double s = s0 + (s1 - s0) * i / segments;
            Vec3 c = line.at(s);
            if (offset != null) c = c.add(offset.at(s));
            Vec3 tangent = line.axis;
            if (offset != null) {
                double ds = (s1 - s0) / segments;
                tangent = line.at(s + ds).add(offset.at(s + ds)).subtract(c);
                tangent = tangent.lengthSqr() < 1e-10 ? line.axis : tangent.normalize();
            }
            Vec3 side = tangent.cross(eye.subtract(c));
            side = side.lengthSqr() < 1e-8 ? line.right : side.normalize();
            Vec3 half = side.scale(width.at(s));
            int color = fadeNear(tint.at(s), c, eye, near, far, floor);
            float u = (float) s * uScale + uOffset;
            Vec3 a = c.add(half), b = c.subtract(half);
            if (prevA != null && ((prevColor | color) >>> 24) != 0) {
                vertex(pose, buf, eye, prevA, prevU, 0, prevColor);
                vertex(pose, buf, eye, prevB, prevU, 1, prevColor);
                vertex(pose, buf, eye, b, u, 1, color);
                vertex(pose, buf, eye, a, u, 0, color);
            }
            prevA = a;
            prevB = b;
            prevColor = color;
            prevU = u;
        }
    }

    /** A square facing the camera, rolled by {@code roll}, showing the UV rectangle given. */
    static void sprite(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Vec3 at, double size,
            double roll, float u0, float v0, float u1, float v1, int color) {
        if (color >>> 24 == 0 || size <= 0) return;
        double c = Math.cos(roll) * size, s = Math.sin(roll) * size;
        Vec3 x = camRight.scale(c).add(camUp.scale(s)), y = camUp.scale(c).subtract(camRight.scale(s));
        vertex(pose, buf, eye, at.subtract(x).add(y), u0, v0, color);
        vertex(pose, buf, eye, at.subtract(x).subtract(y), u0, v1, color);
        vertex(pose, buf, eye, at.add(x).subtract(y), u1, v1, color);
        vertex(pose, buf, eye, at.add(x).add(y), u1, v0, color);
    }

    /** A cell of a {@code columns x rows} sheet as a sprite. */
    static void cell(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Vec3 at, double size,
            double roll, int frame, int columns, int rows, int color) {
        float u = (frame % columns) / (float) columns, v = (frame / columns % rows) / (float) rows;
        sprite(pose, buf, eye, camRight, camUp, at, size, roll, u, v, u + 1f / columns, v + 1f / rows, color);
    }

    /** A camera-facing quad from {@code base} out along {@code dir}: V runs 1 at the base to 0 at the tip (an upright mask's tip). */
    static void streak(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 base, Vec3 dir, double length, double width,
            float u0, float v0, float u1, float v1, int color) {
        if (color >>> 24 == 0 || length <= 0) return;
        Vec3 side = dir.cross(eye.subtract(base));
        if (side.lengthSqr() < 1e-8) return;
        side = side.normalize().scale(width / 2);
        Vec3 tip = base.add(dir.scale(length));
        vertex(pose, buf, eye, tip.subtract(side), u0, v0, color);
        vertex(pose, buf, eye, base.subtract(side), u0, v1, color);
        vertex(pose, buf, eye, base.add(side), u1, v1, color);
        vertex(pose, buf, eye, tip.add(side), u1, v0, color);
    }

    /** A flat square lying across {@code normal} (a sigil around the line), rolled by {@code roll}; dims as it turns edge-on. */
    static void disc(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 at, Vec3 a, Vec3 b, Vec3 normal, double size, double roll, int color) {
        Vec3 x = a.scale(Math.cos(roll) * size).add(b.scale(Math.sin(roll) * size));
        Vec3 y = b.scale(Math.cos(roll) * size).subtract(a.scale(Math.sin(roll) * size));
        Vec3 view = eye.subtract(at);
        double facing = view.lengthSqr() < 1e-6 ? 1 : Math.abs(view.normalize().dot(normal));
        int c = scaleAlpha(color, (float) (.2 + .8 * facing));
        if (c >>> 24 == 0) return;
        vertex(pose, buf, eye, at.subtract(x).add(y), 0, 0, c);
        vertex(pose, buf, eye, at.subtract(x).subtract(y), 0, 1, c);
        vertex(pose, buf, eye, at.add(x).subtract(y), 1, 1, c);
        vertex(pose, buf, eye, at.add(x).add(y), 1, 0, c);
    }

    /** A six-sided pointed ice crystal along {@code dir}; the facets carry the shading. */
    static void crystal(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 centre, Vec3 dir, double size, double roll, int alpha) {
        if (alpha <= 0 || size < .003) return;
        Vec3 x = dir.cross(Math.abs(dir.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 y = dir.cross(x).normalize();
        Vec3 tip = centre.add(dir.scale(size * 2.6)), tail = centre.subtract(dir.scale(size * 1.5));
        for (int face = 0; face < 6; face++) {
            double a = face * Math.PI / 3 + roll, b = (face + 1) * Math.PI / 3 + roll;
            Vec3 p = centre.add(x.scale(Math.cos(a) * size)).add(y.scale(Math.sin(a) * size));
            Vec3 q = centre.add(x.scale(Math.cos(b) * size)).add(y.scale(Math.sin(b) * size));
            int rgb = face % 3 == 0 ? 0xF0FCFF : face % 3 == 1 ? 0x82B9E2 : 0xB8E8FF;
            triangle(pose, buf, eye, tip, p, q, alpha << 24 | rgb);
            triangle(pose, buf, eye, tail, q, p, alpha << 24 | rgb);
        }
    }

    private static void triangle(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 a, Vec3 b, Vec3 c, int color) {
        Vec3 n = b.subtract(a).cross(c.subtract(a));
        n = n.lengthSqr() < 1e-12 ? new Vec3(0, 1, 0) : n.normalize();
        for (Vec3 p : new Vec3[] {a, b, c, c}) {
            Vec3 rel = p.subtract(eye);
            buf.addVertex(pose, (float) rel.x, (float) rel.y, (float) rel.z).setColor(color).setUv(0, 0)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    /** Positions are written relative to the camera, so beams far from the world origin keep their precision. */
    static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, Vec3 at, float u, float v, int color) {
        Vec3 rel = at.subtract(eye);
        buf.addVertex(pose, (float) rel.x, (float) rel.y, (float) rel.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** Whether the line ends against something (a block or a creature) rather than in the open. */
    static boolean blocked(Level level, Line line) {
        Vec3 end = line.to;
        if (!level.getBlockState(BlockPos.containing(end.add(line.axis.scale(.3)))).isAir()
                || !level.getBlockState(BlockPos.containing(end)).isAir()) return true;
        if (line.length < 1.5) return false;
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(end, end).inflate(.6),
                e -> e.distanceToSqr(line.from) > 2.5).isEmpty();
    }

    // ---- small helpers ---------------------------------------------------------------------------

    /** A stable pseudo-random number in [0, 1) for a particle slot, its cycle and a salt. */
    static float hash(int a, int b, int c) {
        int h = a * 0x27D4EB2D ^ b * 0x165667B1 ^ c * 0x9E3779B9;
        h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12; h *= 0x297A2D39; h ^= h >>> 15;
        return (h >>> 8) / 16777216f;
    }

    /** 0 inside {@code near}, 1 beyond {@code far}: nothing piles up right in front of the camera. */
    static float nearFactor(Vec3 at, Vec3 eye, double near, double far) {
        return smooth((float) ((at.distanceTo(eye) - near) / (far - near)));
    }

    static int fadeNear(int color, Vec3 at, Vec3 eye, double near, double far, float floor) {
        return scaleAlpha(color, floor + (1 - floor) * nearFactor(at, eye, near, far));
    }

    static int scaleAlpha(int color, float k) {
        int a = (int) ((color >>> 24) * Math.max(0, Math.min(1, k)));
        return a << 24 | color & 0xFFFFFF;
    }

    static int argb(float alpha, int rgb) {
        return (int) (255 * Math.max(0, Math.min(1, alpha))) << 24 | rgb & 0xFFFFFF;
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }

    static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Rises over the first {@code in} and falls over the last {@code out} of a 0–1 life. */
    static float envelope(float k, float in, float out) {
        return smooth(k / in) * smooth((1 - k) / out);
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /** One layer: additive (glows) or alpha-blended (haze, mist, crystals), never writing depth, so blocks still hide it. */
    private static RenderType material(String name, String texture, String define, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/frost_beam_" + name))
                .withFragmentShader(id("core/frost_beam")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (define != null) pipeline.withShaderDefine(define);
        return RenderType.create("logos_frost_beam_" + name, RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", id("textures/vfx/" + texture + ".png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
