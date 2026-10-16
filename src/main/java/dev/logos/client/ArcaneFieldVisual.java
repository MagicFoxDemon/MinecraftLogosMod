package dev.logos.client;

import dev.logos.lang.Element;

/** The arcane field: the shared field in arcane's colours, until the arcane branch adds its own (see FIELD_VFX.md). */
final class ArcaneFieldVisual extends FieldCore {
    ArcaneFieldVisual() {
        super(FieldStyle.of(Element.ARCANE));
    }
}
