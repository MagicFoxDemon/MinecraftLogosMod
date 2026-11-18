package dev.logos.client.port;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.resources.ResourceLocation;

/**
 * Stands in for the snippets of 26.1's {@code net.minecraft.client.renderer.RenderPipelines} that the effects build on.
 * Their shaders are the mod's own copies of 26.1's ({@code assets/logos/shaders/port/core}), written against the
 * 26.1 uniforms that {@link ShaderPrograms} provides.
 */
public final class RenderPipelines {
    private RenderPipelines() {}

    /** 26.1's uniform blocks are plain uniforms here, declared as a shader uses them; a snippet naming one adds nothing. */
    public static final RenderPipeline.Snippet GLOBALS_SNIPPET = RenderPipeline.builder().buildSnippet();

    public static final RenderPipeline.Snippet BEACON_BEAM_SNIPPET = RenderPipeline.builder()
            .withVertexShader(port("core/beacon_beam"))
            .withFragmentShader(port("core/beacon_beam"))
            .withSampler("Sampler0")
            .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .buildSnippet();

    public static final RenderPipeline.Snippet PARTICLE_SNIPPET = RenderPipeline.builder()
            .withVertexShader(port("core/particle"))
            .withFragmentShader(port("core/particle"))
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withVertexFormat(DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .buildSnippet();

    private static ResourceLocation port(String path) {
        return ResourceLocation.fromNamespaceAndPath("logos", "port/" + path);
    }
}
