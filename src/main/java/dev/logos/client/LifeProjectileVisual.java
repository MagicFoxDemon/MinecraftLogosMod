package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.world.phys.Vec3;

/** Life flight visuals; currently retains the original effect, ready for an isolated upgrade. */
final class LifeProjectileVisual extends LegacyProjectileVisual {
    LifeProjectileVisual(Vec3 position, double now) { super(Element.LIFE); }
}
