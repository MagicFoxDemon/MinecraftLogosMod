package dev.logos.lang;

import java.util.Set;

/**
 * Whom a healing or warding spell can reach: its caster, one chosen creature, or many at once. A spell may
 * reach several ways (a field of healing around its caster reaches the caster and the crowd), so a
 * profile keeps a set of these. Inferred from whom the heal or shield is written on, or declared with
 * {@code :reach}. A lifeform heals its master only with pages that reach ONE or GROUP, and itself only
 * with pages that reach SELF.
 */
public enum Reach {
    SELF("self", "自己"),
    ONE("one", "单体"),
    GROUP("group", "群体");

    /** How it is written: {@code :reach self+group}. */
    public final String word;
    public final String zh;

    Reach(String word, String zh) {
        this.word = word;
        this.zh = zh;
    }

    public static Reach parse(String s) {
        for (Reach r : values()) if (r.word.equals(s)) return r;
        return null;
    }

    public static String words() {
        StringBuilder sb = new StringBuilder();
        for (Reach r : values()) sb.append(sb.isEmpty() ? "" : " / ").append(r.word);
        return sb.toString();
    }

    /** The set in reading order, joined by {@code sep}, in Chinese. */
    public static String zh(Set<Reach> reach, String sep) {
        StringBuilder sb = new StringBuilder();
        for (Reach r : values()) if (reach.contains(r)) sb.append(sb.isEmpty() ? "" : sep).append(r.zh);
        return sb.toString();
    }

    /** The set as {@code :reach} takes it: {@code self+group}. */
    public static String written(Set<Reach> reach) {
        StringBuilder sb = new StringBuilder();
        for (Reach r : values()) if (reach.contains(r)) sb.append(sb.isEmpty() ? "" : "+").append(r.word);
        return sb.toString();
    }
}
