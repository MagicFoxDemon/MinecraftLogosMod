package dev.logos.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.Entity;

/**
 * Finds a creature's mana pool: its own when it is a {@link ManaVessel} (a lifeform, a colossus), or one
 * another mod's compat keeps for it (a maid's). Its spells give their leftover back to it, and infusion
 * and mana potions fill it, the same way for both.
 */
public final class ManaVessels {
    private static final List<Function<Entity, @Nullable ManaVessel>> LOOKUPS = new CopyOnWriteArrayList<>();

    private ManaVessels() {}

    /** Lets a compat give creatures that are not vessels themselves a pool; asked in the order registered, null for none. */
    public static void register(Function<Entity, @Nullable ManaVessel> lookup) {
        LOOKUPS.add(lookup);
    }

    /** {@code e}'s mana pool, or null if it holds none. */
    public static @Nullable ManaVessel of(@Nullable Entity e) {
        if (e instanceof ManaVessel v) return v;
        if (e == null) return null;
        for (Function<Entity, @Nullable ManaVessel> lookup : LOOKUPS) {
            ManaVessel v = lookup.apply(e);
            if (v != null) return v;
        }
        return null;
    }
}
