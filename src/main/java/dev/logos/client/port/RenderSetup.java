package dev.logos.client.port;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * Stands in for 26.1's {@code net.minecraft.client.renderer.rendertype.RenderSetup}: a pipeline with the textures bound
 * to its samplers, whether it is lit by the lightmap, and whether its quads are sorted back to front.
 */
public final class RenderSetup {
    final RenderPipeline pipeline;
    final Map<String, ResourceLocation> textures;
    final boolean lightmap;
    final boolean sortOnUpload;

    private RenderSetup(Builder b) {
        this.pipeline = b.pipeline;
        this.textures = Map.copyOf(b.textures);
        this.lightmap = b.lightmap;
        this.sortOnUpload = b.sortOnUpload;
    }

    public static Builder builder(RenderPipeline pipeline) {
        return new Builder(pipeline);
    }

    public static final class Builder {
        private final RenderPipeline pipeline;
        private final Map<String, ResourceLocation> textures = new LinkedHashMap<>();
        private boolean lightmap;
        private boolean sortOnUpload;

        private Builder(RenderPipeline pipeline) {
            this.pipeline = pipeline;
        }

        public Builder withTexture(String sampler, ResourceLocation texture) {
            textures.put(sampler, texture);
            return this;
        }

        public Builder useLightmap() {
            lightmap = true;
            return this;
        }

        public Builder sortOnUpload() {
            sortOnUpload = true;
            return this;
        }

        public RenderSetup createRenderSetup() {
            return new RenderSetup(this);
        }
    }
}
