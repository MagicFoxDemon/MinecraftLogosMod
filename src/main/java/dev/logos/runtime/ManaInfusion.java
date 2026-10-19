package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.block.AlchemyTableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mana a spell gives away (渡魔 to a creature, 灌魔 into a block): who can hold it and how much room
 * they have. A player holds it in their pool; anything else that holds mana is a {@link ManaVessel}
 * (a mana lifeform, a colossus, an alchemy table). Giving costs a little more than it gives, so
 * passing mana around by spell always loses some.
 */
public final class ManaInfusion {
    /** Mana a spell spends for each point it gives. */
    public static final double COST = 1.1;

    private ManaInfusion() {}

    /** How much more mana {@code e} can hold: 0 for a creature that holds none. */
    public static double room(Entity e) {
        if (e instanceof Player p) {
            PlayerMagic m = PlayerMagic.of(p);
            return Math.max(0, m.maxMana - m.mana);
        }
        return e instanceof ManaVessel v ? Math.max(0, v.maxMana() - v.mana()) : 0;
    }

    /** Gives {@code e} up to {@code amount}; returns what it took. */
    public static double give(Entity e, double amount) {
        double given = Math.min(amount, room(e));
        if (given <= 0) return 0;
        if (e instanceof Player p) PlayerMagic.of(p).mana += given;
        else if (e instanceof ManaVessel v) return v.receiveMana(given);
        return given;
    }

    /** The block at {@code pos} that holds mana, if any; either half of an alchemy table is the table. */
    public static @Nullable ManaVessel vesselAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ManaVessel v) return v;
        BlockState state = level.getBlockState(pos);
        return AlchemyTableBlock.table(level, pos, state);
    }
}
