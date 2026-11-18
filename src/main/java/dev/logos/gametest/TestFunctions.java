package dev.logos.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The tests' bodies by name. 1.21.1 has no registry of test functions, so {@link LogosGameTests} keeps them here and
 * turns them into test functions when the game asks for its tests.
 */
final class TestFunctions {
    private final Map<String, Supplier<? extends Consumer<GameTestHelper>>> byName = new LinkedHashMap<>();

    void register(String name, Supplier<? extends Consumer<GameTestHelper>> body) {
        if (byName.putIfAbsent(name, body) != null) throw new IllegalArgumentException("Duplicate test " + name);
    }

    Consumer<GameTestHelper> get(String name) {
        Supplier<? extends Consumer<GameTestHelper>> body = byName.get(name);
        if (body == null) throw new IllegalStateException("No test function for " + name);
        return body.get();
    }
}
