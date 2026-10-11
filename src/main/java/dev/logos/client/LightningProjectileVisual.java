package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.world.phys.Vec3;

/** Lightning flight visuals; currently retains the original effect, ready for an isolated upgrade. */
final class LightningProjectileVisual extends LegacyProjectileVisual {
    LightningProjectileVisual(Vec3 position, double now) { super(Element.LIGHTNING); }
}
