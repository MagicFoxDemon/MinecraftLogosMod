package dev.logos.guide;

/**
 * Where 符文图鉴, the chapter of 逻各斯手记 with a page for every rune, element and motion, keeps its pages. The pages
 * are made in code ({@code client/GuideRunes}); a file of the same name in the chapter adds to its page.
 */
public final class RuneCodex {
    public static final String CHAPTER = "logos:runes";
    /** The chapter's first page: every family, with a square for each word, black for one not yet learned. */
    public static final String OVERVIEW = CHAPTER + "/overview";

    private RuneCodex() {}

    /** The page of the word an {@code Unlocks} key names: {@code rune:damage} → {@code runes/damage}, {@code element:fire} → {@code runes/element_fire}. */
    public static String page(String key) {
        int colon = key.indexOf(':');
        String kind = key.substring(0, colon), name = key.substring(colon + 1);
        return CHAPTER + "/" + (kind.equals("rune") ? name : kind + "_" + name);
    }

    /** The page of a node no one has to learn: {@code spell}, {@code emitter}; a context gets {@code ctx_} before its word. */
    public static String unlockedPage(String word, boolean context) {
        return CHAPTER + "/" + (context ? "ctx_" : "") + word;
    }
}
