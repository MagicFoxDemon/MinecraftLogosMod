package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class SpellCacheTest {
    private static final String BROKEN = "(spell :name \"x\" (no-such-rune))";

    private static String good(int i) {
        return "(spell :name \"s" + i + "\" :mana 5 (heal caster 1))";
    }

    /** The real compiler, counting how many times it was asked. */
    private static Function<String, Spell> counting(AtomicInteger compiles) {
        return src -> {
            compiles.incrementAndGet();
            return Compiler.compile(src);
        };
    }

    @Test
    void aGoodSpellCompilesOnce() {
        AtomicInteger compiles = new AtomicInteger();
        SpellCache cache = new SpellCache(counting(compiles));
        Spell first = cache.compile(good(0));
        assertSame(first, cache.compile(good(0)));
        assertSame(first, cache.read(good(0), e -> { throw new AssertionError("it compiled"); }));
        assertEquals(1, compiles.get());
    }

    @Test
    void aFailureCompilesOnceAndIsTheSameError() {
        AtomicInteger compiles = new AtomicInteger();
        SpellCache cache = new SpellCache(counting(compiles));
        LogosError first = assertThrows(LogosError.class, () -> cache.compile(BROKEN));
        for (int i = 0; i < 100; i++) assertSame(first, assertThrows(LogosError.class, () -> cache.compile(BROKEN)));
        assertEquals(1, compiles.get());
    }

    @Test
    void aFailureIsReportedOnce() {
        AtomicInteger compiles = new AtomicInteger(), reports = new AtomicInteger();
        SpellCache cache = new SpellCache(counting(compiles));
        for (int i = 0; i < 100; i++) assertNull(cache.read(BROKEN, e -> reports.incrementAndGet()));
        assertEquals(1, compiles.get());
        assertEquals(1, reports.get());
        // Having been thrown to someone typing it is not having been reported as a page gone bad, and vice versa.
        assertThrows(LogosError.class, () -> cache.compile(BROKEN));
        assertEquals(1, reports.get());
    }

    @Test
    void invalidatingForgetsSuccessesAndFailures() {
        AtomicInteger compiles = new AtomicInteger();
        AtomicBoolean accepted = new AtomicBoolean(false);
        SpellCache cache = new SpellCache(src -> {
            compiles.incrementAndGet();
            if (!accepted.get()) throw new LogosError("not yet", 0, 0);
            return Compiler.compile(good(0));
        });
        int before = cache.generation();
        assertNull(cache.read("a", e -> {}));
        accepted.set(true);
        assertNull(cache.read("a", e -> {}), "the failure should still be remembered");
        assertEquals(1, compiles.get());
        cache.invalidate();
        assertNotEquals(before, cache.generation());
        assertNotNull(cache.read("a", e -> {}));
        assertEquals(2, compiles.get());
    }

    @Test
    void aCompileOverlappingAnInvalidationIsNotKept() {
        AtomicInteger compiles = new AtomicInteger();
        SpellCache[] cache = new SpellCache[1];
        cache[0] = new SpellCache(src -> {
            if (compiles.incrementAndGet() == 1) cache[0].invalidate();
            return Compiler.compile(good(0));
        });
        assertNotNull(cache[0].compile("a"));
        assertNotNull(cache[0].compile("a"));
        assertEquals(2, compiles.get());
        assertNotNull(cache[0].compile("a"));
        assertEquals(2, compiles.get());
    }

    /** The old limit was 128: a server with more spells than that recompiled them from its HUD and emitters. */
    @Test
    void manyDistinctSpellsDoNotChurn() {
        AtomicInteger compiles = new AtomicInteger();
        SpellCache cache = new SpellCache(counting(compiles));
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < SpellCache.MAX_ENTRIES; i++) cache.compile(good(i));
        }
        assertEquals(SpellCache.MAX_ENTRIES, compiles.get());
    }

    @Test
    void theLeastRecentlyReadGoesFirstByCount() {
        AtomicInteger compiles = new AtomicInteger();
        SpellCache cache = new SpellCache(counting(compiles), 2, Long.MAX_VALUE);
        cache.compile(good(0));
        cache.compile(good(1));
        cache.compile(good(0));
        cache.compile(good(2));
        assertEquals(3, compiles.get());
        cache.compile(good(0));
        assertEquals(3, compiles.get());
        cache.compile(good(1));
        assertEquals(4, compiles.get());
    }

    @Test
    void theBoundOnLengthEvictsAndKeepsTheNewest() {
        AtomicInteger compiles = new AtomicInteger();
        int one = good(0).length();
        SpellCache cache = new SpellCache(counting(compiles), 100, one * 2L);
        cache.compile(good(0));
        cache.compile(good(1));
        cache.compile(good(2));
        assertEquals(3, compiles.get());
        cache.compile(good(0));
        assertEquals(4, compiles.get(), "the third should have pushed the first out");

        // Longer than the whole bound: it stays until something else comes along.
        String huge = "(spell :name \"" + "x".repeat(one * 3) + "\" (heal caster 1))";
        cache.compile(huge);
        cache.compile(huge);
        assertEquals(5, compiles.get());
    }

    /** A client thread hovering and a server thread ticking, with reloads thrown in; tight bounds force constant relinking. */
    @Test
    void readersAndWritersOnManyThreadsStayConsistent() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            SpellCache cache = new SpellCache(Compiler::compile, 16, Long.MAX_VALUE);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> done = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    done.add(pool.submit(() -> {
                        go.await();
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        for (int i = 0; i < 20_000; i++) {
                            int n = random.nextInt(64);
                            if (n % 8 == 0) {
                                assertNull(cache.read("(no-such-rune-" + n + ")", e -> {}));
                            } else {
                                String src = good(n);
                                assertEquals(src, cache.compile(src).source());
                            }
                            if (random.nextInt(2000) == 0) cache.invalidate();
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : done) f.get();
            } finally {
                pool.shutdownNow();
            }
            // Still a working cache after all that.
            assertTrue(cache.compile(good(1)).source().equals(good(1)));
        });
    }
}
