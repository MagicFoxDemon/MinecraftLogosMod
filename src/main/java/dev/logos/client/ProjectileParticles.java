package dev.logos.client;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import net.minecraft.resources.Identifier;

/** Shared RGBA-mask defaults; element colors, rates, sizes and texture choices belong to each visual. */
final class ProjectileParticles {
    private ProjectileParticles() {}

    static void configureTexture(ParticleEmitter emitter, String texture, boolean additive) {
        ParticleConfig c = emitter.config;
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xD0FFFFFF, 0x00FFFFFF)));
        Curve taper = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .2f, 1, .65f, .65f, 1, 0));
        c.sizeOverLifetime.setEnable(true);
        c.sizeOverLifetime.setSize(new NumberFunction3(taper, taper, taper));
        c.setMaxParticles(48);
        for (var material : c.renderer.getMaterials()) {
            TextureMaterial image = new TextureMaterial(Identifier.fromNamespaceAndPath("logos", "textures/vfx/" + texture + ".png"));
            image.setDiscardThreshold(.005f);
            material.setMaterial(image);
            material.getBlendMode().setSrcColorFactor(SourceFactor.SRC_ALPHA);
            material.getBlendMode().setDstColorFactor(additive ? DestFactor.ONE : DestFactor.ONE_MINUS_SRC_ALPHA);
        }
    }
}
