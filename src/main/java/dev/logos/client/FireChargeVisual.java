package dev.logos.client;

import dev.logos.lang.Element;

/** The fire charge: the shared charge in fire's colours, until the fire branch adds its own layers (see CHARGE_VFX.md). */
final class FireChargeVisual extends ChargeCore {
    FireChargeVisual() {
        super(ChargeStyle.of(Element.FIRE));
    }
}
