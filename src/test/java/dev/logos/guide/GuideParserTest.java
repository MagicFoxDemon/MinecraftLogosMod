package dev.logos.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import dev.logos.guide.GuideDoc.Span;

/** 逻各斯手记: how a page's Markdown reads, how the book orders and links its pages, and that the shipped pages hold. */
class GuideParserTest {
    @Test
    void theHeaderIsReadAndLeftOutOfThePage() {
        GuideDoc doc = GuideParser.parse("---\ntitle: 第一个术式\nicon: logos:spell_page\norder: 3\n---\n正文。\n");
        assertEquals("第一个术式", doc.meta("title", ""));
        assertEquals("logos:spell_page", doc.meta("icon", ""));
        assertEquals("3", doc.meta("order", ""));
        assertEquals(1, doc.blocks().size());
        assertEquals("正文。", doc.plainText().strip());
    }

    @Test
    void blocksAreHeadingsParagraphsListsRulesAndTags() {
        GuideDoc doc = GuideParser.parse("""
                # 标题
                第一行
                第二行

                - 甲
                - 乙
                  接着乙
                2. 第二步
                ---
                {{items logos:wand "logos:echo_wand"}}
                """);
        var b = doc.blocks();
        assertEquals(7, b.size(), b.toString());
        assertEquals(1, assertInstanceOf(GuideDoc.Heading.class, b.get(0)).level());
        // Chinese lines run on without a space between them.
        assertEquals("第一行第二行", text(assertInstanceOf(GuideDoc.Paragraph.class, b.get(1)).text()));
        assertEquals(0, assertInstanceOf(GuideDoc.Bullet.class, b.get(2)).number());
        assertEquals("乙接着乙", text(assertInstanceOf(GuideDoc.Bullet.class, b.get(3)).text()));
        assertEquals(2, assertInstanceOf(GuideDoc.Bullet.class, b.get(4)).number());
        assertInstanceOf(GuideDoc.Rule.class, b.get(5));
        GuideDoc.Tag tag = assertInstanceOf(GuideDoc.Tag.class, b.get(6));
        assertEquals("items", tag.name());
        assertEquals(List.of("logos:wand", "logos:echo_wand"), tag.args());
    }

    @Test
    void latinLinesRunOnWithASpace() {
        GuideDoc doc = GuideParser.parse("Hold right\nclick to cast.");
        assertEquals("Hold right click to cast.", doc.plainText().strip());
    }

    @Test
    void textStylesAndLinks() {
        List<Span> s = GuideParser.spans("用 **法杖** 施放 *蓄力* 的 `damage`，见[第一个术式](first_spell)和[法杖](item:logos:wand)。");
        assertEquals("用 法杖 施放 蓄力 的 damage，见第一个术式和法杖。", text(s));
        assertTrue(find(s, "法杖").bold());
        assertTrue(find(s, "蓄力").italic());
        assertTrue(find(s, "damage").code());
        assertEquals("first_spell", find(s, "第一个术式").link());
        assertEquals("item:logos:wand", s.stream().filter(x -> "item:logos:wand".equals(x.link())).findFirst().orElseThrow().link());
    }

    @Test
    void whatDoesNotCloseStaysText() {
        assertEquals("3 * 4 = 12", text(GuideParser.spans("3 * 4 = 12")));
        assertEquals("**没有收尾", text(GuideParser.spans("**没有收尾")));
        assertEquals("[只有方括号]", text(GuideParser.spans("[只有方括号]")));
        assertEquals("`半个", text(GuideParser.spans("`半个")));
        assertEquals("*星号*", text(GuideParser.spans("\\*星号\\*")));
        for (Span span : GuideParser.spans("3 * 4 = 12")) assertFalse(span.italic());
    }

    @Test
    void anUnclosedHeaderIsText() {
        GuideDoc doc = GuideParser.parse("---\ntitle: 没有结尾\n正文");
        assertTrue(doc.meta().isEmpty());
        assertFalse(doc.blocks().isEmpty());
    }

    @Test
    void theBookOrdersChaptersAndEntries() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logos:craft/_chapter", "---\ntitle: 炼金\norder: 2\n---\n");
        files.put("logos:craft/table", "---\ntitle: 炼金术桌\n---\n");
        files.put("logos:basics/_chapter", "---\ntitle: 入门\nribbon: 入门\norder: 1\n---\n");
        files.put("logos:basics/second", "---\ntitle: 第二篇\norder: 2\n---\n见[第一篇](first)，[炼金术桌](craft/table)，[断链](nowhere)。");
        files.put("logos:basics/first", "---\ntitle: 第一篇\norder: 1\n---\n");
        files.put("logos:loose", "不在任何章节里");
        GuideBook book = GuideBook.build(files);
        assertEquals(List.of("logos:basics", "logos:craft"), book.chapters().stream().map(GuideBook.Chapter::id).toList());
        assertEquals(List.of("logos:basics/first", "logos:basics/second"), book.chapters().getFirst().entries().stream().map(GuideBook.Entry::id).toList());
        assertEquals("logos:basics/first", book.first().id());
        assertEquals(List.of("logos:basics/second → nowhere"), book.brokenLinks());
        assertNull(book.entry("logos:loose"));
    }

    @Test
    void linksResolveFromWhereTheyAreWritten() {
        assertEquals("logos:basics/first", GuideBook.resolve("logos:basics/second", "first"));
        assertEquals("logos:craft/table", GuideBook.resolve("logos:basics/second", "craft/table.md"));
        assertEquals("addon:lore/fox", GuideBook.resolve("logos:basics/second", "addon:lore/fox"));
    }

    @Test
    void searchFindsTitlesBeforeText() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logos:a/one", "---\ntitle: 法杖\n---\n");
        files.put("logos:a/two", "---\ntitle: 载页\n---\n载页挂在法杖上。");
        files.put("logos:a/three", "---\ntitle: 魔力\n---\n");
        GuideBook book = GuideBook.build(files);
        assertEquals(List.of("logos:a/one", "logos:a/two"), book.search("法杖").stream().map(GuideBook.Entry::id).toList());
        assertTrue(book.search("  ").isEmpty());
    }

    /** Every page shipped in Chinese: its links lead to pages that exist and its tags are ones the book draws. */
    @Test
    void theShippedPagesHold() throws IOException {
        Path root = shipped();
        Map<String, String> files = new LinkedHashMap<>();
        List<Path> pages;
        try (Stream<Path> walk = Files.walk(root)) {
            pages = walk.filter(p -> p.toString().endsWith(".md")).toList();
        }
        for (Path p : pages) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            files.put("logos:" + rel.substring(0, rel.length() - 3), Files.readString(p, StandardCharsets.UTF_8));
        }
        GuideBook book = GuideBook.build(files);
        assertFalse(book.isEmpty(), "no pages under " + root);
        assertEquals(List.of(), book.brokenLinks());
        List<String> unknown = new ArrayList<>();
        for (var chapter : book.chapters()) {
            for (var entry : chapter.entries()) {
                assertFalse(entry.title().equals(entry.id().substring(entry.id().lastIndexOf('/') + 1)), entry.id() + " has no title");
                for (var block : entry.doc().blocks()) {
                    if (block instanceof GuideDoc.Tag t && !GuideBook.TAGS.contains(t.name())) unknown.add(entry.id() + ": " + t.name());
                }
            }
        }
        assertEquals(List.of(), unknown);
    }

    private static Path shipped() {
        Path relative = Path.of("src/main/resources/assets/logos/guide/zh_cn");
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(relative))) return dir.resolve(relative);
        }
        throw new IllegalStateException("cannot find " + relative);
    }

    private static String text(List<Span> spans) {
        StringBuilder out = new StringBuilder();
        for (Span s : spans) out.append(s.text());
        return out.toString();
    }

    private static Span find(List<Span> spans, String text) {
        return spans.stream().filter(s -> s.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError(text + " not in " + spans));
    }
}
