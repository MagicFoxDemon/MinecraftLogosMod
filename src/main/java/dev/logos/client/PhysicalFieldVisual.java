package dev.logos.client;

import dev.logos.lang.Element;

/** The physical field: the shared field in physical's colours, until the physical branch adds its own (see FIELD_VFX.md). */
final class PhysicalFieldVisual extends FieldCore {
    PhysicalFieldVisual() {
        super(FieldStyle.of(Element.PHYSICAL));
    }
}
