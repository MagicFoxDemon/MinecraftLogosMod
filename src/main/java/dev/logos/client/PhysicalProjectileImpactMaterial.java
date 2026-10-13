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

/** Capsule's distorted spiral mapped onto a static annulus, with crisp white-gold edges. */
@LDLRegisterClient(name = "logos_physical_impact_spiral", registry = "photon:material")
public final class PhysicalProjectileImpactMaterial extends TextureMaterial {
    private static final Map<VertexFormat.Mode, RenderType> TYPES = new EnumMap<>(VertexFormat.Mode.class);

    public PhysicalProjectileImpactMaterial() { super(id("textures/vfx/physical_impact/spiral_noise.png")); }

    @Override
    public IMaterial copy() { return new PhysicalProjectileImpactMaterial(); }

    @Override
    public RenderType getRenderType(MaterialSetting setting, VertexFormat.Mode mode) {
        return TYPES.computeIfAbsent(mode, key -> {
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/physical_impact_spiral_" + key.name().toLowerCase(java.util.Locale.ROOT)))
                    .withVertexShader(id("core/physical_impact_spiral"))
                    .withFragmentShader(id("core/physical_impact_spiral"))
                    .withVertexFormat(PhotonPipelines.PARTICLE_FORMAT, key)
                    .withSampler("Sampler1").withSampler("Sampler2").withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
            return RenderType.create("logos_physical_impact_spiral", RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", id("textures/vfx/physical_impact/spiral_noise.png"))
                    .withTexture("Sampler1", id("textures/vfx/physical_impact/spiral_distortion.png"))
                    .withTexture("Sampler2", id("textures/vfx/physical_impact/spiral_mask.png"))
                    .sortOnUpload().createRenderSetup());
        });
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
