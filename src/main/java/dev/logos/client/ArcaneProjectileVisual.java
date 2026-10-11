package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.world.phys.Vec3;

/** Arcane flight visuals; currently retains the original effect, ready for an isolated upgrade. */
final class ArcaneProjectileVisual extends LegacyProjectileVisual {
    ArcaneProjectileVisual(Vec3 position, double now) { super(Element.ARCANE); }
}
