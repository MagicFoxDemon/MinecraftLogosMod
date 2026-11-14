package dev.logos.guide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * 逻各斯手记 as loaded: its chapters in order, each with its entries in order, and every entry by id. An entry's id is
 * {@code <namespace>:<chapter>/<name>}, after the file {@code assets/<namespace>/guide/<language>/<chapter>/<name>.md};
 * a chapter's own file {@code _chapter.md} gives its title, icon, bookmark label and place.
 */
public final class GuideBook {
    /** The name of a chapter's own file, without {@code .md}. */
    public static final String CHAPTER_FILE = "_chapter";
    /** The tag lines a page may use; any other is drawn as a warning on the page. */
    public static final Set<String> TAGS = Set.of("items", "recipe", "image");
    /** Where an entry or chapter without an {@code order} goes: after those that have one. */
    private static final int UNORDERED = 1000;

    public record Chapter(String id, String title, String icon, String ribbon, int order, List<Entry> entries) {}

    public record Entry(String id, String chapter, String title, String icon, int order, GuideDoc doc) {}

    public static final GuideBook EMPTY = new GuideBook(List.of());

    private final List<Chapter> chapters;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private GuideBook(List<Chapter> chapters) {
        this.chapters = chapters;
        for (Chapter c : chapters) for (Entry e : c.entries()) entries.put(e.id(), e);
    }

    /** The book from its files: {@code files} maps {@code <namespace>:<chapter>/<name>} (no {@code .md}) to the text. */
    public static GuideBook build(Map<String, String> files) {
        Map<String, GuideDoc> heads = new HashMap<>();
        Map<String, List<Entry>> byChapter = new LinkedHashMap<>();
        for (var file : files.entrySet()) {
            String id = file.getKey();
            int colon = id.indexOf(':'), slash = id.indexOf('/', colon + 1);
            // A file at the guide's root belongs to no chapter.
            if (colon <= 0 || slash < 0 || slash == id.length() - 1) continue;
            String chapter = id.substring(0, slash);
            GuideDoc doc = GuideParser.parse(file.getValue());
            List<Entry> list = byChapter.computeIfAbsent(chapter, k -> new ArrayList<>());
            if (id.substring(slash + 1).equals(CHAPTER_FILE)) {
                heads.put(chapter, doc);
                continue;
            }
            list.add(new Entry(id, chapter, doc.meta("title", id.substring(id.lastIndexOf('/') + 1)), doc.meta("icon", ""), order(doc), doc));
        }
        List<Chapter> chapters = new ArrayList<>();
        for (var c : byChapter.entrySet()) {
            if (c.getValue().isEmpty()) continue;
            GuideDoc head = heads.getOrDefault(c.getKey(), new GuideDoc(Map.of(), List.of()));
            String title = head.meta("title", c.getKey().substring(c.getKey().indexOf(':') + 1));
            List<Entry> sorted = new ArrayList<>(c.getValue());
            sorted.sort(Comparator.comparingInt(Entry::order).thenComparing(Entry::id));
            chapters.add(new Chapter(c.getKey(), title, head.meta("icon", ""), head.meta("ribbon", title), order(head), List.copyOf(sorted)));
        }
        chapters.sort(Comparator.comparingInt(Chapter::order).thenComparing(Chapter::id));
        return new GuideBook(List.copyOf(chapters));
    }

    private static int order(GuideDoc doc) {
        try {
            return Integer.parseInt(doc.meta("order", String.valueOf(UNORDERED)).strip());
        } catch (NumberFormatException e) {
            return UNORDERED;
        }
    }

    public List<Chapter> chapters() {
        return chapters;
    }

    public @Nullable Entry entry(String id) {
        return entries.get(id);
    }

    public @Nullable Chapter chapter(String id) {
        for (Chapter c : chapters) if (c.id().equals(id)) return c;
        return null;
    }

    /** Where the book opens the first time: the first entry of its first chapter. */
    public @Nullable Entry first() {
        return chapters.isEmpty() ? null : chapters.getFirst().entries().getFirst();
    }

    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    /** Whether {@code link} names an item rather than an entry. */
    public static boolean isItem(String link) {
        return link.startsWith("item:");
    }

    /**
     * The entry {@code link}, written on the entry {@code from}, names: a full id as it is; {@code chapter/name} in
     * the same namespace; a bare {@code name} in the same chapter.
     */
    public static String resolve(String from, String link) {
        String l = link.strip();
        if (l.endsWith(".md")) l = l.substring(0, l.length() - 3);
        if (l.startsWith("./")) l = l.substring(2);
        if (l.contains(":")) return l;
        int colon = from.indexOf(':');
        String namespace = colon < 0 ? "logos" : from.substring(0, colon);
        if (l.contains("/")) return namespace + ":" + (l.startsWith("/") ? l.substring(1) : l);
        int slash = from.indexOf('/', colon + 1);
        return (slash < 0 ? from : from.substring(0, slash)) + "/" + l;
    }

    /** The entries whose title holds {@code query}, then those whose text does, each in book order. */
    public List<Entry> search(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        List<Entry> byTitle = new ArrayList<>(), byText = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.title().toLowerCase(Locale.ROOT).contains(q)) byTitle.add(e);
            else if (e.doc().plainText().toLowerCase(Locale.ROOT).contains(q)) byText.add(e);
        }
        byTitle.addAll(byText);
        return byTitle;
    }

    /** Every link to an entry that is not in the book, as {@code from → link}. */
    public List<String> brokenLinks() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            for (String link : e.doc().links()) {
                if (!isItem(link) && entry(resolve(e.id(), link)) == null) out.add(e.id() + " → " + link);
            }
        }
        return out;
    }
}
