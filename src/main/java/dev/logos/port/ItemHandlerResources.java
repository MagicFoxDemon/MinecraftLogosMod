package dev.logos.port;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 1.21.1's {@link IItemHandler} as a {@link ResourceHandler} of items, for what other mods give their creatures. Moves
 * happen at once; a transaction that is taken back puts each touched slot back as it was, or, for a handler whose slots
 * cannot be set, makes the opposite move.
 */
final class ItemHandlerResources implements ResourceHandler<ItemResource>, IndexModifier<ItemResource> {
    private final IItemHandler handler;
    private final List<SlotJournal> journals = new ArrayList<>();

    ItemHandlerResources(IItemHandler handler) {
        this.handler = handler;
    }

    IItemHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemResource getResource(int index) {
        return ItemResource.of(handler.getStackInSlot(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return handler.getStackInSlot(index).getCount();
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        int limit = handler.getSlotLimit(index);
        return resource.isEmpty() ? limit : Math.min(limit, resource.getMaxStackSize());
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return !resource.isEmpty() && handler.isItemValid(index, resource.toStack());
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (resource.isEmpty() || amount <= 0) return 0;
        ItemStack rest = handler.insertItem(index, resource.toStack(amount), true);
        int inserted = amount - rest.getCount();
        if (inserted <= 0) return 0;
        journal(index).updateSnapshots(transaction);
        rest = handler.insertItem(index, resource.toStack(inserted), false);
        inserted -= rest.getCount();
        journal(index).moved += inserted;
        return inserted;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (resource.isEmpty() || amount <= 0 || !resource.matches(handler.getStackInSlot(index))) return 0;
        int extracted = handler.extractItem(index, amount, true).getCount();
        if (extracted <= 0) return 0;
        journal(index).updateSnapshots(transaction);
        extracted = handler.extractItem(index, extracted, false).getCount();
        journal(index).moved -= extracted;
        return extracted;
    }

    /** Sets a slot outright; one that cannot be set gives up what it holds and takes the new, or neither. */
    @Override
    public void set(int index, ItemResource resource, int amount) {
        if (handler instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(index, resource.toStack(amount));
            return;
        }
        try (Transaction tx = Transaction.openRoot()) {
            ItemResource old = getResource(index);
            int had = getAmountAsInt(index);
            if (!old.isEmpty() && extract(index, old, had, tx) != had) return;
            if (!resource.isEmpty() && amount > 0 && insert(index, resource, amount, tx) != amount) return;
            tx.commit();
        }
    }

    private SlotJournal journal(int index) {
        while (journals.size() <= index) journals.add(new SlotJournal(journals.size()));
        return journals.get(index);
    }

    /** One slot's state before a transaction: the stack, and how much has been put in (or, negative, taken out) since. */
    private record Snapshot(ItemStack stack, int moved) {}

    private final class SlotJournal extends SnapshotJournal<Snapshot> {
        private final int index;
        int moved;

        SlotJournal(int index) {
            this.index = index;
        }

        @Override
        protected Snapshot createSnapshot() {
            return new Snapshot(handler.getStackInSlot(index).copy(), moved);
        }

        @Override
        protected void revertToSnapshot(Snapshot snapshot) {
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(index, snapshot.stack());
            } else {
                int undo = moved - snapshot.moved();
                if (undo > 0) handler.extractItem(index, undo, false);
                else if (undo < 0) handler.insertItem(index, snapshot.stack().copyWithCount(-undo), false);
            }
            moved = snapshot.moved();
        }

        @Override
        protected void onRootCommit(Snapshot originalState) {
            moved = 0;
        }
    }
}
