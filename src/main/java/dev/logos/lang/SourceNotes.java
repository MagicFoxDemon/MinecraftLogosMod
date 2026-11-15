package dev.logos.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Notes written into a spell's source the way a program carries comments: a block of lines starting with {@code ;;}
 * just before a form annotates that form. The block's first line is its title and the rest its text. The node graph
 * frames an annotated statement in a region with the text on a sticky note inside, and puts a note written before the
 * whole spell on a sticky note above its root. A line with a single {@code ;} stays a plain comment, never drawn.
 * <p>Notes are found by the line and column of the form they precede, counted as {@link Reader} counts them, so the
 * compiled {@link Node}s, which keep those, find their notes with nothing else changed.
 */
public final class SourceNotes {
    /** One note: its title, its text (empty when the block is a single line), and where the form it annotates starts. */
    public record Note(String title, String text, int line, int col) {}

    public static final SourceNotes NONE = new SourceNotes(Map.of(), null);

    private final Map<Long, Note> byPosition;
    /** The note written before the whole spell, when the source is a {@code (spell …)} form. */
    private final @Nullable Note head;
    private final Set<Note> used = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private SourceNotes(Map<Long, Note> byPosition, @Nullable Note head) {
        this.byPosition = byPosition;
        this.head = head;
    }

    private static long key(int line, int col) {
        return ((long) line << 32) | (col & 0xFFFFFFFFL);
    }

    /** The notes in {@code src}. */
    public static SourceNotes read(String src) {
        Map<Long, Note> out = new LinkedHashMap<>();
        List<String> block = new ArrayList<>();
        Note head = null;
        int line = 1, col = 1, depth = 0;
        boolean inString = false, first = true;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == ';') {
                int end = src.indexOf('\n', i);
                if (end < 0) end = src.length();
                if (i + 1 < end && src.charAt(i + 1) == ';') {
                    String text = src.substring(i + 2, end).stripTrailing();
                    block.add(text.startsWith(" ") ? text.substring(1) : text);
                }
                // The comment runs to the end of its line; the newline itself is counted below.
                col += end - i;
                i = end - 1;
                continue;
            } else if (c == '(' || c == '[') {
                if (!block.isEmpty()) {
                    Note n = note(block, line, col);
                    block.clear();
                    // Before the spell itself the note is the whole spell's; before anything else, the form's.
                    if (first && depth == 0 && startsSpell(src, i)) head = n;
                    else out.put(key(line, col), n);
                }
                first = false;
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            }
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return out.isEmpty() && head == null ? NONE : new SourceNotes(out, head);
    }

    /** Whether the form opening at {@code open} is {@code (spell …)}. */
    private static boolean startsSpell(String src, int open) {
        int i = open + 1;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        return src.startsWith("spell", i) && (i + 5 >= src.length() || !Character.isLetterOrDigit(src.charAt(i + 5)) && src.charAt(i + 5) != '-');
    }

    /** The note written before the whole spell, shown above its root; it counts as used once asked for. */
    public @Nullable Note head() {
        if (head != null) used.add(head);
        return head;
    }

    private static Note note(List<String> block, int line, int col) {
        // Leading empty lines say nothing; the first line with words is the title.
        int first = 0;
        while (first < block.size() && block.get(first).isBlank()) first++;
        String title = first < block.size() ? block.get(first).strip() : "";
        String text = String.join("\n", block.subList(Math.min(block.size(), first + 1), block.size())).strip();
        return new Note(title, text, line, col);
    }

    /** The note on the form starting at {@code line}, {@code col}, which then counts as used; null when there is none. */
    public @Nullable Note at(int line, int col) {
        Note n = byPosition.get(key(line, col));
        if (n != null) used.add(n);
        return n;
    }

    /** The note on {@code node}'s form, if any. */
    public @Nullable Note at(Node node) {
        return at(node.line, node.col);
    }

    public boolean isEmpty() {
        return byPosition.isEmpty() && head == null;
    }

    /** Every note, the whole spell's first. */
    public List<Note> all() {
        List<Note> out = new ArrayList<>();
        if (head != null) out.add(head);
        out.addAll(byPosition.values());
        return out;
    }

    /** The notes nothing has drawn: written before a form that takes none, such as a value inside a statement. */
    public List<Note> unused() {
        List<Note> out = new ArrayList<>();
        for (Note n : all()) if (!used.contains(n)) out.add(n);
        return out;
    }
}
