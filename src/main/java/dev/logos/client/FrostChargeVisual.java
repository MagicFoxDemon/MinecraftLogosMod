package dev.logos.client;

import dev.logos.lang.Element;

/** The frost charge: the shared charge in frost's colours, until the frost branch adds its own layers (see CHARGE_VFX.md). */
final class FrostChargeVisual extends ChargeCore {
    FrostChargeVisual() {
        super(ChargeStyle.of(Element.FROST));
    }
}
