package dev.logos.client;

import dev.logos.lang.Element;

/**
 * The fire vein's two looks (see VEIN_VFX.md): passing over the ground, and what the ground keeps when it lingers.
 * Passing is still the shared tinted look; lingering thrusts up crystals of fire ({@link FireLingeringVeinVisual}).
 */
final class FireVeins {
    private FireVeins() {}

    /** The look of a head of a fire vein: of one that leaves what it crawled over behind if it {@code lingers}. */
    static VeinVisual create(boolean lingers) {
        return lingers ? new FireLingeringVeinVisual() : VeinVisual.tinted(Element.FIRE, false);
    }
}
