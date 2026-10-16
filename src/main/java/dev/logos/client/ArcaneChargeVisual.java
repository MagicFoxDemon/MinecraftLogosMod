package dev.logos.client;

import dev.logos.lang.Element;

/** The arcane charge: the shared charge in arcane's colours, until the arcane branch adds its own layers (see CHARGE_VFX.md). */
final class ArcaneChargeVisual extends ChargeCore {
    ArcaneChargeVisual() {
        super(ChargeStyle.of(Element.ARCANE));
    }
}
