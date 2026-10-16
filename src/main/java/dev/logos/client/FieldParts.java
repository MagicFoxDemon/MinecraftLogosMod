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

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the shared field ({@link FieldCore}) draws its magic circles with: a material that uses a circle
 * texture as a mask and lets a fragment shader fill its lines (vertex stage {@code core/field_line}, which
 * hands the fragment stage two numbers per circle through the vertex's second UV: how far out a pulse's
 * light has swept, and how far the circle has dissolved). Everything else the field draws uses
 * {@link ChargeParts}. Every vertex carries position, colour, UV, the two numbers and normal; everything is
 * submitted through the caller's collector, so it goes through {@link ShaderPackOverlay}.
 */
final class FieldParts {
    private FieldParts() {}

    /** A sweep this far out (in circle radii) means no pulse is passing. */
    static final float NO_SWEEP = 3;

    private static final Map<String, RenderType> LINES = new HashMap<>();

    /** Additive: the circle {@code circle} (under {@code textures/vfx/}) as a mask, its lines filled by {@code shader} flowing {@code fill}. */
    static RenderType lines(String circle, String shader, String fill) {
        return LINES.computeIfAbsent(circle + "|" + shader + "|" + fill, k -> {
            String key = (circle + "_" + shader + "_" + fill).replace('/', '_').replace(':', '_');
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/field_lines_" + key))
                    .withVertexShader(id("core/field_line")).withFragmentShader(shaderId(shader)).withCull(false)
                    .withSampler("FillTex")
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
            return RenderType.create("logos_field_lines_" + key, RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", id("textures/vfx/" + circle + ".png"))
                    .withTexture("FillTex", id("textures/vfx/" + fill + ".png")).sortOnUpload().createRenderSetup());
        });
    }

    private static Identifier shaderId(String shader) {
        Identifier parsed = Identifier.tryParse(shader);
        return shader.contains(":") && parsed != null ? parsed : id(shader);
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    /**
     * A circle of radius {@code r} lying flat at {@code c} (in the x-z plane), turned by {@code turn}: its
     * texture's U along x and V along z. {@code sweep} is how far out a pulse's light has run, in circle
     * radii ({@link #NO_SWEEP} for none); {@code dissolve} 0 (whole) to 1 (gone).
     */
    static void circle(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, float turn, int color, float sweep, float dissolve) {
        float cos = Mth.cos(turn) * r, sin = Mth.sin(turn) * r;
        Vec3 a = new Vec3(cos, 0, sin), b = new Vec3(-sin, 0, cos);
        int su = (int) Mth.clamp(sweep * 1000, 0, 65000), sv = (int) Mth.clamp(dissolve * 1000, 0, 65000);
        vertex(pose, buf, c.subtract(a).subtract(b), 0, 0, color, su, sv);
        vertex(pose, buf, c.subtract(a).add(b), 0, 1, color, su, sv);
        vertex(pose, buf, c.add(a).add(b), 1, 1, color, su, sv);
        vertex(pose, buf, c.add(a).subtract(b), 1, 0, color, su, sv);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int su, int sv) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setUv2(su, sv).setNormal(pose, 0, 1, 0);
    }
}
