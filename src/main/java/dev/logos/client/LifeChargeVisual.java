package dev.logos.client;

import dev.logos.lang.Element;

/** The life charge: the shared charge in life's colours, until the life branch adds its own layers (see CHARGE_VFX.md). */
final class LifeChargeVisual extends ChargeCore {
    LifeChargeVisual() {
        super(ChargeStyle.of(Element.LIFE));
    }
}
