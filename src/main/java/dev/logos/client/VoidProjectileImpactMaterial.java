package dev.logos.client;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import net.minecraft.resources.Identifier;

/**
 * A Photon-local material keeps the impact's starfield independent of flight rendering.
 * A Photon custom shader, so Photon draws it and handles shader packs like its own materials.
 */
@LDLRegisterClient(name = "logos_void_impact_starfield", registry = "photon:material")
public final class VoidProjectileImpactMaterial extends CustomShaderMaterial {
    public VoidProjectileImpactMaterial() {
        super(Identifier.fromNamespaceAndPath("logos", "void_impact_starfield"));
        PhotonShaderTextures.bind(this,
                "CloudMask", "logos:textures/vfx/void_impact/cloud.png",
                "StarTexture", "minecraft:textures/entity/end_portal/end_portal.png");
    }

    @Override
    public IMaterial copy() { return new VoidProjectileImpactMaterial(); }
}
