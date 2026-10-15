package dev.logos.client;

import dev.logos.lang.Element;

/**
 * What a lasting {@code beam} leaves along its line when it ends, fading like a ray's wake. It is a
 * {@link RayVisual}: {@link LogosFx} calls {@code start} with the line as last drawn (and {@code OPEN}),
 * then drives it like a ray. Each element's wake reports {@code legacy()} until its branch draws one, and
 * the beam then simply ends as before.
 */
final class BeamWake {
    private BeamWake() {}

    static RayVisual create(Element element) {
        return switch (element) {
            case FIRE -> new FireBeamWake();
            case FROST -> new FrostBeamWake();
            case LIGHTNING -> new LightningBeamWake();
            case VOID -> new VoidBeamWake();
            case PHYSICAL -> new PhysicalBeamWake();
            case ARCANE -> new ArcaneBeamWake();
            case LIFE -> new LifeBeamWake();
        };
    }
}
