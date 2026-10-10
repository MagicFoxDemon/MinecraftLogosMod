package dev.logos.lang;

import java.util.Locale;

public enum Element {
    PHYSICAL("物理", 0.8),
    FIRE("火焰", 1.0),
    FROST("寒霜", 1.0),
    LIGHTNING("雷霆", 1.1),
    VOID("虚空", 1.5),
    // Not writable by players; inferred for visuals only.
    ARCANE("奥秘", 1.0),
    LIFE("生命", 1.0);

    public final String zh;
    /** Mana cost per point of damage. */
    public final double costFactor;

    Element(String zh, double costFactor) {
        this.zh = zh;
        this.costFactor = costFactor;
    }

    public static Element parse(String s) {
        for (Element e : values()) {
            if (e.ordinal() <= VOID.ordinal() && e.name().toLowerCase(Locale.ROOT).equals(s)) return e;
        }
        return null;
    }
}
