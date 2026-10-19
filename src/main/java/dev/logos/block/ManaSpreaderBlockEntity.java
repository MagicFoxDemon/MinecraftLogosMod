package dev.logos.block;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaField;
import dev.logos.runtime.ManaVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * 魔力散布器: burns mana fuel from its drawer and pours the mana into the air of its chunk, up to
 * {@link ManaField#CAP}; from there it spreads to the chunks around ({@link ManaField}). A collector
 * facing it can feed it too, to carry mana on from another field.
 */
public class ManaSpreaderBlockEntity extends BlockEntity implements ManaVessel {
    /** Mana it holds before pouring: room for the richest fuel. */
    public static final double MAX_MANA = 1000;
    /** Most mana it pours into the air each second. */
    public static final double POUR = 20;

    private final ItemStacksResourceHandler fuel = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return AlchemyTableBlockEntity.fuelValue(resource.toStack()) > 0;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private double mana;

    public ManaSpreaderBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.MANA_SPREADER_BE.get(), pos, state);
    }

    /** What it poured into the air last second, for its screen. */
    private double lastPoured;

    public double lastPoured() {
        return lastPoured;
    }

    /** The drawer: hoppers and pipes put fuel in here. */
    public ItemStacksResourceHandler fuel() {
        return fuel;
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return MAX_MANA;
    }

    @Override
    public double receiveMana(double amount) {
        double taken = Math.clamp(amount, 0, MAX_MANA - mana);
        if (taken > 0) {
            mana += taken;
            setChanged();
        }
        return taken;
    }

    /** Once a second: burn a fuel that fits, then pour what it holds into its chunk's air. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, ManaSpreaderBlockEntity spreader) {
        if (!(level instanceof ServerLevel server) || !ManaField.working(level)) return;
        ItemStack f = spreader.fuel.getResource(0).toStack(spreader.fuel.getAmountAsInt(0));
        int value = AlchemyTableBlockEntity.fuelValue(f);
        if (value > 0 && spreader.mana + value <= MAX_MANA) {
            spreader.fuel.set(0, spreader.fuel.getResource(0), f.getCount() - 1);
            spreader.mana += value;
        }
        double poured = ManaField.pour(server, pos, Math.min(POUR, spreader.mana));
        spreader.lastPoured = poured;
        if (poured > 0) {
            spreader.mana -= poured;
            spreader.setChanged();
        }
        ManaSpreaderBlock.light(level, pos, state, spreader.mana > 0 || ManaField.spread(server, pos) > 0);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        ItemStack f = fuel.getResource(0).toStack(fuel.getAmountAsInt(0));
        if (level != null && !f.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), f);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        fuel.serialize(output.child("fuel"));
        output.putDouble("mana", mana);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("fuel").ifPresent(fuel::deserialize);
        mana = input.getDoubleOr("mana", 0);
    }
}
