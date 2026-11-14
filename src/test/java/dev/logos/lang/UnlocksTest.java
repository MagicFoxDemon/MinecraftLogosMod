package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 符文解锁: which words a spell writes, so a page can be held to what its writer has learned. */
class UnlocksTest {
    private static Map<String, Integer> written(String src) {
        return Unlocks.written(Compiler.compile(src));
    }

    private static List<String> beyondStart(String src) {
        return Unlocks.beyond(Compiler.compile(src), null, Unlocks.STARTING::contains);
    }

    @Test
    void theFirstSpellNeedsNothingLearned() {
        assertEquals(List.of(), beyondStart("(spell :name \"魔弹\" (projectile (on-hit (damage target 5))))"));
        assertEquals(List.of(), beyondStart("(projectile :motion forward (on-hit (damage target 5 physical)))"));
    }

    @Test
    void lockedWordsAreCountedInOrder() {
        String src = "(projectile :motion arc (on-hit (damage target 5 fire) (freeze target 2) (freeze target 1)))";
        Map<String, Integer> w = written(src);
        assertEquals(1, w.get("rune:projectile"));
        assertEquals(1, w.get("motion:arc"));
        assertEquals(1, w.get("element:fire"));
        assertEquals(2, w.get("rune:freeze"));
        assertEquals(List.of("motion:arc", "element:fire", "rune:freeze"), beyondStart(src));
    }

    @Test
    void defaultsTheWriterLeftOutAreNotCounted() {
        Map<String, Integer> w = written("(projectile (on-hit (damage target 5)))");
        assertFalse(w.containsKey("motion:forward"), "the projectile's default motion was counted");
        assertFalse(w.containsKey("element:physical"), "damage's default element was counted");
        assertFalse(written("(if (alive caster) (heal caster 1))").containsKey("rune:do"), "if's empty else was counted");
        assertFalse(written("(dismiss)").containsKey("rune:familiar"), "dismiss's own familiar was counted");
        assertEquals(1, written("(dismiss (familiar))").get("rune:familiar"));
    }

    @Test
    void whatTheEditorWrapsAroundBranchesAndYieldsIsNotCounted() {
        assertFalse(written("(if (alive caster) (do (heal caster 1)) (do (damage caster 1)))").containsKey("rune:do"));
        assertEquals(1, written("(if (alive caster) (do (do (heal caster 1))))").get("rune:do"));
        assertEquals(1, written("(do (heal caster 1))").get("rune:do"));
        assertFalse(written("(let $f (field :life 2) (delay 1 (heal caster (life-of $f))))").containsKey("rune:let"));
        assertEquals(1, written("(let $x 3 (damage caster $x))").get("rune:let"));
    }

    @Test
    void aPageKeepsWhatItAlreadySaid() {
        Spell found = Compiler.compile("(projectile (on-hit (freeze target 2)))");
        Spell same = Compiler.compile("(projectile :speed 2 (on-hit (freeze target 3) (damage target 1)))");
        Spell more = Compiler.compile("(projectile (on-hit (freeze target 2) (freeze caster 1)))");
        assertEquals(List.of(), Unlocks.beyond(same, found, Unlocks.STARTING::contains));
        assertEquals(List.of("rune:freeze"), Unlocks.beyond(more, found, Unlocks.STARTING::contains));
        assertEquals(List.of(), Unlocks.beyond(more, null, k -> true));
    }

    @Test
    void lifeIsWrittenLikeAnyElementButNeverDrawnAtRandom() {
        assertEquals(List.of("element:life"), beyondStart("(projectile (on-hit (damage target 2 life)))"));
        assertTrue(Unlocks.NOT_RANDOM.contains(Unlocks.element(Element.LIFE)));
    }

    @Test
    void everyRuneElementAndMotionCanBeLearnedAndNothingElse() {
        for (Rune r : Runes.all()) assertTrue(Unlocks.lockable(Unlocks.rune(r.name)), r.name);
        for (Element e : Element.values()) assertTrue(Unlocks.lockable(Unlocks.element(e)), e.name());
        for (Motion m : Motion.values()) assertTrue(Unlocks.lockable(Unlocks.motion(m)), m.name());
        assertFalse(Unlocks.lockable("rune:spell"));
        assertFalse(Unlocks.lockable("shape:wall"));
        assertEquals(Runes.all().size() + Element.values().length + Motion.values().length, Unlocks.all().size());
        assertTrue(Unlocks.all().containsAll(Unlocks.STARTING));
    }
}
