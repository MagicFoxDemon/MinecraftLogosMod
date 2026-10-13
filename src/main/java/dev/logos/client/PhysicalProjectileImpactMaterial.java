package dev.logos.client;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import net.minecraft.resources.Identifier;

/**
 * Capsule's distorted spiral mapped onto a static annulus, with crisp white-gold edges.
 * A Photon custom shader, so Photon draws it and handles shader packs like its own materials.
 */
@LDLRegisterClient(name = "logos_physical_impact_spiral", registry = "photon:material")
public final class PhysicalProjectileImpactMaterial extends CustomShaderMaterial {
    public PhysicalProjectileImpactMaterial() {
        super(Identifier.fromNamespaceAndPath("logos", "physical_impact_spiral"));
        PhotonShaderTextures.bind(this,
                "SpiralNoise", "logos:textures/vfx/physical_impact/spiral_noise.png",
                "SpiralDistortion", "logos:textures/vfx/physical_impact/spiral_distortion.png",
                "SpiralMask", "logos:textures/vfx/physical_impact/spiral_mask.png");
    }

    @Override
    public IMaterial copy() { return new PhysicalProjectileImpactMaterial(); }
}
