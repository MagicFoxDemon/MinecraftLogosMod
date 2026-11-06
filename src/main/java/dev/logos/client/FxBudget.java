package dev.logos.client;

/**
 * How many one-shot effects and particles spell FX may start in one client tick, so that a spell striking a hundred
 * targets at once cannot ask for a hundred Photon effects. What is past the budget is skipped, not put off.
 */
final class FxBudget {
    private int effects, particles;

    /** A new client tick: the whole budget again. */
    void refill() {
        effects = particles = 0;
    }

    /** Whether one more effect may start this tick, of {@code max}; counts it if so. */
    boolean effect(int max) {
        if (effects >= max) return false;
        effects++;
        return true;
    }

    /** How many of {@code wanted} particles may still be added this tick, of {@code max}; counts those. */
    int particles(int wanted, int max) {
        int granted = Math.clamp(wanted, 0, Math.max(0, max - particles));
        particles += granted;
        return granted;
    }
}
