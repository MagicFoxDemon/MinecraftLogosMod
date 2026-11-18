package dev.logos.port;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.transaction.Transaction}: changes made through
 * {@link ResourceHandler}s inside it stay only if it is committed; closing it without committing takes them back.
 * Transactions nest, one thread at a time: a nested one commits into its parent, and only the root's commit is final.
 *
 * <pre>{@code
 * try (Transaction tx = Transaction.openRoot()) {
 *     if (handler.extract(resource, 4, tx) == 4) tx.commit();
 * }
 * }</pre>
 */
public final class Transaction implements AutoCloseable, TransactionContext {
    private static final ThreadLocal<Deque<Transaction>> OPEN = ThreadLocal.withInitial(ArrayDeque::new);

    private final @Nullable Transaction parent;
    private final int depth;
    /** Journals with a snapshot at this depth, in the order they took it. */
    private final List<SnapshotJournal<?>> enlisted = new ArrayList<>();
    /** At the root: journals whose changes its commit makes final. */
    private final List<SnapshotJournal<?>> committed = new ArrayList<>();
    private boolean open = true;

    private Transaction(@Nullable Transaction parent) {
        this.parent = parent;
        this.depth = parent == null ? 0 : parent.depth + 1;
    }

    /** Opens an outermost transaction; none may be open on this thread. */
    public static Transaction openRoot() {
        Deque<Transaction> open = OPEN.get();
        if (!open.isEmpty()) throw new IllegalStateException("A transaction is already open on this thread: nest in it instead");
        Transaction tx = new Transaction(null);
        open.push(tx);
        return tx;
    }

    /** Opens a transaction inside {@code parent}, the innermost open one, or a root one when it is null. */
    public static Transaction open(@Nullable TransactionContext parent) {
        if (parent == null) return openRoot();
        Deque<Transaction> open = OPEN.get();
        if (open.peek() != parent) throw new IllegalStateException("Transactions nest only in the innermost open one");
        Transaction tx = new Transaction((Transaction) parent);
        open.push(tx);
        return tx;
    }

    @Override
    public int depth() {
        return depth;
    }

    public void commit() {
        finish(false);
    }

    /** Takes back what was not committed. */
    @Override
    public void close() {
        if (open) finish(true);
    }

    void validateOpen() {
        if (!open) throw new IllegalStateException("The transaction is closed");
    }

    void enlist(SnapshotJournal<?> journal) {
        enlisted.add(journal);
    }

    void committedAtRoot(SnapshotJournal<?> journal) {
        committed.add(journal);
    }

    @Nullable Transaction parent() {
        return parent;
    }

    private void finish(boolean abort) {
        validateOpen();
        Deque<Transaction> stack = OPEN.get();
        if (stack.peek() != this) throw new IllegalStateException("Close nested transactions first");
        open = false;
        stack.pop();
        for (int i = enlisted.size() - 1; i >= 0; i--) enlisted.get(i).onClose(this, abort);
        enlisted.clear();
        if (parent == null) {
            for (SnapshotJournal<?> journal : committed) journal.rootCommitted();
            committed.clear();
        }
    }

    @Override
    public String toString() {
        return "Transaction[depth=" + depth + (open ? ", open" : ", closed") + "]";
    }
}
