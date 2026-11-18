package dev.logos.client.port;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Stands in for 26.1's {@code RenderType.create(name, renderSetup)}: a 1.21.1 render type drawing with a
 * {@link RenderSetup}'s pipeline (compiled by {@link ShaderPrograms}), textures and state. It extends
 * {@link RenderType} only to reach the shared state shards.
 */
public final class RenderTypes extends RenderType {
    private static final Pattern NUMBERED_SAMPLER = Pattern.compile("Sampler(\\d+)");
    private static final int BUFFER_SIZE = 1536;

    private RenderTypes(String name) {
        super(name, null, null, 0, false, false, () -> {}, () -> {});
    }

    public static RenderType create(String name, RenderSetup setup) {
        RenderPipeline p = setup.pipeline;
        CompositeState state = CompositeState.builder()
                .setShaderState(new ShaderStateShard(() -> ShaderPrograms.get(p)))
                .setTextureState(textures(p, setup.textures))
                .setTransparencyState(transparency(p))
                .setDepthTestState(depthTest(p.depth))
                .setCullState(p.cull ? CULL : NO_CULL)
                .setWriteMaskState(writeMask(p))
                .setLightmapState(setup.lightmap ? LIGHTMAP : NO_LIGHTMAP)
                .createCompositeState(false);
        return RenderType.create(name, p.format, p.mode, BUFFER_SIZE, false, setup.sortOnUpload, state);
    }

    /** {@code SamplerN} through the render system, as vanilla binds them; any other name straight onto the pipeline's shader. */
    private static EmptyTextureStateShard textures(RenderPipeline p, Map<String, ResourceLocation> textures) {
        return new EmptyTextureStateShard(() -> {
            var manager = Minecraft.getInstance().getTextureManager();
            for (var e : textures.entrySet()) {
                Matcher m = NUMBERED_SAMPLER.matcher(e.getKey());
                if (m.matches()) RenderSystem.setShaderTexture(Integer.parseInt(m.group(1)), e.getValue());
                else ShaderPrograms.get(p).setSampler(e.getKey(), manager.getTexture(e.getValue()));
            }
        }, () -> {});
    }

    private static TransparencyStateShard transparency(RenderPipeline p) {
        var blend = p.color.blendFunction();
        if (blend.isEmpty()) return NO_TRANSPARENCY;
        BlendFunction b = blend.get();
        return new TransparencyStateShard("logos_blend_" + b.sourceColor() + "_" + b.destColor() + "_" + b.sourceAlpha() + "_" + b.destAlpha(), () -> {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(b.sourceColor(), b.destColor(), b.sourceAlpha(), b.destAlpha());
        }, () -> {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        });
    }

    private static DepthTestStateShard depthTest(DepthStencilState depth) {
        return switch (depth.depthTest()) {
            case ALWAYS_PASS -> NO_DEPTH_TEST;
            case LESS_THAN_OR_EQUAL -> LEQUAL_DEPTH_TEST;
            case EQUAL -> EQUAL_DEPTH_TEST;
            default -> new DepthTestStateShard(depth.depthTest().name().toLowerCase(java.util.Locale.ROOT), depth.depthTest().gl);
        };
    }

    private static WriteMaskStateShard writeMask(RenderPipeline p) {
        boolean color = (p.color.writeMask() & ColorTargetState.WRITE_COLOR) != 0;
        if (p.depth.writeDepth()) return color ? COLOR_DEPTH_WRITE : DEPTH_WRITE;
        return COLOR_WRITE;
    }

}
