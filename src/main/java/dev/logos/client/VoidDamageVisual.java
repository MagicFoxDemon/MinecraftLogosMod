package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

/** Void damage: the shared hit core in violet; this element's own accents go here. */
final class VoidDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = DamageBurstCore.layers(0x9A28FF, 0xD5A8FF);

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }
}
