package dev.logos.lang;

import java.util.Locale;
import java.util.Set;

/**
 * Mercury runes describing how a spell body travels. Plugged into a form's {@code :motion} slot. A projectile flies and a
 * vein crawls over the ground: forward, homing and wander suit both, the rest only one of them ({@link #flies},
 * {@link #crawls}). Appended only: clients read a trail's motion by its ordinal.
 */
public enum Motion {
    FORWARD("直行", 0, 0),
    ARC("抛物", 0, 1),
    HOMING("追踪", 3, 3),
    ORBIT("环绕", 2, 3),
    WANDER("游走", 1, 1),
    BOOMERANG("折返", 2, 2),
    /** A vein swaying left and right like a snake, its heading kept overall. */
    SNAKE("蛇行", 1, 1),
    /** A vein spreading out from its start every way at once, or over {@code :spread} degrees of a round. */
    RING("环扩", 2, 2),
    /** A vein sweeping round an arc of {@code :spread} degrees at {@code :radius} about {@code :from}, clockwise unless {@code :ccw}. */
    SWEEP("弧扫", 1, 1);

    public final String zh;
    public final double mana;
    public final double stability;

    Motion(String zh, double mana, double stability) {
        this.zh = zh;
        this.mana = mana;
        this.stability = stability;
    }

    /** Whether a projectile can travel this way. */
    public boolean flies() {
        return ordinal() <= BOOMERANG.ordinal();
    }

    /** Whether a vein can: it keeps to the ground, where an arc, an orbit or a return mean nothing. */
    public boolean crawls() {
        return this == FORWARD || this == HOMING || this == WANDER || this.ordinal() > BOOMERANG.ordinal();
    }

    /** The vein keywords that only some motions use; the editor hides the rest, as it does a construct's sizes. */
    public static final Set<String> SHAPED = Set.of("range", "spread", "radius", "ccw");

    /** Whether a vein crawling this way uses that keyword of {@link #SHAPED}: a sweep goes round its arc instead of a range. */
    public boolean uses(String keyword) {
        return switch (this) {
            case RING -> keyword.equals("range") || keyword.equals("spread");
            case SWEEP -> keyword.equals("spread") || keyword.equals("radius") || keyword.equals("ccw");
            default -> keyword.equals("range");
        };
    }

    public static Motion parse(String s) {
        for (Motion m : values()) {
            if (m.name().toLowerCase(Locale.ROOT).equals(s)) return m;
        }
        return null;
    }
}
