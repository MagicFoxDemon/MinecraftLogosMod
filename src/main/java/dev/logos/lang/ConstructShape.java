package dev.logos.lang;

import java.util.Locale;

/** The shape a {@code construct} takes. Plugged into its {@code :shape} slot like a motion. */
public enum ConstructShape {
    /** A solid block of width × height × depth. */
    BOX("方块"),
    /** An upright wall, width × height, facing along {@code :facing}. */
    WALL("墙"),
    /** A level floor, width × depth: a platform, a bridge, a magic circle to stand on. */
    PLANE("平台"),
    /** A round shield of {@code :radius}, facing exactly along {@code :facing} (it may tilt). */
    DISK("圆盾"),
    /** A half-sphere shell of {@code :radius} over {@code :at}. */
    DOME("穹顶");

    public final String zh;

    ConstructShape(String zh) {
        this.zh = zh;
    }

    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConstructShape parse(String s) {
        for (ConstructShape v : values()) if (v.word().equals(s)) return v;
        return null;
    }
}
