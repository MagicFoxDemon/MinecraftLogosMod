package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.world.phys.Vec3;

/** Void flight visuals; currently retains the original effect, ready for an isolated upgrade. */
final class VoidProjectileVisual extends LegacyProjectileVisual {
    VoidProjectileVisual(Vec3 position, double now) { super(Element.VOID); }
}
