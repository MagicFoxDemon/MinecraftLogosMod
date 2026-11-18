package dev.logos.port;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.transaction.SnapshotJournal}: something that changes
 * inside {@link Transaction}s. Before its first change in a transaction it calls {@link #updateSnapshots}, which keeps a
 * snapshot to go back to if that transaction is not committed; when the root commits, {@link #onRootCommit} gets the
 * state from before it.
 */
public abstract class SnapshotJournal<T> {
    private static final Object NONE = new Object();

    /** By transaction depth: the state before the first change in it, or {@link #NONE}. */
    private final List<Object> snapshots = new ArrayList<>();
    private Object original = NONE;

    protected abstract T createSnapshot();

    protected abstract void revertToSnapshot(T snapshot);

    protected void releaseSnapshot(T snapshot) {}

    protected void onRootCommit(T originalState) {}

    public final boolean isInTransaction() {
        for (Object s : snapshots) if (s != NONE) return true;
        return false;
    }

    /** Call before changing anything in {@code transaction}. */
    public void updateSnapshots(TransactionContext transaction) {
        Transaction tx = (Transaction) transaction;
        tx.validateOpen();
        int depth = tx.depth();
        while (snapshots.size() <= depth) snapshots.add(NONE);
        if (snapshots.get(depth) == NONE) {
            snapshots.set(depth, createSnapshot());
            tx.enlist(this);
        }
    }

    @SuppressWarnings("unchecked")
    void onClose(Transaction tx, boolean aborted) {
        int depth = tx.depth();
        T snapshot = (T) snapshots.set(depth, NONE);
        if (aborted) {
            revertToSnapshot(snapshot);
            releaseSnapshot(snapshot);
        } else if (tx.parent() != null) {
            // The parent keeps the older snapshot if it has one: that is the state to go back to from there.
            if (snapshots.get(depth - 1) == NONE) {
                snapshots.set(depth - 1, snapshot);
                tx.parent().enlist(this);
            } else {
                releaseSnapshot(snapshot);
            }
        } else {
            original = snapshot;
            tx.committedAtRoot(this);
        }
    }

    @SuppressWarnings("unchecked")
    void rootCommitted() {
        T state = (T) original;
        original = NONE;
        onRootCommit(state);
        releaseSnapshot(state);
    }
}
