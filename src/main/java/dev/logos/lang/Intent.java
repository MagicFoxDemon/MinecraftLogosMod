package dev.logos.lang;

/**
 * What a spell is for, as a creature that casts it understands it. A mana lifeform chooses its
 * pages by these: it attacks with STRIKE / AREA / CONTROL pages, heals its master with HEAL pages,
 * guards with WARD pages. Inferred from the runes a spell uses, or declared with {@code :intent}.
 */
public enum Intent {
    STRIKE("strike", "单体攻击", true),
    AREA("area", "范围攻击", true),
    CONTROL("control", "控制", true),
    HEAL("heal", "治疗", false),
    WARD("ward", "守护", false),
    MOVE("move", "位移", false),
    UTILITY("utility", "杂务", false);

    /** How it is written: {@code :intent heal}. */
    public final String word;
    public final String zh;
    /** Aimed at enemies (true) or for its own side (false). */
    public final boolean hostile;

    Intent(String word, String zh, boolean hostile) {
        this.word = word;
        this.zh = zh;
        this.hostile = hostile;
    }

    public static Intent parse(String s) {
        for (Intent i : values()) if (i.word.equals(s)) return i;
        return null;
    }

    public static String words() {
        StringBuilder sb = new StringBuilder();
        for (Intent i : values()) sb.append(sb.isEmpty() ? "" : " / ").append(i.word);
        return sb.toString();
    }
}
