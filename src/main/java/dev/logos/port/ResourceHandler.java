package dev.logos.port;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.ResourceHandler}: indexed storage of one kind of
 * {@link Resource}, changed only inside a {@link Transaction} so that a change can be taken back.
 */
public interface ResourceHandler<T extends Resource> {
    int size();

    T getResource(int index);

    long getAmountAsLong(int index);

    default int getAmountAsInt(int index) {
        return (int) Math.min(Integer.MAX_VALUE, getAmountAsLong(index));
    }

    long getCapacityAsLong(int index, T resource);

    default int getCapacityAsInt(int index, T resource) {
        return (int) Math.min(Integer.MAX_VALUE, getCapacityAsLong(index, resource));
    }

    boolean isValid(int index, T resource);

    /** Puts up to {@code amount} of {@code resource} at {@code index}; returns how much went in. */
    int insert(int index, T resource, int amount, TransactionContext transaction);

    /** Puts up to {@code amount} of {@code resource} anywhere, filling indices in order; returns how much went in. */
    default int insert(T resource, int amount, TransactionContext transaction) {
        int inserted = 0;
        for (int i = 0; i < size() && inserted < amount; i++) inserted += insert(i, resource, amount - inserted, transaction);
        return inserted;
    }

    /** Takes up to {@code amount} of {@code resource} from {@code index}; returns how much came out. */
    int extract(int index, T resource, int amount, TransactionContext transaction);

    /** Takes up to {@code amount} of {@code resource} from anywhere, in index order; returns how much came out. */
    default int extract(T resource, int amount, TransactionContext transaction) {
        int extracted = 0;
        for (int i = 0; i < size() && extracted < amount; i++) extracted += extract(i, resource, amount - extracted, transaction);
        return extracted;
    }
}
