package dev.logos.client;

import dev.logos.lang.Element;

/** The life field: the shared field in life's colours, until the life branch adds its own (see FIELD_VFX.md). */
final class LifeFieldVisual extends FieldCore {
    LifeFieldVisual() {
        super(FieldStyle.of(Element.LIFE));
    }
}
