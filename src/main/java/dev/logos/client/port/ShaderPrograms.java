package dev.logos.client.port;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;

/**
 * Compiles a {@link RenderPipeline} into a 1.21.1 shader instance, on first use and again after resources reload.
 * The shaders are 26.1's: they import 26.1's includes ({@code minecraft:dynamictransforms.glsl} and the rest), which
 * 1.21.1 does not have or has otherwise. For these programs alone those includes are written here, declaring each
 * uniform a shader uses as the plain uniform 1.21.1 sets (26.1's fog values map onto 1.21.1's one fog), and the
 * pipeline's defines go in after the {@code #version} line. The program json 1.21.1 wants is written here too.
 */
public final class ShaderPrograms {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<ResourceLocation, ShaderInstance> COMPILED = new HashMap<>();
    private static final Set<ResourceLocation> FAILED = new HashSet<>();

    /** A 26.1 uniform: how a shader declares it, and how 1.21.1's program json does. */
    private record Uniform(String name, String glsl, String type, int count, float... values) {}

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final List<Uniform> UNIFORMS = List.of(
            new Uniform("ModelViewMat", "mat4", "matrix4x4", 16, IDENTITY),
            new Uniform("ProjMat", "mat4", "matrix4x4", 16, IDENTITY),
            new Uniform("TextureMat", "mat4", "matrix4x4", 16, IDENTITY),
            new Uniform("ColorModulator", "vec4", "float", 4, 1, 1, 1, 1),
            new Uniform("ModelOffset", "vec3", "float", 3, 0, 0, 0),
            new Uniform("ScreenSize", "vec2", "float", 2, 1, 1),
            new Uniform("GameTime", "float", "float", 1, 0),
            new Uniform("GlintAlpha", "float", "float", 1, 1),
            new Uniform("CameraOffset", "vec3", "float", 3, 0, 0, 0),
            new Uniform("FogStart", "float", "float", 1, 0),
            new Uniform("FogEnd", "float", "float", 1, 1),
            new Uniform("FogColor", "vec4", "float", 4, 0, 0, 0, 0),
            new Uniform("FogShape", "int", "int", 1, 0));
    private static final Map<String, List<String>> INCLUDE_UNIFORMS = Map.of(
            "dynamictransforms.glsl", List.of("ModelViewMat", "ColorModulator", "ModelOffset", "TextureMat"),
            "projection.glsl", List.of("ProjMat"),
            "globals.glsl", List.of("ScreenSize", "GameTime", "GlintAlpha", "CameraOffset"));
    /** 26.1's fog values, all of them 1.21.1's one fog's start and end. */
    private static final String FOG_NAMES = "FogEnvironmentalStart|FogEnvironmentalEnd|FogRenderDistanceStart|FogRenderDistanceEnd|FogSkyEnd|FogCloudsEnd";

    private ShaderPrograms() {}

    /** The compiled pipeline; one that fails to compile draws with vanilla's shader of its vertex format, and says so once. */
    public static ShaderInstance get(RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        ShaderInstance shader = COMPILED.get(pipeline.location);
        if (shader != null) return shader;
        if (!FAILED.contains(pipeline.location)) {
            try {
                shader = compile(pipeline);
                COMPILED.put(pipeline.location, shader);
                return shader;
            } catch (IOException | RuntimeException e) {
                FAILED.add(pipeline.location);
                LOG.error("Could not compile render pipeline {}", pipeline.location, e);
            }
        }
        return pipeline.format == DefaultVertexFormat.PARTICLE ? GameRenderer.getParticleShader() : GameRenderer.getRendertypeBeaconBeamShader();
    }

    /** Drops every compiled pipeline, as resources reload: the next use compiles it from the new ones. */
    public static ResourceManagerReloadListener reloadListener() {
        return manager -> RenderSystem.recordRenderCall(() -> {
            COMPILED.values().forEach(ShaderInstance::close);
            COMPILED.clear();
            FAILED.clear();
        });
    }

    private static ShaderInstance compile(RenderPipeline p) throws IOException {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        String vertex = withDefines(read(resources, shaderFile(p.vertexShader, ".vsh")), p.defines);
        String fragment = withDefines(read(resources, shaderFile(p.fragmentShader, ".fsh")), p.defines);
        String usage = vertex + "\n" + fragment;
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath(p.location.getNamespace(), "port/" + p.location.getPath());
        ResourceLocation json = name.withPath(path -> "shaders/core/" + path + ".json");
        ResourceLocation vsh = name.withPath(path -> "shaders/core/" + path + ".vsh");
        ResourceLocation fsh = name.withPath(path -> "shaders/core/" + path + ".fsh");
        String program = programJson(name, p.samplers, usage);
        ResourceProvider provider = loc -> {
            if (loc.equals(json)) return Optional.of(text(program));
            if (loc.equals(vsh)) return Optional.of(text(vertex));
            if (loc.equals(fsh)) return Optional.of(text(fragment));
            if (loc.getNamespace().equals("minecraft") && loc.getPath().startsWith("shaders/include/")) {
                String include = include(loc.getPath().substring("shaders/include/".length()), usage);
                if (include != null) return Optional.of(text(include));
            }
            return resources.getResource(loc);
        };
        return new ShaderInstance(provider, name, p.format);
    }

    /** {@code core/name} as on 26.1: {@code shaders/core/name} plus the extension. */
    private static ResourceLocation shaderFile(ResourceLocation id, String extension) {
        return id.withPath(path -> "shaders/" + path + extension);
    }

    private static String read(ResourceManager resources, ResourceLocation file) throws IOException {
        Resource resource = resources.getResourceOrThrow(file);
        try (InputStream in = resource.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String withDefines(String source, Map<String, String> defines) {
        if (defines.isEmpty()) return source;
        StringBuilder lines = new StringBuilder();
        defines.forEach((k, v) -> lines.append("#define ").append(k).append(v.isEmpty() ? "" : " " + v).append('\n'));
        int version = source.indexOf("#version");
        if (version < 0) return lines + source;
        int end = source.indexOf('\n', version);
        return end < 0 ? source + "\n" + lines : source.substring(0, end + 1) + lines + source.substring(end + 1);
    }

    private static boolean uses(String source, String word) {
        return Pattern.compile("\\b(" + word + ")\\b").matcher(source).find();
    }

    /** The uniforms {@code usage} needs, 26.1's fog names counted as 1.21.1's fog. */
    private static Set<String> uniformsUsed(String usage) {
        Set<String> used = new LinkedHashSet<>();
        for (List<String> names : INCLUDE_UNIFORMS.values()) for (String n : names) if (uses(usage, n)) used.add(n);
        if (uses(usage, "FogColor")) used.add("FogColor");
        if (uses(usage, FOG_NAMES)) {
            used.add("FogStart");
            used.add("FogEnd");
        }
        if (uses(usage, "apply_fog|total_fog_value")) used.add("FogShape");
        return used;
    }

    private static @Nullable String include(String file, String usage) {
        Set<String> used = uniformsUsed(usage);
        StringBuilder out = new StringBuilder();
        List<String> declares = new ArrayList<>(INCLUDE_UNIFORMS.getOrDefault(file, List.of()));
        switch (file) {
            case "dynamictransforms.glsl", "globals.glsl" -> {}
            case "projection.glsl" -> out.append("""
                    vec4 projection_from_position(vec4 position) {
                        vec4 projection = position * 0.5;
                        projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
                        projection.zw = position.zw;
                        return projection;
                    }
                    """);
            case "fog.glsl" -> {
                declares = List.of("FogColor", "FogStart", "FogEnd", "FogShape");
                out.append("""
                        #define FogEnvironmentalStart FogStart
                        #define FogEnvironmentalEnd FogEnd
                        #define FogRenderDistanceStart FogStart
                        #define FogRenderDistanceEnd FogEnd
                        #define FogSkyEnd FogEnd
                        #define FogCloudsEnd FogEnd

                        float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
                            if (vertexDistance <= fogStart) return 0.0;
                            if (vertexDistance >= fogEnd) return 1.0;
                            return (vertexDistance - fogStart) / (fogEnd - fogStart);
                        }

                        float fog_spherical_distance(vec3 pos) {
                            return length(pos);
                        }

                        float fog_cylindrical_distance(vec3 pos) {
                            return max(length(pos.xz), abs(pos.y));
                        }
                        """);
                if (used.contains("FogShape")) out.append("""

                        // 1.21.1 has one fog, of one shape: the distance of that shape counts.
                        float total_fog_value(float sphericalDistance, float cylindricalDistance, float start, float end, float renderStart, float renderEnd) {
                            return linear_fog_value(FogShape == 0 ? sphericalDistance : cylindricalDistance, start, end);
                        }

                        vec4 apply_fog(vec4 inColor, float sphericalDistance, float cylindricalDistance, float start, float end, float renderStart, float renderEnd, vec4 fogColor) {
                            float fog = total_fog_value(sphericalDistance, cylindricalDistance, start, end, renderStart, renderEnd);
                            return vec4(mix(inColor.rgb, fogColor.rgb, fog * fogColor.a), inColor.a);
                        }
                        """);
            }
            case "sample_lightmap.glsl" -> out.append("""
                    vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
                        return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
                    }
                    """);
            default -> {
                return null;
            }
        }
        StringBuilder head = new StringBuilder();
        for (Uniform u : UNIFORMS) if (declares.contains(u.name()) && used.contains(u.name())) head.append("uniform ").append(u.glsl()).append(' ').append(u.name()).append(";\n");
        return head.append(out).toString();
    }

    private static String programJson(ResourceLocation name, List<String> samplers, String usage) {
        JsonObject json = new JsonObject();
        json.addProperty("vertex", name.toString());
        json.addProperty("fragment", name.toString());
        JsonArray samplerArray = new JsonArray();
        for (String s : samplers) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s);
            samplerArray.add(o);
        }
        json.add("samplers", samplerArray);
        JsonArray uniformArray = new JsonArray();
        Set<String> used = uniformsUsed(usage);
        for (Uniform u : UNIFORMS) {
            if (!used.contains(u.name())) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", u.name());
            o.addProperty("type", u.type());
            o.addProperty("count", u.count());
            JsonArray values = new JsonArray();
            for (float v : u.values()) values.add(v);
            o.add("values", values);
            uniformArray.add(o);
        }
        json.add("uniforms", uniformArray);
        return json.toString();
    }

    private static Resource text(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Resource(Minecraft.getInstance().getVanillaPackResources(), () -> new ByteArrayInputStream(bytes));
    }
}
