package dev.logos.client.uitest;

import net.minecraft.client.resources.language.I18n;

/** Text from the language files as the client reads it, for checks that hold in any language. */
final class LangText {
    private static final String CUT = "\u0001";

    private LangText() {}

    /** What {@code key} reads up to where its first argument goes (all of it if it takes none). */
    static String before(String key) {
        String s = I18n.get(key, CUT, CUT, CUT);
        int i = s.indexOf(CUT);
        return i < 0 ? s : s.substring(0, i);
    }
}
