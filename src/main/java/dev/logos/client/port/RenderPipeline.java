package dev.logos.client.port;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.resources.ResourceLocation;

/**
 * Stands in for 26.1's {@code com.mojang.blaze3d.pipeline.RenderPipeline}: the shaders, defines, samplers, vertex
 * format and fixed state one kind of geometry is drawn with. On 1.21.1 {@link ShaderPrograms} compiles it into a shader
 * instance, and {@link RenderTypes} makes render types of it. Shader ids are as on 26.1: {@code core/name} is
 * {@code shaders/core/name.vsh} (and {@code .fsh}).
 */
public final class RenderPipeline {
    final ResourceLocation location;
    final ResourceLocation vertexShader;
    final ResourceLocation fragmentShader;
    final List<String> samplers;
    /** Name to value; a flag has an empty value. */
    final Map<String, String> defines;
    final VertexFormat format;
    final VertexFormat.Mode mode;
    final boolean cull;
    final DepthStencilState depth;
    final ColorTargetState color;

    private RenderPipeline(Builder b) {
        this.location = b.location;
        this.vertexShader = b.vertexShader;
        this.fragmentShader = b.fragmentShader;
        this.samplers = List.copyOf(b.samplers);
        this.defines = Map.copyOf(b.defines);
        this.format = b.format;
        this.mode = b.mode;
        this.cull = b.cull;
        this.depth = b.depth;
        this.color = b.color;
    }

    public ResourceLocation getLocation() {
        return location;
    }

    public static Builder builder(Snippet... snippets) {
        Builder b = new Builder();
        for (Snippet s : snippets) s.applyTo(b);
        return b;
    }

    @Override
    public String toString() {
        return location.toString();
    }

    /** Part of a pipeline, shared by many (26.1's {@code RenderPipeline.Snippet}). */
    public static final class Snippet {
        private final Builder parts;

        private Snippet(Builder parts) {
            this.parts = parts;
        }

        void applyTo(Builder b) {
            if (parts.vertexShader != null) b.vertexShader = parts.vertexShader;
            if (parts.fragmentShader != null) b.fragmentShader = parts.fragmentShader;
            for (String s : parts.samplers) if (!b.samplers.contains(s)) b.samplers.add(s);
            b.defines.putAll(parts.defines);
            if (parts.format != null) {
                b.format = parts.format;
                b.mode = parts.mode;
            }
            if (parts.cullSet) b.cull = parts.cull;
            if (parts.depthSet) b.depth = parts.depth;
            if (parts.colorSet) b.color = parts.color;
        }
    }

    public static final class Builder {
        private @Nullable ResourceLocation location;
        private @Nullable ResourceLocation vertexShader;
        private @Nullable ResourceLocation fragmentShader;
        private final List<String> samplers = new ArrayList<>();
        private final Map<String, String> defines = new LinkedHashMap<>();
        private @Nullable VertexFormat format;
        private VertexFormat.Mode mode = VertexFormat.Mode.QUADS;
        private boolean cull = true, cullSet;
        private DepthStencilState depth = DepthStencilState.DEFAULT;
        private boolean depthSet;
        private ColorTargetState color = ColorTargetState.DEFAULT;
        private boolean colorSet;

        private Builder() {}

        public Builder withLocation(ResourceLocation location) {
            this.location = location;
            return this;
        }

        public Builder withLocation(String location) {
            return withLocation(ResourceLocation.withDefaultNamespace(location));
        }

        public Builder withVertexShader(ResourceLocation shader) {
            this.vertexShader = shader;
            return this;
        }

        public Builder withVertexShader(String shader) {
            return withVertexShader(ResourceLocation.withDefaultNamespace(shader));
        }

        public Builder withFragmentShader(ResourceLocation shader) {
            this.fragmentShader = shader;
            return this;
        }

        public Builder withFragmentShader(String shader) {
            return withFragmentShader(ResourceLocation.withDefaultNamespace(shader));
        }

        public Builder withSampler(String sampler) {
            if (!samplers.contains(sampler)) samplers.add(sampler);
            return this;
        }

        public Builder withShaderDefine(String flag) {
            defines.put(flag, "");
            return this;
        }

        public Builder withShaderDefine(String name, int value) {
            defines.put(name, Integer.toString(value));
            return this;
        }

        public Builder withShaderDefine(String name, float value) {
            defines.put(name, Float.toString(value));
            return this;
        }

        public Builder withVertexFormat(VertexFormat format, VertexFormat.Mode mode) {
            this.format = format;
            this.mode = mode;
            return this;
        }

        public Builder withCull(boolean cull) {
            this.cull = cull;
            this.cullSet = true;
            return this;
        }

        public Builder withDepthStencilState(DepthStencilState depth) {
            this.depth = depth;
            this.depthSet = true;
            return this;
        }

        public Builder withDepthStencilState(Optional<DepthStencilState> depth) {
            return withDepthStencilState(depth.orElse(new DepthStencilState(CompareOp.ALWAYS_PASS, false)));
        }

        public Builder withColorTargetState(ColorTargetState color) {
            this.color = color;
            this.colorSet = true;
            return this;
        }

        public RenderPipeline build() {
            if (location == null) throw new IllegalStateException("A pipeline needs a location");
            if (vertexShader == null || fragmentShader == null) throw new IllegalStateException("Pipeline " + location + " needs both shaders");
            if (format == null) throw new IllegalStateException("Pipeline " + location + " needs a vertex format");
            return new RenderPipeline(this);
        }

        public Snippet buildSnippet() {
            return new Snippet(this);
        }
    }
}
