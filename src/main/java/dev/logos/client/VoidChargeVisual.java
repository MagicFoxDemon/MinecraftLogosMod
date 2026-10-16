package dev.logos.client;

import dev.logos.lang.Element;

/** The void charge: the shared charge in void's colours, until the void branch adds its own layers (see CHARGE_VFX.md). */
final class VoidChargeVisual extends ChargeCore {
    VoidChargeVisual() {
        super(ChargeStyle.of(Element.VOID));
    }
}
