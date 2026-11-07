package dev.logos.compat.aiplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import dev.logos.item.PageTier;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;

class SpellTextTest {
    @Test
    void summaryTellsAnAiWhatAnExampleDoesAndWhichPageItNeeds() {
        JsonObject s = SpellText.summary(Compiler.compile(Examples.ALL.get("hello")));
        assertEquals("启蒙魔弹", s.get("name").getAsString());
        assertEquals(10, s.get("mana").getAsDouble());
        assertEquals("cast", s.get("trigger").getAsString());
        assertEquals("主动施放", s.get("trigger_zh").getAsString());
        assertEquals("strike", s.getAsJsonArray("intents").get(0).getAsString());
        assertEquals("basic", s.get("page_grade").getAsString());
        assertEquals("logos:spell_page", s.get("page_item").getAsString());
    }

    @Test
    void noteIsTheLeadingCommentOfAnExample() {
        assertEquals("最简单的术式：弹丸 → 命中 → 伤害", SpellText.note(Examples.ALL.get("hello")));
        assertTrue(SpellText.note(Examples.ALL.get("prismatic-volley")).startsWith("虹彩飞矢"));
        assertEquals("", SpellText.note("(spell :name \"x\" :mana 1 (projectile))"));
    }

    @Test
    void fitPicksTheLowestGradeThatHoldsTheSpellElseTheHighestCarried() {
        assertEquals(PageTier.ECHO, SpellText.fit(List.of(PageTier.BASIC, PageTier.ECHO, PageTier.CHALK), 100));
        assertEquals(PageTier.BASIC, SpellText.fit(List.of(PageTier.ECHO, PageTier.BASIC), 10));
        assertEquals(PageTier.CRYSTAL, SpellText.fit(List.of(PageTier.BASIC, PageTier.CRYSTAL), 1000));
        assertNull(SpellText.fit(List.of(), 1));
    }
}
