package dev.logos.lang;

/**
 * A premise established by an enclosing {@code if}. Facts about the same entity that an
 * {@code execute} targets form its proof and lower its cost (Lex Proof).
 */
public record Fact(String key, Kind kind, double value, Status status) {
    public enum Kind { HP_RATIO_MAX, HP_MAX, HAS, STACKS_MIN }

    public String describe() {
        return switch (kind) {
            case HP_RATIO_MAX -> "hp-ratio(" + key + ") ≤ " + Node.fmt(value);
            case HP_MAX -> "hp(" + key + ") ≤ " + Node.fmt(value);
            case HAS -> status + "(" + key + ")";
            case STACKS_MIN -> "stacks(" + key + ", " + status + ") ≥ " + Node.fmt(value);
        };
    }
}
