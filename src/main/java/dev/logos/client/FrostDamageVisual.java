package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

/** Frost damage: the shared hit core in ice blue; this element's own accents go here. */
final class FrostDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = DamageBurstCore.layers(0x8CCBFF, 0xDCF4FF);

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }
}
