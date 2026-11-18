package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import dev.logos.client.port.BlendFunction;
import dev.logos.client.port.ColorTargetState;
import dev.logos.client.port.DepthStencilState;
import dev.logos.client.port.RenderPipeline;
import dev.logos.client.port.CompareOp;

import dev.logos.client.port.RenderPipelines;
import dev.logos.client.port.RenderSetup;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * The void field's own materials, beside the shared {@link FieldParts#lines} (which has only the circle and
 * one fill): both read the end portal's stars as the rest of the void effects do. Vertex stage
 * {@code core/field_line}, so the circle's geometry is {@link FieldParts#circle} and the curtain's
 * {@link ChargeParts#cylinder}, every vertex written in full, submitted through the caller's collector.
 */
final class VoidFieldParts {
    private VoidFieldParts() {}

    private static final ResourceLocation PORTAL = ResourceLocation.withDefaultNamespace("textures/entity/end_portal/end_portal.png");
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
            return dev.logos.client.port.RenderTypes.create("logos_void_field_slit_" + key, RenderSetup.builder(pipeline)
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
        return dev.logos.client.port.RenderTypes.create("logos_void_field_curtain", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/void_beam/aura.png")).withTexture("StarTex", PORTAL).sortOnUpload()
                .createRenderSetup());
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("logos", path); }
}
