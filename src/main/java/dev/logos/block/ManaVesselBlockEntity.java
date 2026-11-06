package dev.logos.block;

import dev.logos.runtime.ManaVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** A block entity with a store of mana that takes in what it is given up to a fixed cap; subclasses save it themselves. */
public abstract class ManaVesselBlockEntity extends BlockEntity implements ManaVessel {
    private final double maxMana;
    protected double mana;

    protected ManaVesselBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, double maxMana) {
        super(type, pos, state);
        this.maxMana = maxMana;
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return maxMana;
    }

    @Override
    public double receiveMana(double amount) {
        double taken = Math.clamp(amount, 0, maxMana - mana);
        if (taken > 0) {
            mana += taken;
            setChanged();
        }
        return taken;
    }
}
