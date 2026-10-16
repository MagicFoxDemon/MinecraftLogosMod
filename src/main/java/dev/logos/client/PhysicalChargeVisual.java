package dev.logos.client;

import dev.logos.lang.Element;

/** The physical charge: the shared charge in physical's colours, until the physical branch adds its own layers (see CHARGE_VFX.md). */
final class PhysicalChargeVisual extends ChargeCore {
    PhysicalChargeVisual() {
        super(ChargeStyle.of(Element.PHYSICAL));
    }
}
