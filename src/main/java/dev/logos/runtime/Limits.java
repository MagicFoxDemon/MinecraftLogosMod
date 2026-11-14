package dev.logos.runtime;

/**
 * Fixed safety limits ("Logos Stability" at runtime). Mana bounds how much work a spell does;
 * these bound how complex it may become. The tunable ones (injected mana, recursion, repeat,
 * steps per tick) live in {@link dev.logos.LogosConfig}.
 */
public final class Limits {
    public static final int MAX_OBJECTS_PER_SPELL = 48;
    public static final int MAX_GLOBAL_OBJECTS = 600;
    /** Delayed tasks waiting across all spells; still leaves room for the most one seventh-tier page can queue (about 12,800). */
    public static final int MAX_GLOBAL_TASKS = 20000;
    public static final int MAX_SPLIT = 16;

    public static final double DEFAULT_MAX_MANA = 200;
    public static final double MANA_REGEN_PER_TICK = 0.3;
    /** Percent of the cap a second a player's pool refills by besides the flat rate ({@code mana_regeneration_percent}). */
    public static final double MANA_REGEN_PERCENT = 0.5;
    public static final int DEFAULT_STABILITY = 80;
    public static final int MAX_PASSIVES = 3;
    public static final int PASSIVE_COOLDOWN_TICKS = 20;

    private Limits() {}
}
