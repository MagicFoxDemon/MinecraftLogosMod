package dev.logos.client;

import dev.logos.lang.Element;

/**
 * How the shared charge ({@link ChargeCore}) is coloured and which magic circles it lays down: everything an
 * element's charge changes without drawing anything of its own. Colours are opaque RGB: {@code light} the hot
 * centre of motes, trails and the ball, {@code main} the element's colour, {@code deep} the darker shade of
 * soft glows, the pillar and the outer strips. Circles are texture paths under {@code textures/vfx/} (white
 * masks, tinted here), from the simplest under an elaborate charge to the layers of a colossal one.
 */
public record ChargeStyle(int light, int main, int deep, String elaborate, String grandOuter, String grandInner,
        String colossalOuter, String colossalMiddle, String colossalInner, String raised) {

    /** The circles every element starts from (Unreal MagicCircle 17, 18, 16, 03, 11, 15, 17). */
    public static ChargeStyle colours(int light, int main, int deep) {
        return new ChargeStyle(light, main, deep, "charge/circle_17", "charge/circle_18", "charge/circle_16",
                "charge/circle_03", "charge/circle_11", "charge/circle_15", "charge/circle_17");
    }

    /** The element's palette (that of its projectiles, impacts, rays and beams) with the shared circles. */
    public static ChargeStyle of(Element e) {
        return switch (e) {
            case FIRE -> colours(0xFFF2B0, 0xFF6A10, 0xC02800);
            case FROST -> colours(0xF0FFFF, 0x38B6FF, 0x1C5CC8);
            case LIGHTNING -> colours(0xFFFFFF, 0x6C8CFF, 0x3040D0);
            case VOID -> colours(0xF0C8FF, 0x9A28FF, 0x4A0A9A);
            case PHYSICAL -> colours(0xFFFFFF, 0xD8DCE6, 0x8890A0);
            case ARCANE -> colours(0xFFD8FF, 0xE83CFF, 0x8A1AC0);
            case LIFE -> colours(0xF2FFD8, 0x40E050, 0x1A9A30);
        };
    }

    public ChargeStyle withCircles(String elaborate, String grandOuter, String grandInner, String colossalOuter,
            String colossalMiddle, String colossalInner, String raised) {
        return new ChargeStyle(light, main, deep, elaborate, grandOuter, grandInner, colossalOuter, colossalMiddle, colossalInner, raised);
    }
}
