package dev.logos;

import static dev.logos.CompiledClasses.MOD;
import static dev.logos.CompiledClasses.packageOf;
import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Which of the mod's packages may use which (CONTRIBUTING, 包之间的依赖方向). The language stands on its own, the
 * node graph on the language, and what plays the game on both; screens and the client sit on top. Several of the
 * packages that play the game do use each other both ways, and this holds them to the ways they already do: a
 * package starting to use one it never did is how a tangle grows.
 */
class PackageRulesTest {
    /**
     * These may use anything: {@code dev.logos} itself (the mod's entry, which registers everything, and its settings),
     * the client, the optional other-mod compats and the in-world tests. What may use the client and the tests is for
     * {@link ClientBoundaryTest} and {@link TestCodeStaysOutTest}; nothing below lists {@code compat}, so nothing may use it.
     */
    private static final Set<String> ON_TOP = Set.of("", "client", "compat", "gametest");
    /** Uses of these are not this test's to judge: anything may use the entry and settings and the 1.21.1 stand-ins (port), and the client and tests have their own tests. */
    private static final Set<String> NOT_JUDGED = Set.of("", "client", "gametest", "port");

    /** Each other package, and the packages it uses. */
    private static final Map<String, Set<String>> MAY_USE = Map.ofEntries(
            entry("guide", Set.of()),
            // The 1.21.1 branch's stand-ins for the 26.1 API (dev.logos.port): anything may use them, and they use nothing of the mod's.
            entry("port", Set.of()),
            // Only its rune files reach out; see languageCoreStandsAlone.
            entry("lang", Set.of("runtime", "block")),
            entry("graph", Set.of("lang")),
            entry("sage", Set.of("lang", "net", "runtime")),
            entry("runtime", Set.of("lang", "item", "net", "mixin")),
            entry("item", Set.of("lang", "runtime")),
            entry("alchemy", Set.of("lang", "runtime", "item", "block", "mixin")),
            entry("block", Set.of("lang", "graph", "runtime", "item", "alchemy", "net", "ui")),
            entry("net", Set.of("lang", "graph", "runtime", "item", "block", "alchemy", "sage")),
            entry("command", Set.of("lang", "runtime", "item")),
            // 1.21.1: no mixin names the language (26.1's weather-attribute mixin did).
            entry("mixin", Set.of("runtime")),
            entry("ui", Set.of("runtime", "alchemy")));

    /**
     * In {@code lang}, the files that may use other packages: a rune's declaration sits with what it does, in {@code Rune},
     * {@code Runes} and the {@code …Runes} family files, and these two work out a spell's price from what the runtime charges.
     */
    private static final Set<String> PRICING = Set.of("SpellProfile", "ExecuteCost");

    @Test
    void packagesUseOnlyWhatTheyMay() throws IOException {
        Map<String, Set<String>> found = new TreeMap<>();
        CompiledClasses.references().forEach((from, named) -> {
            String pkg = packageOf(from);
            if (ON_TOP.contains(pkg)) return;
            Set<String> uses = found.computeIfAbsent(pkg, k -> new TreeSet<>());
            for (String to : named) {
                if (!to.startsWith(MOD)) continue;
                String used = packageOf(to);
                if (!used.equals(pkg) && !NOT_JUDGED.contains(used)) uses.add(used);
            }
        });
        Map<String, Set<String>> allowed = new TreeMap<>();
        MAY_USE.forEach((pkg, uses) -> allowed.put(pkg, new TreeSet<>(uses)));
        assertEquals(describe(allowed), describe(found), """
                A package uses one it did not use before (or no longer uses one this test lists).
                See CONTRIBUTING, 包之间的依赖方向. A new direction between packages is how they grow into a tangle:
                look first for a way round, such as moving the code to the package that should own it, or an interface
                in the lower package that the higher one implements. If the new use is right, or one has gone,
                update MAY_USE in this test and the table in CONTRIBUTING.
                Left: what this test allows. Right: what the compiled classes hold.""");
    }

    @Test
    void languageCoreStandsAlone() throws IOException {
        Map<String, Set<String>> reachingOut = new TreeMap<>();
        CompiledClasses.references().forEach((from, named) -> {
            if (!packageOf(from).equals("lang") || runeFile(from.substring(from.lastIndexOf('/') + 1))) return;
            for (String to : named) {
                if (!to.startsWith(MOD)) continue;
                String used = packageOf(to);
                if (!used.equals("lang") && !used.isEmpty()) reachingOut.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
            }
        });
        assertEquals("", describe(reachingOut), """
                The language itself (parser, compiler, types) uses a class from another package.
                Only the rune files do that: what a rune does belongs in its …Runes file, not in the compiler.""");
    }

    private static boolean runeFile(String simpleName) {
        return simpleName.equals("Rune") || simpleName.endsWith("Runes") || PRICING.contains(simpleName);
    }

    private static String describe(Map<String, Set<String>> edges) {
        StringBuilder out = new StringBuilder();
        edges.forEach((from, to) -> out.append(from).append(" -> ").append(String.join(", ", List.copyOf(to))).append('\n'));
        return out.toString();
    }
}
