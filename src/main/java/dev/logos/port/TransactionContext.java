package dev.logos.port;

/** Stands in for 26.1's {@code net.neoforged.neoforge.transfer.transaction.TransactionContext}: an open {@link Transaction}, as handlers see it. */
public sealed interface TransactionContext permits Transaction {
    /** 0 for a root transaction, one more for each level of nesting. */
    int depth();
}
