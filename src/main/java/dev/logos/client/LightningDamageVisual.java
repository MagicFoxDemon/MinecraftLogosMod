package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

/** Lightning damage: the shared hit core in electric blue; this element's own accents go here. */
final class LightningDamageVisual implements EffectBurstVisual {
    private static final List<ParticleEmitter> LAYERS = DamageBurstCore.layers(0x6AAEFF, 0xBCEBFF);

    @Override
    public List<ParticleEmitter> layers() { return LAYERS; }
}
