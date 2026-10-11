package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.world.phys.Vec3;

/** Physical flight visuals; currently retains the original effect, ready for an isolated upgrade. */
final class PhysicalProjectileVisual extends LegacyProjectileVisual {
    PhysicalProjectileVisual(Vec3 position, double now) { super(Element.PHYSICAL); }
}
