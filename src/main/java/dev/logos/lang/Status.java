package dev.logos.lang;

/**
 * A status reference: either a built-in condition ({@code burning}, {@code frozen}) or a
 * player-defined mark ({@code #name}) that spells can stack, read and consume.
 */
public record Status(String name) {
    public static final Status BURNING = new Status("burning");
    public static final Status FROZEN = new Status("frozen");

    public boolean isMark() {
        return name.startsWith("#");
    }

    public static Status parse(String s) {
        if (s.equals("burning")) return BURNING;
        if (s.equals("frozen")) return FROZEN;
        if (s.length() > 1 && s.startsWith("#")) return new Status(s);
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
