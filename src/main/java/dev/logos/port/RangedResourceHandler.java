package dev.logos.port;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.RangedResourceHandler}: the indices {@code [start, end)}
 * of another handler, seen from 0.
 */
public class RangedResourceHandler<T extends Resource> implements ResourceHandler<T> {
    private final Supplier<ResourceHandler<T>> delegate;
    protected int start;
    protected int end;

    protected RangedResourceHandler(ResourceHandler<T> delegate, int start, int end) {
        this(() -> delegate, start, end);
    }

    protected RangedResourceHandler(Supplier<ResourceHandler<T>> delegate, int start, int end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("Bad range [" + start + ", " + end + ")");
        this.delegate = delegate;
        this.start = start;
        this.end = end;
    }

    public static <T extends Resource> RangedResourceHandler<T> of(ResourceHandler<T> delegate, int start, int end) {
        return new RangedResourceHandler<>(delegate, start, end);
    }

    public static <T extends Resource> RangedResourceHandler<T> of(Supplier<ResourceHandler<T>> delegate, int start, int end) {
        return new RangedResourceHandler<>(delegate, start, end);
    }

    public static <T extends Resource> RangedResourceHandler<T> ofSingleIndex(ResourceHandler<T> delegate, int index) {
        return new RangedResourceHandler<>(delegate, index, index + 1);
    }

    protected ResourceHandler<T> getDelegate() {
        return delegate.get();
    }

    protected int convertIndex(int index) {
        Objects.checkIndex(index, size());
        return start + index;
    }

    @Override
    public int size() {
        return end - start;
    }

    @Override
    public T getResource(int index) {
        return getDelegate().getResource(convertIndex(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return getDelegate().getAmountAsLong(convertIndex(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        return getDelegate().getCapacityAsLong(convertIndex(index), resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        return getDelegate().isValid(convertIndex(index), resource);
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        return getDelegate().insert(convertIndex(index), resource, amount, transaction);
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        return getDelegate().extract(convertIndex(index), resource, amount, transaction);
    }
}
