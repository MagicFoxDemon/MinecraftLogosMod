package dev.logos.port;

import java.util.List;
import java.util.SequencedCollection;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.CombinedResourceHandler}: several handlers one after
 * another, as one. Putting in or taking out anywhere tries them in order.
 */
public class CombinedResourceHandler<T extends Resource> implements ResourceHandler<T> {
    private final List<ResourceHandler<T>> handlers;

    public CombinedResourceHandler(SequencedCollection<? extends ResourceHandler<T>> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    @SafeVarargs
    public CombinedResourceHandler(ResourceHandler<T>... handlers) {
        this.handlers = List.of(handlers);
    }

    /** Which handler {@code index} falls in. */
    protected int getHandlerIndex(int index) {
        if (index < 0) throw new IndexOutOfBoundsException(index);
        for (int h = 0; h < handlers.size(); h++) {
            int size = handlers.get(h).size();
            if (index < size) return h;
            index -= size;
        }
        throw new IndexOutOfBoundsException(index);
    }

    protected ResourceHandler<T> getHandlerFromIndex(int handlerIndex) {
        return handlers.get(handlerIndex);
    }

    /** {@code index} within the handler it falls in. */
    protected int getSlotFromIndex(int index, int handlerIndex) {
        for (int h = 0; h < handlerIndex; h++) index -= handlers.get(h).size();
        return index;
    }

    @Override
    public final int size() {
        int size = 0;
        for (ResourceHandler<T> h : handlers) size += h.size();
        return size;
    }

    @Override
    public T getResource(int index) {
        int h = getHandlerIndex(index);
        return handlers.get(h).getResource(getSlotFromIndex(index, h));
    }

    @Override
    public long getAmountAsLong(int index) {
        int h = getHandlerIndex(index);
        return handlers.get(h).getAmountAsLong(getSlotFromIndex(index, h));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        int h = getHandlerIndex(index);
        return handlers.get(h).getCapacityAsLong(getSlotFromIndex(index, h), resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        int h = getHandlerIndex(index);
        return handlers.get(h).isValid(getSlotFromIndex(index, h), resource);
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        int h = getHandlerIndex(index);
        return handlers.get(h).insert(getSlotFromIndex(index, h), resource, amount, transaction);
    }

    @Override
    public int insert(T resource, int amount, TransactionContext transaction) {
        int inserted = 0;
        for (ResourceHandler<T> h : handlers) {
            if (inserted >= amount) break;
            inserted += h.insert(resource, amount - inserted, transaction);
        }
        return inserted;
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        int h = getHandlerIndex(index);
        return handlers.get(h).extract(getSlotFromIndex(index, h), resource, amount, transaction);
    }

    @Override
    public int extract(T resource, int amount, TransactionContext transaction) {
        int extracted = 0;
        for (ResourceHandler<T> h : handlers) {
            if (extracted >= amount) break;
            extracted += h.extract(resource, amount - extracted, transaction);
        }
        return extracted;
    }
}
