package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import dev.logos.lang.Element;

/** Element-local collision and expiry layers, separate from flight and generic damage bursts. */
interface ProjectileImpactVisual {
    /** Empty until upgraded; the element retains its existing end burst. */
    default List<ParticleEmitter> impact() { return List.of(); }
    default List<ParticleEmitter> expiry() { return List.of(); }
    default float scale(float radius) { return Math.min(1.25f, Math.max(.65f, radius / .3f)); }

    static ProjectileImpactVisual create(Element element) {
        return switch (element) {
            case FIRE -> new FireProjectileImpactVisual();
            case FROST -> new FrostProjectileImpactVisual();
            case LIGHTNING -> new LightningProjectileImpactVisual();
            case VOID -> new VoidProjectileImpactVisual();
            case PHYSICAL -> new PhysicalProjectileImpactVisual();
            case ARCANE -> new ArcaneProjectileImpactVisual();
            case LIFE -> new LifeProjectileImpactVisual();
        };
    }
}
