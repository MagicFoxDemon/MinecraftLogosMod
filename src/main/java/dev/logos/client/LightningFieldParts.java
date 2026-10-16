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
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the lightning field ({@link LightningFieldVisual}) draws beyond the shared core: the material for the
 * arcs flashing on its edge curtain (fragment shader {@code core/lightning_field_curtain} over the beacon-beam
 * vertex stage, bent by {@code lightning_beam/noise}), and flat flipbook frames for the crackles on the
 * ground. Every vertex is written in full through {@link ChargeParts#vertex}; everything is submitted through
 * the caller's collector, so it goes through {@link ShaderPackOverlay}.
 */
final class LightningFieldParts {
    private LightningFieldParts() {}

    /** Additive: thin upright arcs striking at random round a cylinder (see {@code lightning_field_curtain.fsh}). */
    static final RenderType CURTAIN_ARCS = RenderType.create("logos_lightning_field_curtain", RenderSetup.builder(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/lightning_field_curtain")).withFragmentShader(id("core/lightning_field_curtain"))
                    .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build())
            .withTexture("Sampler0", id("textures/vfx/lightning_beam/noise.png")).sortOnUpload().createRenderSetup());

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    /** A square of half-size {@code r} lying flat at {@code c}, turned by {@code turn}, showing frame {@code frame} of a flipbook. */
    static void flatFrame(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, float turn, int frame, int cols, int rows, int color) {
        float cos = Mth.cos(turn) * r, sin = Mth.sin(turn) * r;
        Vec3 a = new Vec3(cos, 0, sin), b = new Vec3(-sin, 0, cos);
        float u0 = (frame % cols) / (float) cols, v0 = (frame / cols % rows) / (float) rows, u1 = u0 + 1f / cols, v1 = v0 + 1f / rows;
        ChargeParts.vertex(pose, buf, c.subtract(a).subtract(b), u0, v0, color);
        ChargeParts.vertex(pose, buf, c.subtract(a).add(b), u0, v1, color);
        ChargeParts.vertex(pose, buf, c.add(a).add(b), u1, v1, color);
        ChargeParts.vertex(pose, buf, c.add(a).subtract(b), u1, v0, color);
    }
}
