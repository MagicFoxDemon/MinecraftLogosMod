package dev.logos.client;

import dev.logos.lang.Element;

/** The void field: the shared field in void's colours, until the void branch adds its own (see FIELD_VFX.md). */
final class VoidFieldVisual extends FieldCore {
    VoidFieldVisual() {
        super(FieldStyle.of(Element.VOID));
    }
}
