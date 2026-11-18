package dev.logos.port;

/**
 * Stands in for 26.1's {@code net.minecraft.util.ProblemReporter}, as far as the mod uses it: only
 * {@link #DISCARDING}, which drops what fails to save or load, as the mod always asks for.
 */
public final class ProblemReporter {
    public static final ProblemReporter DISCARDING = new ProblemReporter();

    private ProblemReporter() {}
}
