package dev.logos.block;

import com.geckolib.animatable.GeoBlockEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;

import dev.logos.LogosMod;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.ManaVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.RangedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * 炼金术桌, kept on its main half: a raw-material area and a product area, a mana pool and a fluid
 * tank. Hoppers and other mods' pipes may only put into the raw area (or the fuel slot) and only
 * take from the product area; the tank is open both ways. The pool fills from mana fuel, from free
 * mana that drifts onto the table, and from a wand's holder.
 */
public class AlchemyTableBlockEntity extends BlockEntity implements GeoBlockEntity, ManaVessel {
    public static final int RAW_SLOTS = 18, PRODUCT_SLOTS = 9, SLOTS = RAW_SLOTS + PRODUCT_SLOTS;
    public static final int MAX_MANA = 1000;
    /** Ten buckets. One fluid at a time. */
    public static final int TANK_CAPACITY = 10_000;
    /** How much of the free mana touching the table it keeps (a lifeform keeps 0.8, a player 0.5 by default). */
    static final double FREE_MANA_EFFICIENCY = 0.8;
    /** How much a wand hands over per use. */
    public static final int WAND_CHARGE = 100;
    /** Changes reach watching clients at most this often, for the hourglass and the tank. */
    private static final int SYNC_INTERVAL = 5;

    private final ItemStacksResourceHandler items = new ItemStacksResourceHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private final ItemStacksResourceHandler fuel = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return fuelValue(resource.toStack()) > 0;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private final FluidStacksResourceHandler tank = new FluidStacksResourceHandler(1, TANK_CAPACITY) {
        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            changed();
        }
    };
    /** What hoppers and pipes see: fuel and raw materials in, products out. Fuel first, so fuel finds its slot. */
    private final ResourceHandler<ItemResource> automation = new CombinedResourceHandler<>(
            OneWay.in(fuel, 0, 1), OneWay.in(items, 0, RAW_SLOTS), OneWay.out(items, RAW_SLOTS, SLOTS));
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private double mana;
    private boolean dirty;

    public AlchemyTableBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.ALCHEMY_TABLE_BE.get(), pos, state);
    }

    public ItemStacksResourceHandler items() {
        return items;
    }

    public ItemStacksResourceHandler fuel() {
        return fuel;
    }

    public FluidStacksResourceHandler tank() {
        return tank;
    }

    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    public FluidStack fluid() {
        return tank.getResource(0).toStack(tank.getAmountAsInt(0));
    }

    /** Pours the tank away. */
    public void clearTank() {
        tank.set(0, FluidResource.EMPTY, 0);
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return MAX_MANA;
    }

    public void setMana(double mana) {
        this.mana = Math.clamp(mana, 0, MAX_MANA);
        changed();
    }

    @Override
    public double receiveMana(double amount) {
        double taken = Math.clamp(amount, 0, MAX_MANA - mana);
        if (taken > 0) setMana(mana + taken);
        return taken;
    }

    /** The mana a fuel item gives the table; unstable fuel (a tainted shard) is not accepted. */
    public static int fuelValue(ItemStack stack) {
        Lifeforms.ManaFuel f = stack.typeHolder().getData(LogosMod.MANA_FUEL);
        return f == null || f.unstable() ? 0 : (int) f.mana();
    }

    /** Every tick on the server: burn fuel that fits, take in free mana on the table, and tell clients what changed. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, AlchemyTableBlockEntity table) {
        ItemStack f = table.fuel.getResource(0).toStack(table.fuel.getAmountAsInt(0));
        int value = fuelValue(f);
        // Only burnt when it all fits, so no fuel is wasted.
        if (value > 0 && table.mana + value <= MAX_MANA) {
            table.fuel.set(0, table.fuel.getResource(0), f.getCount() - 1);
            table.setMana(table.mana + value);
        }
        if (level.getGameTime() % 10 == 0 && table.mana < MAX_MANA) {
            for (FreeManaEntity orb : level.getEntitiesOfClass(FreeManaEntity.class, table.reach())) {
                orb.drainInto(table, FREE_MANA_EFFICIENCY);
            }
        }
        if (table.dirty && level.getGameTime() % SYNC_INTERVAL == 0) {
            table.dirty = false;
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /** Both halves of the table and a little above them. */
    private AABB reach() {
        BlockPos side = AlchemyTableBlock.otherHalf(worldPosition, getBlockState());
        return new AABB(worldPosition).minmax(new AABB(side)).inflate(0.5, 1, 0.5);
    }

    /** Saved, and shown to watching clients a moment later. */
    private void changed() {
        setChanged();
        dirty = true;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        items.serialize(output.child("items"));
        fuel.serialize(output.child("fuel"));
        tank.serialize(output.child("tank"));
        output.putDouble("mana", mana);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("items").ifPresent(items::deserialize);
        input.child("fuel").ifPresent(fuel::deserialize);
        input.child("tank").ifPresent(tank::deserialize);
        mana = input.getDoubleOr("mana", 0);
    }

    /** Clients draw the hourglass and the tank, so they get the pool and the tank (and, simply, the rest). */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        for (var handler : new ItemStacksResourceHandler[] {items, fuel}) {
            for (int i = 0; i < handler.size(); i++) {
                ItemStack s = handler.getResource(i).toStack(handler.getAmountAsInt(i));
                if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s);
            }
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The process animations (fermenting bubbles, the selector levers) come with the processes.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    /** A range of a handler that can only be put into, or only taken from. */
    private static final class OneWay extends RangedResourceHandler<ItemResource> {
        private final boolean in;

        private OneWay(ResourceHandler<ItemResource> delegate, int start, int end, boolean in) {
            super(delegate, start, end);
            this.in = in;
        }

        static OneWay in(ResourceHandler<ItemResource> delegate, int start, int end) {
            return new OneWay(delegate, start, end, true);
        }

        static OneWay out(ResourceHandler<ItemResource> delegate, int start, int end) {
            return new OneWay(delegate, start, end, false);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return in && super.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return in ? super.insert(index, resource, amount, transaction) : 0;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            return in ? super.insert(resource, amount, transaction) : 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return in ? 0 : super.extract(index, resource, amount, transaction);
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            return in ? 0 : super.extract(resource, amount, transaction);
        }
    }
}
