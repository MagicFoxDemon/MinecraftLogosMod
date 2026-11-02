package dev.logos.client;

import dev.logos.lang.Element;

/**
 * The life vein's two looks (see VEIN_VFX.md): passing over the ground, and what the ground keeps when it lingers.
 * Passing is still the shared tinted look; lingering grows thorny vines ({@link LifeLingeringVeinVisual}).
 */
final class LifeVeins {
    private LifeVeins() {}

    /** The look of a head of a life vein: of one that leaves what it crawled over behind if it {@code lingers}. */
    static VeinVisual create(boolean lingers) {
        return lingers ? new LifeLingeringVeinVisual() : VeinVisual.tinted(Element.LIFE, false);
    }
}
