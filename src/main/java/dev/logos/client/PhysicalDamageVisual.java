package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

/** Physical damage: the shared hit core in white gold; this element's own accents go here. */
final class PhysicalDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = DamageBurstCore.layers(0xFFF0C6, 0xFFFFF5);

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }
}
