package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * The void field's own materials, beside the shared {@link FieldParts#lines} (which has only the circle and
 * one fill): both read the end portal's stars as the rest of the void effects do. Vertex stage
 * {@code core/field_line}, so the circle's geometry is {@link FieldParts#circle} and the curtain's
 * {@link ChargeParts#cylinder}, every vertex written in full, submitted through the caller's collector.
 */
final class VoidFieldParts {
    private VoidFieldParts() {}

    private static final Identifier PORTAL = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png");
    private static final Map<String, RenderType> SLITS = new HashMap<>();

    /**
     * The circle {@code circle}'s lines cut into the ground with the starfield behind them
     * ({@code void_field_slit.fsh}): opaque and writing depth, so the shared glow round the lines only shows
     * outside them, whichever is drawn first.
     */
    static RenderType slit(String circle) {
        return SLITS.computeIfAbsent(circle, c -> {
            String key = c.replace('/', '_');
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/void_field_slit_" + key))
                    .withVertexShader(id("core/field_line")).withFragmentShader(id("core/void_field_slit")).withCull(false)
                    .withSampler("StarTex").withSampler("AuraTex")
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
            return RenderType.create("logos_void_field_slit_" + key, RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", id("textures/vfx/" + c + ".png")).withTexture("StarTex", PORTAL)
                    .withTexture("AuraTex", id("textures/vfx/void_beam/aura.png")).sortOnUpload().createRenderSetup());
        });
    }

    /** The see-through veil of void at the edge ({@code void_field_curtain.fsh}): blended, not writing depth. */
    static final RenderType CURTAIN = curtain();

    private static RenderType curtain() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_field_curtain"))
                .withVertexShader(id("core/field_line")).withFragmentShader(id("core/void_field_curtain")).withCull(false)
                .withSampler("StarTex")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_void_field_curtain", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/void_beam/aura.png")).withTexture("StarTex", PORTAL).sortOnUpload()
                .createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
