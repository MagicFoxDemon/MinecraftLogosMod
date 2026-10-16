package dev.logos.client;

import dev.logos.lang.Element;

/** The fire field: the shared field in fire's colours, until the fire branch adds its own (see FIELD_VFX.md). */
final class FireFieldVisual extends FieldCore {
    FireFieldVisual() {
        super(FieldStyle.of(Element.FIRE));
    }
}
