package dev.logos.runtime;

/**
 * Anything that holds a pool of mana of its own: today the mana lifeform. A future area mana
 * distributor fills vessels through this, without knowing what they are.
 */
public interface ManaVessel {
    double mana();

    double maxMana();

    /** Takes in up to {@code amount}; returns how much it actually took. */
    double receiveMana(double amount);
}
