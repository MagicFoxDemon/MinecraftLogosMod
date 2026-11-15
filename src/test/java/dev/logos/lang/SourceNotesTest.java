package dev.logos.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Notes written as {@code ;;} comments: what each says, and that the compiled statements find theirs. */
class SourceNotesTest {
    private static final String ANNOTATED = String.join("\n",
            ";; 灼热魔弹",
            ";; 射出一枚魔弹，命中后灼烧目标。",
            "(spell :name \"测试\" :mana 30",
            "  ;; 发射",
            "  ;; 魔弹沿视线飞行。",
            "  ;; 命中实体时执行其后的行动。",
            "  (projectile",
            "    (on-hit",
            "      ; 普通注释不会画进图里",
            "      ;; 灼烧",
            "      (damage target 5 fire))))");

    @Test
    void aBlockIsATitleAndItsText() {
        SourceNotes notes = SourceNotes.read(ANNOTATED);
        SourceNotes.Note head = notes.head();
        assertNotNull(head);
        assertEquals("灼热魔弹", head.title());
        assertEquals("射出一枚魔弹，命中后灼烧目标。", head.text());
        List<SourceNotes.Note> all = notes.all();
        assertEquals(3, all.size());
        assertEquals("发射", all.get(1).title());
        assertEquals("魔弹沿视线飞行。\n命中实体时执行其后的行动。", all.get(1).text());
        // A single line is a title alone; the plain comment between is no part of it.
        assertEquals("灼烧", all.get(2).title());
        assertEquals("", all.get(2).text());
    }

    @Test
    void compiledStatementsFindTheirNotes() {
        SourceNotes notes = SourceNotes.read(ANNOTATED);
        Spell spell = Compiler.compile(ANNOTATED);
        Node projectile = spell.body().getFirst();
        assertEquals("发射", notes.at(projectile).title());
        Node damage = projectile.events.values().iterator().next().getFirst();
        assertEquals("灼烧", notes.at(damage).title());
        notes.head();
        assertEquals(List.of(), notes.unused());
    }

    @Test
    void aNoteBeforeAValueIsLeftUnused() {
        String src = "(spell :mana 20 (projectile :dir\n  ;; 方向\n  (random-dir)))";
        SourceNotes notes = SourceNotes.read(src);
        Spell spell = Compiler.compile(src);
        assertNull(notes.at(spell.body().getFirst()));
        assertEquals(1, notes.unused().size());
        assertEquals("方向", notes.unused().getFirst().title());
    }

    @Test
    void semicolonsInTextAndPlainCommentsAreNotNotes() {
        SourceNotes notes = SourceNotes.read("; 普通注释\n(spell :name \"a;;b\" :mana 10 (projectile))");
        assertTrue(notes.isEmpty());
        assertSame(SourceNotes.NONE, notes);
    }

    @Test
    void aNoteBeforeABareStatementIsThatStatements() {
        String src = ";; 发射\n(projectile (on-hit (damage target 5)))";
        SourceNotes notes = SourceNotes.read(src);
        assertNull(notes.head());
        assertEquals("发射", notes.at(1 + 1, 1).title());
    }
}
