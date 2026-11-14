package dev.logos.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One page of 逻各斯手记 as written: the fields of its header and its blocks, read from the guide's small
 * Markdown by {@link GuideParser}. Nothing here knows Minecraft; the client turns the blocks into elements.
 */
public record GuideDoc(Map<String, String> meta, List<Block> blocks) {
    public sealed interface Block permits Heading, Paragraph, Bullet, Tag, Rule {}

    /** A heading, {@code level} 1 to 3. */
    public record Heading(int level, List<Span> text) implements Block {}

    public record Paragraph(List<Span> text) implements Block {}

    /** A list item: {@code number} is its number in an ordered list, 0 in a plain one. */
    public record Bullet(int number, List<Span> text) implements Block {}

    /** A line {@code {{name arg …}}}: something drawn rather than written, such as a row of items or a recipe. */
    public record Tag(String name, List<String> args) implements Block {}

    /** A line {@code ---}: a rule across the page. */
    public record Rule() implements Block {}

    /** A run of text in one style; {@code link} is where it leads when clicked, as written: an entry, or {@code item:<id>}. */
    public record Span(String text, boolean bold, boolean italic, boolean code, @Nullable String link) {
        public static Span plain(String text) {
            return new Span(text, false, false, false, null);
        }
    }

    public String meta(String key, String fallback) {
        String v = meta.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    /** The page's words without markup, for search. */
    public String plainText() {
        StringBuilder out = new StringBuilder();
        for (Block b : blocks) {
            List<Span> spans = switch (b) {
                case Heading h -> h.text();
                case Paragraph p -> p.text();
                case Bullet l -> l.text();
                default -> List.of();
            };
            for (Span s : spans) out.append(s.text());
            out.append('\n');
        }
        return out.toString();
    }

    /** Every link the page's text makes, as written. */
    public List<String> links() {
        List<String> out = new ArrayList<>();
        for (Block b : blocks) {
            List<Span> spans = switch (b) {
                case Heading h -> h.text();
                case Paragraph p -> p.text();
                case Bullet l -> l.text();
                default -> List.of();
            };
            for (Span s : spans) if (s.link() != null) out.add(s.link());
        }
        return out;
    }
}
