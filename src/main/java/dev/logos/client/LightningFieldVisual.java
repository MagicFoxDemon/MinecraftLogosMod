package dev.logos.client;

import dev.logos.lang.Element;

/** The lightning field: the shared field in lightning's colours, until the lightning branch adds its own (see FIELD_VFX.md). */
final class LightningFieldVisual extends FieldCore {
    LightningFieldVisual() {
        super(FieldStyle.of(Element.LIGHTNING));
    }
}
