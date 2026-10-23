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

    /** The construct keywords that only some shapes use: their sizes, and the shield's magic-circle face. */
    public static final java.util.Set<String> SHAPED = java.util.Set.of("width", "height", "depth", "radius", "sigil");

    /** Whether this shape uses that keyword of {@link #SHAPED}; the editor hides the rest. */
    public boolean uses(String keyword) {
        return switch (this) {
            case BOX -> keyword.equals("width") || keyword.equals("height") || keyword.equals("depth");
            case WALL -> keyword.equals("width") || keyword.equals("height");
            case PLANE -> keyword.equals("width") || keyword.equals("depth");
            case DISK -> keyword.equals("radius") || keyword.equals("sigil");
            case DOME -> keyword.equals("radius");
        };
    }

    public static ConstructShape parse(String s) {
        for (ConstructShape v : values()) if (v.word().equals(s)) return v;
        return null;
    }
}
