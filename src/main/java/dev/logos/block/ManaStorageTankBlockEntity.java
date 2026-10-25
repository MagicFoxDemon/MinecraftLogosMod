package dev.logos.block;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaInfusion;
import dev.logos.runtime.ManaVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * 魔力存储罐: holds up to 5000 mana. Collectors, the infusing rune and mana items dropped in its input
 * (dissolved whole, nothing lost) fill it. Once a second it works its mode: storing keeps the mana; supplying
 * gives each neighbouring vessel some (not collectors or other tanks, so mana does not run in circles);
 * condensing grows the seed in its seed slot into the next solid mana (amethyst shard → mana shard → mana
 * crystal → mana gemstone) at 90%. A redstone signal pauses supplying and condensing.
 */
public class ManaStorageTankBlockEntity extends BlockEntity implements ManaVessel {
    public static final double MAX_MANA = 5000;
    /** Most mana given to each neighbour a second when supplying. */
    public static final double SUPPLY = 50;
    /** Most mana poured into a growing crystal a second, and the share of it the crystal keeps. */
    public static final double CONDENSE_RATE = 20, EFFICIENCY = 0.9;

    public enum Mode implements StringRepresentable {
        STORE, SUPPLY, CONDENSE;

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Component displayName() {
            return Component.translatable("logos.mana_storage_tank.mode." + getSerializedName());
        }
    }

    /** Mana items dropped in to be dissolved into the tank. */
    private final ItemStacksResourceHandler input = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return AlchemyTableBlockEntity.fuelValue(resource.toStack()) > 0;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    /** The seed a crystal grows from. */
    private final ItemStacksResourceHandler seed = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return grownFrom(resource.getItem()) != null;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            // A different seed starts over; what the old one had taken goes back into the tank.
            if (!ItemStack.isSameItem(previousContents, getResource(0).toStack())) {
                mana = Math.min(MAX_MANA, mana + grown);
                grown = 0;
            }
            changed();
        }
    };
    /** Grown crystals; only taken from. */
    private final ItemStacksResourceHandler output = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return false;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    /** What hoppers and pipes see: mana items and seeds in, grown crystals out. */
    private final ResourceHandler<ItemResource> automation = new CombinedResourceHandler<>(
            AlchemyTableBlockEntity.OneWay.in(input, 0, 1), AlchemyTableBlockEntity.OneWay.in(seed, 0, 1),
            AlchemyTableBlockEntity.OneWay.out(output, 0, 1));

    private double mana;
    private Mode mode = Mode.STORE;
    /** Mana poured into the crystal growing on the seed so far. */
    private double grown;
    /** Mana each side gave last second when supplying, for the screen. */
    private double lastSupplied;

    public ManaStorageTankBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.MANA_STORAGE_TANK_BE.get(), pos, state);
    }

    /** What a seed grows into in the tank, or null if it is no seed. */
    public static @Nullable Item grownFrom(Item seed) {
        if (seed == Items.AMETHYST_SHARD) return LogosMod.MANA_SHARD.get();
        if (seed == LogosMod.MANA_SHARD.get()) return LogosMod.MANA_CRYSTAL.get();
        if (seed == LogosMod.MANA_CRYSTAL.get()) return LogosMod.MANA_GEMSTONE.get();
        return null;
    }

    /** The mana a seed takes to grow: the mana it gains, at 90%. */
    public static double growCost(Item seed) {
        Item into = grownFrom(seed);
        if (into == null) return 0;
        return (AlchemyTableBlockEntity.fuelValue(new ItemStack(into)) - AlchemyTableBlockEntity.fuelValue(new ItemStack(seed))) / EFFICIENCY;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ManaStorageTankBlockEntity tank) {
        if (level.getGameTime() % 20 != Math.floorMod(pos.asLong(), 20)) return;
        tank.work(level, pos);
    }

    /** One second of work. */
    public void work(Level level, BlockPos pos) {
        boolean changed = false;
        // Mana items dissolve whole, one a second, when all of one fits.
        ItemStack in = input.getResource(0).toStack(input.getAmountAsInt(0));
        int value = AlchemyTableBlockEntity.fuelValue(in);
        if (value > 0 && mana + value <= MAX_MANA) {
            input.set(0, input.getResource(0), in.getCount() - 1);
            mana += value;
            changed = true;
        }
        lastSupplied = 0;
        if (!level.hasNeighborSignal(pos)) {
            if (mode == Mode.SUPPLY) changed |= supply(level, pos);
            else if (mode == Mode.CONDENSE) changed |= condense();
        }
        if (changed) changed();
    }

    private boolean supply(Level level, BlockPos pos) {
        boolean gave = false;
        for (Direction d : Direction.values()) {
            if (mana <= 0) break;
            BlockPos at = pos.relative(d);
            if (level.getBlockEntity(at) instanceof ManaStorageTankBlockEntity || level.getBlockEntity(at) instanceof ManaCollectorBlockEntity) continue;
            ManaVessel target = ManaInfusion.vesselAt(level, at);
            if (target == null || target == this) continue;
            double given = target.receiveMana(Math.min(SUPPLY, mana));
            if (given > 0) {
                mana -= given;
                lastSupplied += given;
                gave = true;
            }
        }
        return gave;
    }

    private boolean condense() {
        Item from = seed.getResource(0).getItem();
        Item into = seed.getAmountAsInt(0) > 0 ? grownFrom(from) : null;
        if (into == null) return false;
        double cost = growCost(from);
        if (grown < cost) {
            double pour = Math.min(Math.min(CONDENSE_RATE, mana), cost - grown);
            if (pour <= 0) return false;
            mana -= pour;
            grown += pour;
        }
        if (grown >= cost) {
            ItemResource done = ItemResource.of(into);
            int held = output.getAmountAsInt(0);
            // Ripe: it waits for room in the output.
            if (held > 0 && !output.getResource(0).equals(done) || held >= into.getDefaultMaxStackSize()) return true;
            output.set(0, done, held + 1);
            grown = 0;
            seed.set(0, seed.getResource(0), seed.getAmountAsInt(0) - 1);
        }
        return true;
    }

    public ItemStacksResourceHandler input() {
        return input;
    }

    public ItemStacksResourceHandler seed() {
        return seed;
    }

    public ItemStacksResourceHandler output() {
        return output;
    }

    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        changed();
    }

    public double lastSupplied() {
        return lastSupplied;
    }

    /** How far the crystal on the seed has grown, 0–1; 0 when nothing grows. */
    public float growth() {
        Item from = seed.getResource(0).getItem();
        double cost = seed.getAmountAsInt(0) > 0 ? growCost(from) : 0;
        return cost <= 0 ? 0 : (float) Math.min(1, grown / cost);
    }

    /** Which solid mana is growing: 0 shard, 1 crystal, 2 gemstone; -1 if none. */
    public int growingTier() {
        if (seed.getAmountAsInt(0) == 0) return -1;
        Item into = grownFrom(seed.getResource(0).getItem());
        if (into == LogosMod.MANA_SHARD.get()) return 0;
        if (into == LogosMod.MANA_CRYSTAL.get()) return 1;
        return into == LogosMod.MANA_GEMSTONE.get() ? 2 : -1;
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

    /** Sets the stored mana (tests and scenarios). */
    public void setMana(double mana) {
        this.mana = Math.clamp(mana, 0, MAX_MANA);
        changed();
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        input.serialize(output.child("input"));
        seed.serialize(output.child("seed"));
        this.output.serialize(output.child("output"));
        output.putDouble("mana", mana);
        output.putDouble("grown", grown);
        output.putString("mode", mode.getSerializedName());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("input").ifPresent(this.input::deserialize);
        input.child("seed").ifPresent(seed::deserialize);
        input.child("output").ifPresent(output::deserialize);
        mana = input.getDoubleOr("mana", 0);
        grown = input.getDoubleOr("grown", 0);
        String m = input.getStringOr("mode", "store");
        mode = Mode.STORE;
        for (Mode each : Mode.values()) if (each.getSerializedName().equals(m)) mode = each;
    }

    /** The mana goes with the tank when it is broken (its loot copies this onto the item). */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (mana > 0) components.set(LogosMod.STORED_MANA.get(), mana);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        mana = Math.clamp(components.getOrDefault(LogosMod.STORED_MANA.get(), 0.0), 0, MAX_MANA);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        for (ItemStacksResourceHandler h : new ItemStacksResourceHandler[]{input, seed, output}) {
            ItemStack s = h.getResource(0).toStack(h.getAmountAsInt(0));
            if (!s.isEmpty()) Block.popResource(level, pos, s);
        }
    }
}
