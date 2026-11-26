package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 寄托形体: another mod's form, carried by its own things (a round, an arrow), written like any form with events. */
class HostedFormTest {
    static final String WORD = "test-shell";

    static {
        if (Runes.get(WORD) == null) {
            Runes.hosted(WORD, "测试用的寄托形体。",
                    new Rune.EventSpec("on-hit", true, "命中生物时"),
                    new Rune.EventSpec("on-impact", false, "打在方块上时"),
                    new Rune.EventSpec("on-expire", false, "什么都没打中时"));
        }
    }

    @Test
    void itIsAFormWithOnlyItsEvents() {
        Rune r = Runes.get(WORD);
        assertTrue(r.hosted);
        assertEquals(Category.FORM, r.category);
        assertEquals(Type.ACTION, r.returns);
        assertTrue(r.params.isEmpty() && r.keywords.isEmpty());
        assertEquals(java.util.List.of("on-hit", "on-impact", "on-expire"), java.util.List.copyOf(r.events.keySet()));
        assertEquals(Runes.HOSTED_STABILITY, r.stability);
    }

    @Test
    void aSpellIsWrittenAroundItWithItsEvents() {
        Spell s = Compiler.compile("(spell :name \"爆裂弹\" :mana 20 (" + WORD + " (on-hit (damage target 5 fire)) (on-impact (damage-all (enemies :radius 3) 2))))");
        assertEquals(1, s.body().size());
        Node form = s.body().getFirst();
        assertEquals(WORD, form.rune.name);
        assertNotNull(form.events.get("on-hit"));
        assertNotNull(form.events.get("on-impact"));
        assertFalse(form.events.containsKey("on-expire"));
    }

    @Test
    void itTakesNoArgumentsAndOnlyItsOwnEvents() {
        assertThrows(LogosError.class, () -> Compiler.compile("(" + WORD + " 3)"));
        assertThrows(LogosError.class, () -> Compiler.compile("(" + WORD + " :speed 2)"));
        assertThrows(LogosError.class, () -> Compiler.compile("(" + WORD + " (on-tick (damage target 1)))"));
        // Only on-hit binds a target.
        assertThrows(LogosError.class, () -> Compiler.compile("(" + WORD + " (on-impact (damage target 5)))"));
    }

    @Test
    void anyoneMayWriteItAndNoBookTeachesIt() {
        String key = Unlocks.rune(WORD);
        assertFalse(Unlocks.lockable(key));
        assertFalse(Unlocks.all().contains(key));
        // Written like any word, but one that is not locked counts as known (as RuneKnowledge.knows has it).
        Spell s = Compiler.compile("(" + WORD + " (on-hit (damage target 5)))");
        assertEquals(1, Unlocks.written(s).get(key));
        assertTrue(Unlocks.beyond(s, null, k -> Unlocks.STARTING.contains(k) || !Unlocks.lockable(k)).isEmpty(),
                "a shell with physical damage needs nothing learned");
    }

    @Test
    void eachEventIsReckonedOnce() {
        Spell hosted = Compiler.compile("(spell :mana 20 (" + WORD + " (on-hit (damage target 5 fire))))");
        Spell plain = Compiler.compile("(spell :mana 20 (damage (foe) 5 fire))");
        assertEquals(plain.profile().estimate(), hosted.profile().estimate(), 1e-9);
        assertTrue(hosted.profile().assumptions().contains("寄托形体的每个事件按触发一次估算"));
        assertEquals(Intent.STRIKE, hosted.profile().primary());
    }

    @Test
    void registeringIsCheckedUpFront() {
        Rune.EventSpec hit = new Rune.EventSpec("on-hit", true, "");
        assertThrows(IllegalArgumentException.class, () -> Runes.hosted("projectile", "", hit), "a rune's name is taken");
        assertThrows(IllegalArgumentException.class, () -> Runes.hosted("Bad Name", "", hit));
        assertThrows(IllegalArgumentException.class, () -> Runes.hosted("test-empty", ""), "a form with no events");
        assertThrows(IllegalArgumentException.class, () -> Runes.hosted("test-odd", "", new Rune.EventSpec("hit", true, "")));
        assertTrue(Runes.get("test-empty") == null && Runes.get("test-odd") == null, "a refused form is not half registered");
    }
}
