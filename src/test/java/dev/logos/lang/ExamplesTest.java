package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The example course: grouped in order, named, explained by its notes, and each one does what it is for. */
class ExamplesTest {
    /** Runes that hurt what they reach. */
    private static final Set<String> HARM = Set.of("damage", "damage-all", "drain", "execute", "ignite", "ignite-all", "lightning");

    @Test
    void examplesComeInCourseOrderAndEveryGroupHasSome() {
        int last = 0;
        for (String id : Examples.ALL.keySet()) {
            String group = Examples.group(id);
            assertNotNull(group, id + " has no group");
            int at = Examples.GROUPS.indexOf(group);
            assertTrue(at >= last, id + " is listed after a later group");
            last = at;
        }
        assertEquals(Set.copyOf(Examples.GROUPS), Examples.ALL.keySet().stream().map(Examples::group).collect(Collectors.toSet()));
    }

    @Test
    void everyExampleAndGroupIsNamedInBothLanguages() throws IOException {
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject keys = JsonParser.parseString(Files.readString(langFile(lang))).getAsJsonObject();
            for (String id : Examples.ALL.keySet()) assertTrue(keys.has("logos.example." + id), lang + " has no name for " + id);
            for (String group : Examples.GROUPS) assertTrue(keys.has("logos.example.group." + group), lang + " has no name for group " + group);
            for (String key : keys.keySet()) {
                if (!key.startsWith("logos.example.") || key.startsWith("logos.example.group.")) continue;
                assertTrue(Examples.ALL.containsKey(key.substring("logos.example.".length())), lang + " still names a removed example: " + key);
            }
        }
    }

    @Test
    void everyExampleOpensWithAnOverview() {
        for (var e : Examples.ALL.entrySet()) {
            SourceNotes.Note head = SourceNotes.read(e.getValue()).head();
            assertNotNull(head, e.getKey() + " has no overview note before (spell");
            assertFalse(head.text().isBlank(), e.getKey() + "'s overview has a title and nothing else");
        }
    }

    /** A player who casts an attack example and sees nothing get hurt would take it for a bug. */
    @Test
    void attackExamplesHurtWhatTheyHit() {
        for (var e : Examples.ALL.entrySet()) {
            if (!List.of(Examples.BASICS, Examples.ADVANCED, Examples.GRAND).contains(Examples.group(e.getKey()))) continue;
            Spell s = Compiler.compile(e.getValue());
            if (s.profile().primary() == Intent.HEAL || s.profile().primary() == Intent.MOVE) continue;
            assertTrue(uses(s.body(), HARM), e.getKey() + " is an attack example that hurts nothing");
        }
    }

    /** What a cast is estimated to spend fits in what it injects, so an example does not run dry halfway. */
    @Test
    void examplesInjectWhatTheyAreEstimatedToSpend() {
        for (var e : Examples.ALL.entrySet()) {
            Spell s = Compiler.compile(e.getValue());
            // The estimate adds up every branch of a switch, though only one of them runs.
            if (uses(s.body(), Set.of("switch"))) continue;
            assertTrue(s.profile().estimate() <= s.mana(), e.getKey() + " is estimated at " + s.profile().estimate() + " but injects " + s.mana());
        }
    }

    private static boolean uses(Collection<Node> nodes, Set<String> runes) {
        for (Node n : nodes) {
            if (n == null) continue;
            if (n.rune != null && runes.contains(n.rune.name)) return true;
            if (uses(n.args, runes) || uses(n.kw.values(), runes) || uses(n.body, runes)) return true;
            for (List<Node> handler : n.events.values()) if (uses(handler, runes)) return true;
        }
        return false;
    }

    private static Path langFile(String lang) {
        Path relative = Path.of("src/main/resources/assets/logos/lang/" + lang + ".json");
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(relative))) return dir.resolve(relative);
        }
        throw new IllegalStateException("no " + relative + " above " + Path.of("").toAbsolutePath());
    }
}
