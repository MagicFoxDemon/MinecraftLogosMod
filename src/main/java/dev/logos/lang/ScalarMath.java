package dev.logos.lang;

/** Pure arithmetic shared by number runes; all results remain Logos numbers (doubles). */
public final class ScalarMath {
    private ScalarMath() {}

    /** A and B are exact at the endpoints; alpha is deliberately not clamped. */
    public static double lerp(double a, double b, double alpha) {
        if (alpha == 0) return a;
        if (alpha == 1) return b;
        if (alpha > 0 && alpha < 1) return a * (1 - alpha) + b * alpha;
        return Math.fma(b - a, alpha, a);
    }

    /** Reversed limits describe the same interval. */
    public static double clamp(double value, double a, double b) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), value));
    }

    /** Nearest uses half away from zero; up and down mean mathematical ceiling and floor. */
    public static double round(double value, String mode) {
        return switch (mode) {
            case "up" -> Math.ceil(value);
            case "down" -> Math.floor(value);
            default -> {
                double magnitude = Math.abs(value), whole = Math.floor(magnitude);
                yield Math.copySign(whole + (magnitude - whole >= 0.5 ? 1 : 0), value);
            }
        };
    }

    /** Real draws retain random(max)'s directed interval; integer draws include both integral bounds. */
    public static double random(double from, double to, boolean integer, double draw) {
        if (!integer) {
            double value = lerp(from, to, draw);
            if (from < to && value >= to) return Math.nextDown(to);
            if (from > to && value <= to) return Math.nextUp(to);
            return value;
        }
        double low = Math.ceil(Math.min(from, to)), high = Math.floor(Math.max(from, to));
        if (low > high) return 0;
        return clamp(Math.floor(lerp(low, high + 1, draw)), low, high);
    }
}
