package dev.logos.client;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import net.minecraft.resources.Identifier;

/**
 * The void damage rift: a starfield seen through a torn pocket on the struck body. Its own material and
 * shader, independent of the projectile impact's. A Photon custom shader, so Photon draws it and handles
 * shader packs like its own materials.
 */
@LDLRegisterClient(name = "logos_void_damage_rift", registry = "photon:material")
public final class VoidDamageMaterial extends CustomShaderMaterial {
    public VoidDamageMaterial() {
        super(Identifier.fromNamespaceAndPath("logos", "void_damage_rift"));
        PhotonShaderTextures.bind(this,
                "RiftMask", "logos:textures/vfx/void_damage/rift.png",
                "StarTexture", "minecraft:textures/entity/end_portal/end_portal.png");
    }

    @Override
    public IMaterial copy() { return new VoidDamageMaterial(); }
}
