package dev.logos.client;

import dev.logos.runtime.Vfx;

/**
 * {@code potion}: a puff of potion smoke that appears and is gone, after Piloto's PoisonSkull; poison green
 * for harmful effects, pale pink for beneficial ones, a third neutral colour for the rest (see
 * {@link Vfx#potion}). Not drawn yet, so the generic burst stays.
 */
final class PotionBurstVisual implements EffectBurstVisual {
    final Vfx.Burst kind;

    PotionBurstVisual(Vfx.Burst kind) {
        this.kind = kind;
    }
}
