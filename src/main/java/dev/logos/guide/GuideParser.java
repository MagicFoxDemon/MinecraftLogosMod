package dev.logos.guide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import dev.logos.guide.GuideDoc.Block;
import dev.logos.guide.GuideDoc.Span;

/**
 * Reads the Markdown 逻各斯手记 is written in: a header between {@code ---} lines ({@code key: value}), then headings
 * ({@code #} to {@code ###}), paragraphs, lists ({@code -} or {@code 1.}), rules ({@code ---}) and tag lines
 * ({@code {{items logos:wand}}}). Inside text: {@code **bold**}, {@code *italic*}, {@code `code`} and
 * {@code [text](target)}; a backslash keeps the next character as it is. Anything it does not understand is kept as
 * text, so a slip in a page shows on the page instead of losing it.
 */
public final class GuideParser {
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^(-{3,}|\\*{3,})$");

    private GuideParser() {}

    public static GuideDoc parse(String source) {
        String s = source.replace("\r\n", "\n").replace('\r', '\n');
        if (s.startsWith("﻿")) s = s.substring(1);
        String[] lines = s.split("\n", -1);
        Map<String, String> meta = new LinkedHashMap<>();
        int i = 0;
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            for (int end = 1; end < lines.length; end++) {
                if (!lines[end].strip().equals("---")) continue;
                for (int j = 1; j < end; j++) {
                    int colon = lines[j].indexOf(':');
                    if (colon > 0) meta.put(lines[j].substring(0, colon).strip(), unquote(lines[j].substring(colon + 1).strip()));
                }
                i = end + 1;
                break;
            }
        }
        List<Block> blocks = new ArrayList<>();
        Open open = null;
        for (; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                open = close(open, blocks);
                continue;
            }
            Matcher m;
            if (line.startsWith("{{") && line.endsWith("}}") && line.length() > 4) {
                open = close(open, blocks);
                List<String> words = words(line.substring(2, line.length() - 2));
                if (!words.isEmpty()) blocks.add(new GuideDoc.Tag(words.getFirst(), List.copyOf(words.subList(1, words.size()))));
            } else if (RULE.matcher(line).matches()) {
                open = close(open, blocks);
                blocks.add(new GuideDoc.Rule());
            } else if ((m = HEADING.matcher(line)).matches()) {
                open = close(open, blocks);
                blocks.add(new GuideDoc.Heading(m.group(1).length(), spans(m.group(2).strip())));
            } else if ((m = BULLET.matcher(line)).matches()) {
                close(open, blocks);
                open = new Open(0, new StringBuilder(m.group(1).strip()));
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                close(open, blocks);
                open = new Open(Integer.parseInt(m.group(1)), new StringBuilder(m.group(2).strip()));
            } else if (open == null) {
                open = new Open(-1, new StringBuilder(line));
            } else {
                join(open.text, line);
            }
        }
        close(open, blocks);
        return new GuideDoc(Map.copyOf(meta), List.copyOf(blocks));
    }

    /** A paragraph ({@code number} -1) or list item still taking lines. */
    private record Open(int number, StringBuilder text) {}

    private static @Nullable Open close(@Nullable Open open, List<Block> into) {
        if (open == null) return null;
        List<Span> text = spans(open.text.toString());
        into.add(open.number < 0 ? new GuideDoc.Paragraph(text) : new GuideDoc.Bullet(open.number, text));
        return null;
    }

    /** Lines of one paragraph run on: with a space between words, with none where either side is Chinese. */
    private static void join(StringBuilder text, String line) {
        boolean tight = text.isEmpty() || wide(text.charAt(text.length() - 1)) || wide(line.charAt(0));
        if (!tight) text.append(' ');
        text.append(line);
    }

    private static boolean wide(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    private static String unquote(String v) {
        return v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")) ? v.substring(1, v.length() - 1) : v;
    }

    /** A tag's words: split at spaces, except inside double quotes. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(word.toString());
                word.setLength(0);
                any = false;
            } else {
                word.append(c);
                any = true;
            }
        }
        if (any) out.add(word.toString());
        return out;
    }

    /** The runs of text in a line, by style. */
    public static List<Span> spans(String text) {
        List<Span> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean bold = false, italic = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                run.append(text.charAt(i + 1));
                i += 2;
            } else if (c == '`' && text.indexOf('`', i + 1) > i) {
                int end = text.indexOf('`', i + 1);
                emit(out, run, bold, italic, false, null);
                out.add(new Span(text.substring(i + 1, end), bold, italic, true, null));
                i = end + 1;
            } else if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*' && (bold || text.indexOf("**", i + 2) > 0)) {
                emit(out, run, bold, italic, false, null);
                bold = !bold;
                i += 2;
            } else if (c == '*' && (italic || i + 1 < text.length() && !Character.isWhitespace(text.charAt(i + 1)) && closes(text, i + 1))) {
                emit(out, run, bold, italic, false, null);
                italic = !italic;
                i++;
            } else if (c == '[' && link(text, i) != null) {
                int[] at = link(text, i);
                emit(out, run, bold, italic, false, null);
                out.add(new Span(unescape(text.substring(i + 1, at[0])), bold, italic, false, text.substring(at[0] + 2, at[1]).strip()));
                i = at[1] + 1;
            } else {
                run.append(c);
                i++;
            }
        }
        emit(out, run, bold, italic, false, null);
        return List.copyOf(out);
    }

    /** Whether a single {@code *} opened at {@code from} is closed later by another single one. */
    private static boolean closes(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '*') {
                // A pair is bold, not the end of this.
                if (i + 1 < text.length() && text.charAt(i + 1) == '*') i++;
                else return i > from;
            }
        }
        return false;
    }

    /** For {@code [text](target)} at {@code open}: where the {@code ]} and the closing {@code )} are, or null. */
    private static int @Nullable [] link(String text, int open) {
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ']') {
                if (i + 1 >= text.length() || text.charAt(i + 1) != '(') return null;
                int close = text.indexOf(')', i + 2);
                return close < 0 ? null : new int[] {i, close};
            }
        }
        return null;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) c = text.charAt(++i);
            out.append(c);
        }
        return out.toString();
    }

    private static void emit(List<Span> out, StringBuilder run, boolean bold, boolean italic, boolean code, @Nullable String link) {
        if (run.isEmpty()) return;
        out.add(new Span(run.toString(), bold, italic, code, link));
        run.setLength(0);
    }
}
