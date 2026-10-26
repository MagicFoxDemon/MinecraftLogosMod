package dev.logos.lang;

import java.util.Locale;

/** Every element deals damage; {@link #writable} says which ones a player may write. Synced by ordinal: append only. */
public enum Element {
    PHYSICAL("物理", 0.8, true),
    // Cheapest: vanilla fire damage, so armour, fire protection, fire resistance and fire immunity all stop it.
    FIRE("火焰", 0.7, true),
    FROST("寒霜", 1.0, true),
    LIGHTNING("雷霆", 1.1, true),
    VOID("虚空", 1.5, true),
    // Also how a spell with no element looks.
    ARCANE("奥秘", 1.0, true),
    // Dealt by other mods' creatures (a dryad), never written by players; heals and shields look like it.
    LIFE("生命", 1.0, false);

    public final String zh;
    /** Mana cost per point of damage. */
    public final double costFactor;
    /** Whether players can pick it, in text or in the graph editor. */
    public final boolean writable;

    Element(String zh, double costFactor, boolean writable) {
        this.zh = zh;
        this.costFactor = costFactor;
        this.writable = writable;
    }

    /** The writable element named {@code s}, or null. */
    public static Element parse(String s) {
        for (Element e : values()) {
            if (e.writable && e.name().toLowerCase(Locale.ROOT).equals(s)) return e;
        }
        return null;
    }
}
