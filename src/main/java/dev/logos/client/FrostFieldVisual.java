package dev.logos.client;

import dev.logos.lang.Element;

/** The frost field: the shared field in frost's colours, until the frost branch adds its own (see FIELD_VFX.md). */
final class FrostFieldVisual extends FieldCore {
    FrostFieldVisual() {
        super(FieldStyle.of(Element.FROST));
    }
}
