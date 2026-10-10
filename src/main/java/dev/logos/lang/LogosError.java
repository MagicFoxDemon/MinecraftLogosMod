package dev.logos.lang;

/** A compile-time or runtime error in a Logos program, carrying the source position. */
public class LogosError extends RuntimeException {
    public final int line;
    public final int col;

    public LogosError(String message, int line, int col) {
        super(message);
        this.line = line;
        this.col = col;
    }

    public String describe() {
        return line > 0 ? "第 " + line + " 行第 " + col + " 列: " + getMessage() : getMessage();
    }
}
