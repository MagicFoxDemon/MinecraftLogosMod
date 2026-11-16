package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Anything that holds a pool of mana of its own: today the mana lifeform. A future area mana
 * distributor fills vessels through this, without knowing what they are.
 */
public interface ManaVessel {
    double mana();

    double maxMana();

    /** Takes in up to {@code amount}; returns how much it actually took. */
    double receiveMana(double amount);

    /**
     * A block that belongs to a vessel kept elsewhere, as the side half of an alchemy table does: it says which.
     * Mana poured at such a block goes to that vessel ({@link ManaInfusion#vesselAt}).
     */
    interface OfBlock {
        /** The vessel the block at {@code pos} belongs to, if it is loaded. */
        @Nullable ManaVessel vessel(Level level, BlockPos pos, BlockState state);
    }
}
