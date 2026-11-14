package dev.logos.graph;

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.lang.Unlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * What the editor's viewer has not learned (符文解锁): such a node, its ports and its help are drawn in Minecraft's own
 * obfuscated text, and the library does not hand it out. The client points {@link #known} at its player's knowledge;
 * until then, and wherever nothing is drawn, every word is known. Writing is checked again by the server.
 */
public final class Sealed {
    /** Whether the viewer may write the {@link Unlocks} key given. */
    public static Predicate<String> known = key -> true;

    private Sealed() {}

    /** The key {@code node} writes, if it is a word to learn: a rune (remember writes let), or an element or motion card. */
    public static @Nullable String key(LogosNode node) {
        if (node instanceof RuneNode || node instanceof SpecialNodes.Remember) return Unlocks.rune(node.word());
        if (node instanceof EnumValues.Value v && v.kind == EnumValues.Kind.ELEMENT) {
            Element e = Element.parse(v.value());
            return e == null ? null : Unlocks.element(e);
        }
        if (node instanceof EnumValues.Value v && v.kind == EnumValues.Kind.MOTION) {
            Motion m = Motion.parse(v.value());
            return m == null ? null : Unlocks.motion(m);
        }
        return null;
    }

    /** Whether {@code node} is a word its viewer has not learned. */
    public static boolean node(LogosNode node) {
        String key = key(node);
        return key != null && !known.test(key);
    }

    public static boolean element(Element e) {
        return !known.test(Unlocks.element(e));
    }

    public static boolean motion(Motion m) {
        return !known.test(Unlocks.motion(m));
    }

    public static MutableComponent garble(Component text) {
        return text.copy().withStyle(ChatFormatting.OBFUSCATED);
    }

    /** For the places that take plain strings (dropdown entries): the obfuscation code carried in the text. */
    public static String garble(String text) {
        return ChatFormatting.OBFUSCATED + text;
    }
}
