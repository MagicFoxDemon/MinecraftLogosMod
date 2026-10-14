package dev.logos.client;

import java.util.List;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;

/**
 * What a {@code BURST} looks like: chosen by the act ({@link Vfx.Burst}) first, then by the element, so a
 * hit reads differently from other effects. Separate from projectile impacts, which only show the
 * collision. Local +Z points away from where the effect came from. An empty list keeps the old generic burst.
 */
interface EffectBurstVisual {
    EffectBurstVisual GENERIC = new EffectBurstVisual() {};

    default List<ParticleEmitter> layers() { return List.of(); }

    /** {@code size} is the target's scale from the server (about 1 for a player). */
    default float scale(float size) { return Math.min(2, Math.max(.6f, size)); }

    static EffectBurstVisual create(Vfx.Burst what, Element element) {
        return switch (what) {
            case DAMAGE -> switch (element) {
                case PHYSICAL -> new PhysicalDamageVisual();
                case FIRE -> new FireDamageVisual();
                case FROST -> new FrostDamageVisual();
                case LIGHTNING -> new LightningDamageVisual();
                case VOID -> new VoidDamageVisual();
                // Arcane and life never deal damage.
                case ARCANE, LIFE -> GENERIC;
            };
            case GENERIC -> GENERIC;
        };
    }
}
