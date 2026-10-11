package dev.logos.runtime;

/**
 * A familiar that is made rather than summoned — the mana lifeform, the alchemical colossus. It is
 * kept with the world (never unmade on reload or logout), counts against its own per-player limit,
 * and has a mind of its own that does its following and fighting; the familiar bond only carries its
 * master's orders (gather, stance, sic) to it.
 */
public interface KeptFamiliar {
    /** Out of mana and lying still. */
    boolean isDormant();

    /**
     * Whether it is beyond its master's orders for now (dormant, or a colossus gone berserk): the
     * familiar bond then neither aims nor leashes it.
     */
    default boolean actsAlone() {
        return isDormant();
    }
}
