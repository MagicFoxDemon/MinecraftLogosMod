package dev.logos.client;

import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;

/**
 * Binds textures to a Photon custom shader's samplers from code. Photon reads them from the same
 * {@code shaderData.samplers} entries its editor saves; the lookup argument is unused there.
 */
final class PhotonShaderTextures {
    private PhotonShaderTextures() {}

    /** {@code samplerAndTexture} alternates sampler names (as declared in the shader JSON) and texture ids. */
    static void bind(CustomShaderMaterial material, String... samplerAndTexture) {
        CompoundTag samplers = new CompoundTag();
        for (int i = 0; i < samplerAndTexture.length; i += 2) {
            CompoundTag sampler = new CompoundTag();
            sampler.putString("type", "texture");
            sampler.putString("resource", samplerAndTexture[i + 1]);
            samplers.put(samplerAndTexture[i], sampler);
        }
        CompoundTag shaderData = new CompoundTag();
        shaderData.put("samplers", samplers);
        CompoundTag tag = new CompoundTag();
        tag.put("shaderData", shaderData);
        material.deserializeAdditionalNBT(tag, RegistryAccess.EMPTY);
    }
}
