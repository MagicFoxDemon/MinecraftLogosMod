package dev.logos.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * 符文解锁: the words of the language a player learns before they may write them. A key names one word, as
 * {@code rune:freeze}, {@code element:fire} or {@code motion:arc}. Only runes, elements and motions are locked so far;
 * the invocation, the context words and the other enums (shapes, weather, creatures…) stay open, and locking one later
 * is one more kind of key here. What a player has learned is kept with them ({@code runtime.RuneKnowledge}); this
 * class only says what a spell writes, so a page can be held to it.
 */
public final class Unlocks {
    private Unlocks() {}

    public static String rune(String name) {
        return "rune:" + name;
    }

    public static String element(Element e) {
        return "element:" + e.name().toLowerCase(Locale.ROOT);
    }

    public static String motion(Motion m) {
        return "motion:" + m.name().toLowerCase(Locale.ROOT);
    }

    /** Known from the start: 魔弹, 伤害, the physical element and the straight path. */
    public static final Set<String> STARTING = Set.of(rune("projectile"), rune("damage"), element(Element.PHYSICAL), motion(Motion.FORWARD));

    /** Never drawn by anything that unlocks at random: other mods' creatures deal life, and in Logos alone only a command gives it. */
    public static final Set<String> NOT_RANDOM = Set.of(element(Element.LIFE));

    private static @Nullable List<String> all;
    private static @Nullable Set<String> lockable;

    /** Every key a player can learn: the runes family by family, then the elements, then the motions. */
    public static synchronized List<String> all() {
        if (all == null) {
            List<String> keys = new ArrayList<>();
            for (Category c : Category.values()) {
                if (c == Category.INVOCATION) continue;
                for (Rune r : Runes.all()) if (r.category == c) keys.add(rune(r.name));
            }
            for (Element e : Element.values()) keys.add(element(e));
            for (Motion m : Motion.values()) keys.add(motion(m));
            all = Collections.unmodifiableList(keys);
            lockable = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        }
        return all;
    }

    /** Whether {@code key} is one a player must learn (a word that is not locked is always known). */
    public static boolean lockable(String key) {
        all();
        return lockable.contains(key);
    }

    /**
     * How many times {@code spell} writes each lockable word. What the compiler or the graph editor puts in on its own
     * is not counted: slots left to their defaults (an if's empty else, a dismiss's own familiar), the {@code (do …)}
     * a branch is wrapped in, and the {@code let} that keeps what a form or summon made for what follows it.
     */
    public static Map<String, Integer> written(Spell spell) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Node n : spell.body()) walk(n, false, counts);
        return counts;
    }

    /**
     * The words {@code spell} writes more often than {@code before} did (null: a blank page) that {@code known} does not
     * accept, in the order first written. A page found or given keeps what it says when rewritten; nothing is added.
     */
    public static List<String> beyond(Spell spell, @Nullable Spell before, Predicate<String> known) {
        Map<String, Integer> had = before == null ? Map.of() : written(before);
        List<String> out = new ArrayList<>();
        written(spell).forEach((key, n) -> {
            if (n > had.getOrDefault(key, 0) && !known.test(key)) out.add(key);
        });
        return out;
    }

    private static void walk(Node n, boolean glue, Map<String, Integer> into) {
        switch (n.kind) {
            case LIT -> {
                if (n.value instanceof Element e) into.merge(element(e), 1, Integer::sum);
                else if (n.value instanceof Motion m) into.merge(motion(m), 1, Integer::sum);
            }
            case CALL -> {
                Rune r = n.rune;
                boolean keeps = r.name.equals("let") && n.arg(1) != null && n.arg(1).kind == Node.Kind.CALL && n.arg(1).rune.yields != null;
                if (!glue && !keeps) into.merge(rune(r.name), 1, Integer::sum);
                for (int i = 0; i < n.args.size(); i++) {
                    if (n.defaultedArgs.contains(i)) continue;
                    Node a = n.args.get(i);
                    Rune.Param p = r.params.isEmpty() ? null : r.params.get(Math.min(i, r.params.size() - 1));
                    walk(a, p != null && p.type() == Type.ACTION && a.kind == Node.Kind.CALL && a.rune.name.equals("do"), into);
                }
                for (var e : n.kw.entrySet()) if (!n.defaulted.contains(e.getKey())) walk(e.getValue(), false, into);
                for (Node b : n.body) walk(b, false, into);
                for (List<Node> handler : n.events.values()) for (Node b : handler) walk(b, false, into);
            }
            default -> {
            }
        }
    }
}
