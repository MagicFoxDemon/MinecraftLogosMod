package dev.logos.client;

import dev.logos.lang.Element;

/**
 * The lightning vein's two looks (see VEIN_VFX.md): passing over the ground, and what the ground keeps when it lingers.
 * Both are still the shared tinted ones.
 */
final class LightningVeins {
    private LightningVeins() {}

    /** The look of a head of a lightning vein: of one that leaves what it crawled over behind if it {@code lingers}. */
    static VeinVisual create(boolean lingers) {
        return VeinVisual.tinted(Element.LIGHTNING, lingers);
    }
}
