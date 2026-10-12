package dev.logos.client;

import java.util.EnumMap;
import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** A Photon-local material keeps the impact's starfield independent of flight rendering. */
@LDLRegisterClient(name = "logos_void_impact_starfield", registry = "photon:material")
public final class VoidProjectileImpactMaterial extends TextureMaterial {
    private static final Map<VertexFormat.Mode, RenderType> TYPES = new EnumMap<>(VertexFormat.Mode.class);

    public VoidProjectileImpactMaterial() { super(id("textures/vfx/void_impact/cloud.png")); }

    @Override
    public IMaterial copy() { return new VoidProjectileImpactMaterial(); }

    @Override
    public RenderType getRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
        return TYPES.computeIfAbsent(mode, key -> {
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/void_impact_starfield_" + key.name().toLowerCase(java.util.Locale.ROOT)))
                    .withVertexShader(id("core/void_impact_starfield"))
                    .withFragmentShader(id("core/void_impact_starfield"))
                    .withVertexFormat(PhotonPipelines.PARTICLE_FORMAT, key)
                    .withSampler("Sampler1").withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
            return RenderType.create("logos_void_impact_starfield", RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", id("textures/vfx/void_impact/cloud.png"))
                    .withTexture("Sampler1", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"))
                    .sortOnUpload().createRenderSetup());
        });
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
