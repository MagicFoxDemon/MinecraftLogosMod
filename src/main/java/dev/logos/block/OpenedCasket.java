package dev.logos.block;

import java.util.function.DoubleUnaryOperator;
import java.util.function.ToIntFunction;

import dev.logos.item.ManaCasketItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * The 魔匣 a machine (the alchemy table, the storage tank) has taken from its fuel slot, as a furnace
 * keeps what it is burning: it gives the machine what it lacks, one casket at a time, and the next is
 * opened only when this one is spent and its empty casket has gone out. Its slot is shown on the
 * machine's screen and cannot be taken from; the casket drops with the machine.
 */
final class OpenedCasket {
    private final ItemStacksResourceHandler slot;

    OpenedCasket(Runnable changed) {
        slot = new ItemStacksResourceHandler(1) {
            @Override
            public boolean isValid(int index, ItemResource resource) {
                return false;
            }

            @Override
            protected void onContentsChanged(int index, ItemStack previousContents) {
                changed.run();
            }
        };
    }

    /** For the screen. */
    ItemStacksResourceHandler slot() {
        return slot;
    }

    ItemStack held() {
        return slot.getResource(0).toStack(slot.getAmountAsInt(0));
    }

    /**
     * One step: put a waiting empty casket out ({@code putOut} answers how many it took, 0 or 1), open
     * a casket from {@code fuel} if none is open and {@code room} is above 0, and give up to
     * {@code room} from the open one through {@code give} (which answers what it took).
     */
    void work(ItemStacksResourceHandler fuel, double room, DoubleUnaryOperator give, ToIntFunction<ItemStack> putOut) {
        ItemStack before = held();
        ItemStack held = before;
        // An empty casket left waiting goes out first; until it does, the next stays shut.
        if (!held.isEmpty() && !(held.getItem() instanceof ManaCasketItem)) {
            if (putOut.applyAsInt(held) == 0) return;
            held = ItemStack.EMPTY;
        }
        if (held.isEmpty()) {
            if (!before.isEmpty()) slot.set(0, ItemResource.EMPTY, 0);
            ItemStack next = fuel.getResource(0).toStack(fuel.getAmountAsInt(0));
            if (room <= 0 || !(next.getItem() instanceof ManaCasketItem)) return;
            fuel.set(0, fuel.getResource(0), next.getCount() - 1);
            held = next.copyWithCount(1);
        }
        ItemStack after = held;
        if (held.getItem() instanceof ManaCasketItem && room > 0) {
            double taken = give.applyAsDouble(Math.min(room, ManaCasketItem.manaIn(held)));
            if (taken > 0) after = ManaCasketItem.afterGiving(held, taken);
        }
        // Spent: its empty casket goes out, or waits here and keeps the next casket shut.
        if (!(after.getItem() instanceof ManaCasketItem) && putOut.applyAsInt(after) > 0) after = ItemStack.EMPTY;
        if (!ItemStack.matches(before, after)) slot.set(0, after.isEmpty() ? ItemResource.EMPTY : ItemResource.of(after), after.getCount());
    }

    void save(ValueOutput output) {
        slot.serialize(output.child("opened_casket"));
    }

    void load(ValueInput input) {
        input.child("opened_casket").ifPresent(slot::deserialize);
    }

    void drop(Level level, BlockPos pos) {
        ItemStack held = held();
        if (!held.isEmpty()) Block.popResource(level, pos, held);
    }
}
