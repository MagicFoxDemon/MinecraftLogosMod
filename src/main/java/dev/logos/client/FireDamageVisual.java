package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

/** Fire damage: the shared hit core in flame orange; this element's own accents go here. */
final class FireDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = DamageBurstCore.layers(0xFF9D22, 0xFFE2AB);

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }
}
