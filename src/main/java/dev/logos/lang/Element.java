package dev.logos.lang;

import java.util.Locale;

/**
 * Every element deals damage. Which ones a player may write is what they have unlocked ({@link Unlocks}); life is
 * never in a random pool, so in Logos alone only a command gives it. Synced by ordinal: append only.
 */
public enum Element {
    PHYSICAL("物理", 0.8),
    // Cheapest: vanilla fire damage, so armour, fire protection, fire resistance and fire immunity all stop it.
    FIRE("火焰", 0.7),
    FROST("寒霜", 1.0),
    LIGHTNING("雷霆", 1.1),
    // Dearest: true damage (see Effects.VOID), which almost nothing turns aside.
    VOID("虚空", 1.5),
    // Also how a spell with no element looks.
    ARCANE("奥秘", 1.0),
    // Dealt by other mods' creatures (a dryad); heals and shields look like it.
    LIFE("生命", 1.0);

    public final String zh;
    /** Mana cost per point of damage. */
    public final double costFactor;

    Element(String zh, double costFactor) {
        this.zh = zh;
        this.costFactor = costFactor;
    }

    /** The element named {@code s}, or null. */
    public static Element parse(String s) {
        for (Element e : values()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(s)) return e;
        }
        return null;
    }
}
