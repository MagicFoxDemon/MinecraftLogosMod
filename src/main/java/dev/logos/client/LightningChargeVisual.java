package dev.logos.client;

import dev.logos.lang.Element;

/** The lightning charge: the shared charge in lightning's colours, until the lightning branch adds its own layers (see CHARGE_VFX.md). */
final class LightningChargeVisual extends ChargeCore {
    LightningChargeVisual() {
        super(ChargeStyle.of(Element.LIGHTNING));
    }
}
