package dev.logos.lang;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Compiled spells by source, failures included, so that reading a page does not parse it again. In
 * single player the client and server threads share one of these; that is safe because the compiler
 * never changes a {@link Spell} after returning it. Bounded by count and by total source length, since
 * one source can run to tens of thousands of characters.
 */
public final class SpellCache {
    public static final int MAX_ENTRIES = 1024;
    /** Half of what the old limit of 128 entries could hold at 32,000 characters each. */
    public static final long MAX_CHARS = 2_000_000;

    /** Exactly one of {@code spell} and {@code error} is set. */
    private record Entry(@Nullable Spell spell, @Nullable LogosError error, AtomicBoolean reported) {}

    private final Function<String, Spell> compiler;
    private final int maxEntries;
    private final long maxChars;
    /** Least recently read first. Guarded by {@code this}: even a read relinks it. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long chars;
    private volatile int generation;

    public SpellCache(Function<String, Spell> compiler) {
        this(compiler, MAX_ENTRIES, MAX_CHARS);
    }

    SpellCache(Function<String, Spell> compiler, int maxEntries, long maxChars) {
        this.compiler = compiler;
        this.maxEntries = maxEntries;
        this.maxChars = maxChars;
    }

    /** @throws LogosError on syntax/type errors; a remembered failure is the same error again */
    public Spell compile(String src) {
        Entry e = entry(src);
        if (e.error != null) throw e.error;
        return e.spell;
    }

    /** The spell, or null if it does not compile; {@code firstFailure} hears of each remembered failure once. */
    public @Nullable Spell read(String src, Consumer<LogosError> firstFailure) {
        Entry e = entry(src);
        if (e.error != null && e.reported.compareAndSet(false, true)) firstFailure.accept(e.error);
        return e.spell;
    }

    /** Forgets everything: what compiles has changed. */
    public synchronized void invalidate() {
        entries.clear();
        chars = 0;
        generation++;
    }

    /** Changes with each {@link #invalidate}, so whoever keeps a result knows when to ask again. */
    public int generation() {
        return generation;
    }

    private Entry entry(String src) {
        int startedAt;
        synchronized (this) {
            Entry hit = entries.get(src);
            if (hit != null) return hit;
            startedAt = generation;
        }
        // Outside the lock: a long compile must not hold up the other thread's reads.
        Entry made;
        try {
            made = new Entry(compiler.apply(src), null, new AtomicBoolean());
        } catch (LogosError e) {
            made = new Entry(null, e, new AtomicBoolean());
        }
        synchronized (this) {
            // Invalidated meanwhile: this answer may already be out of date, so it is not kept.
            if (startedAt != generation) return made;
            Entry raced = entries.putIfAbsent(src, made);
            if (raced != null) return raced;
            chars += src.length();
            evict();
            return made;
        }
    }

    /** Drops the least recently read, always keeping the newest however long it is. */
    private void evict() {
        Iterator<Map.Entry<String, Entry>> eldest = entries.entrySet().iterator();
        while (entries.size() > 1 && (entries.size() > maxEntries || chars > maxChars)) {
            chars -= eldest.next().getKey().length();
            eldest.remove();
        }
    }
}
