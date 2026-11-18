package dev.logos.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;

import net.minecraft.core.NonNullList;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.StacksResourceHandler}: a {@link ResourceHandler} kept
 * as a list of stacks. Each index takes its own snapshot, so a transaction that is taken back restores only what it
 * touched; {@link #onContentsChanged} runs when the root commits, once per index that changed, and on {@link #set}.
 */
public abstract class StacksResourceHandler<S, T extends Resource> implements ResourceHandler<T> {
    public static final String VALUE_IO_KEY = "stacks";

    protected final S emptyStack;
    protected NonNullList<S> stacks;
    protected final Codec<NonNullList<S>> codec;
    private final List<IndexJournal> journals = new ArrayList<>();

    protected StacksResourceHandler(int size, S emptyStack, Codec<S> stackCodec) {
        this(NonNullList.withSize(size, emptyStack), emptyStack, stackCodec);
    }

    protected StacksResourceHandler(NonNullList<S> stacks, S emptyStack, Codec<S> stackCodec) {
        this.emptyStack = emptyStack;
        this.codec = stackCodec.listOf().xmap(list -> {
            NonNullList<S> copy = NonNullList.withSize(list.size(), emptyStack);
            for (int i = 0; i < list.size(); i++) copy.set(i, list.get(i));
            return copy;
        }, list -> list);
        setStacks(stacks);
    }

    protected void setStacks(NonNullList<S> stacks) {
        this.stacks = stacks;
        journals.clear();
        for (int i = 0; i < stacks.size(); i++) journals.add(new IndexJournal(i));
    }

    public void serialize(ValueOutput output) {
        output.store(VALUE_IO_KEY, codec, stacks);
    }

    public void deserialize(ValueInput input) {
        input.read(VALUE_IO_KEY, codec).ifPresent(read -> {
            // Keep this handler's size: what a bigger save holds beyond it is dropped.
            for (int i = 0; i < stacks.size(); i++) stacks.set(i, i < read.size() ? read.get(i) : emptyStack);
        });
    }

    /** Sets an index outright, outside any transaction. */
    public void set(int index, T resource, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative amount " + amount);
        if (resource.isEmpty() && amount > 0) throw new IllegalArgumentException("Empty resource with amount " + amount);
        S old = stacks.set(index, getStackFrom(resource, amount));
        onContentsChanged(index, old);
    }

    protected abstract T getResourceFrom(S stack);

    protected abstract int getAmountFrom(S stack);

    protected abstract S getStackFrom(T resource, int amount);

    protected abstract S copyOf(S stack);

    protected boolean matches(S stack, T resource) {
        return getResourceFrom(stack).equals(resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        return true;
    }

    protected abstract int getCapacity(int index, T resource);

    protected void onContentsChanged(int index, S previousContents) {}

    public NonNullList<S> copyToList() {
        NonNullList<S> copy = NonNullList.withSize(stacks.size(), emptyStack);
        for (int i = 0; i < stacks.size(); i++) copy.set(i, copyOf(stacks.get(i)));
        return copy;
    }

    @Override
    public int size() {
        return stacks.size();
    }

    @Override
    public T getResource(int index) {
        return getResourceFrom(stacks.get(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return getAmountFrom(stacks.get(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return getCapacity(index, resource);
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        checkInsertable(resource, amount);
        S current = stacks.get(index);
        int had = getAmountFrom(current);
        if ((had != 0 && !matches(current, resource)) || !isValid(index, resource)) return 0;
        int inserted = Math.min(amount, getCapacity(index, resource) - had);
        if (inserted <= 0) return 0;
        journals.get(index).updateSnapshots(transaction);
        stacks.set(index, getStackFrom(resource, had + inserted));
        return inserted;
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        checkInsertable(resource, amount);
        S current = stacks.get(index);
        if (!matches(current, resource)) return 0;
        int had = getAmountFrom(current);
        int extracted = Math.min(amount, had);
        if (extracted <= 0) return 0;
        journals.get(index).updateSnapshots(transaction);
        stacks.set(index, had == extracted ? emptyStack : getStackFrom(resource, had - extracted));
        return extracted;
    }

    private static void checkInsertable(Resource resource, int amount) {
        if (resource.isEmpty()) throw new IllegalArgumentException("Empty resource");
        if (amount < 0) throw new IllegalArgumentException("Negative amount " + amount);
    }

    private final class IndexJournal extends SnapshotJournal<S> {
        private final int index;

        IndexJournal(int index) {
            this.index = index;
        }

        @Override
        protected S createSnapshot() {
            return copyOf(stacks.get(index));
        }

        @Override
        protected void revertToSnapshot(S snapshot) {
            stacks.set(index, snapshot);
        }

        @Override
        protected void onRootCommit(S originalState) {
            onContentsChanged(index, originalState);
        }
    }
}
