package dev.logos.client;

import dev.logos.lang.Element;

/**
 * How the shared field ({@link FieldCore}) is coloured and dressed: everything an element changes without
 * drawing anything of its own. Colours are opaque RGB, the same three as the element's charge
 * ({@link ChargeStyle}): {@code light} the hot centre, {@code main} the element's colour, {@code deep} the
 * darker shade. Textures are paths under {@code textures/vfx/} (white masks, tinted here):
 * <ul>
 * <li>{@code outer}, {@code middle}, {@code inner}: the three magic circles, from the full radius inwards;</li>
 * <li>{@code rim}: a thin ring laid at the full radius; {@code clock}: the small circle that goes round once;</li>
 * <li>{@code curtain}: the low wall of light at the edge (tileable, scrolled upwards);</li>
 * <li>{@code runes} with {@code runeCols} x {@code runeRows} frames: the runes floating over the circles.</li>
 * </ul>
 * The circles' lines are filled by the fragment shader {@code lineShader} (a {@code logos:} id under
 * {@code shaders/core/}, paired with the vertex stage {@code core/field_line}) sampling {@code fill}, a
 * tileable texture the shader flows along the lines; see {@code field_line.fsh} for what it is given.
 */
public record FieldStyle(int light, int main, int deep, String outer, String middle, String inner, String rim, String clock,
        String curtain, String runes, int runeCols, int runeRows, String lineShader, String fill) {

    /** The element's charge colours with the shared circles, curtain, runes and line fill. */
    public static FieldStyle of(Element e) {
        ChargeStyle c = ChargeStyle.of(e);
        return new FieldStyle(c.light(), c.main(), c.deep(), "charge/circle_18", "charge/circle_11", "charge/circle_17",
                "void_ray/ring", "charge/circle_16", "charge/pillar", "arcane_ray/runes", 3, 3, "core/field_line", "ward/noise");
    }

    public FieldStyle withColours(int light, int main, int deep) {
        return new FieldStyle(light, main, deep, outer, middle, inner, rim, clock, curtain, runes, runeCols, runeRows, lineShader, fill);
    }

    public FieldStyle withCircles(String outer, String middle, String inner, String clock) {
        return new FieldStyle(light, main, deep, outer, middle, inner, rim, clock, curtain, runes, runeCols, runeRows, lineShader, fill);
    }

    public FieldStyle withCurtain(String curtain) {
        return new FieldStyle(light, main, deep, outer, middle, inner, rim, clock, curtain, runes, runeCols, runeRows, lineShader, fill);
    }

    public FieldStyle withRunes(String runes, int cols, int rows) {
        return new FieldStyle(light, main, deep, outer, middle, inner, rim, clock, curtain, runes, cols, rows, lineShader, fill);
    }

    /** The circles' lines filled by {@code shader} (under {@code shaders/core/}) flowing {@code fill}. */
    public FieldStyle withLines(String shader, String fill) {
        return new FieldStyle(light, main, deep, outer, middle, inner, rim, clock, curtain, runes, runeCols, runeRows, shader, fill);
    }
}
