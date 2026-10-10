package dev.logos.lang;

import java.util.Locale;

/** Mercury runes describing how a spell body travels. Plugged into a form's {@code :motion} slot. */
public enum Motion {
    FORWARD("直行", 0, 0),
    ARC("抛物", 0, 1),
    HOMING("追踪", 3, 3),
    ORBIT("环绕", 2, 3),
    WANDER("游走", 1, 1),
    BOOMERANG("折返", 2, 2);

    public final String zh;
    public final double mana;
    public final double stability;

    Motion(String zh, double mana, double stability) {
        this.zh = zh;
        this.mana = mana;
        this.stability = stability;
    }

    public static Motion parse(String s) {
        for (Motion m : values()) {
            if (m.name().toLowerCase(Locale.ROOT).equals(s)) return m;
        }
        return null;
    }
}
