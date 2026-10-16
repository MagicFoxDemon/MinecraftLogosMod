package dev.logos.graph;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Display-only terminology; identical source words may mean different things on different runes. */
public final class NodeTerms {
    private NodeTerms() {}

    public static MutableComponent parameter(String rune, String name) {
        String common = Component.translatableWithFallback("logos.param." + name, name).getString();
        return Component.translatableWithFallback("logos.param." + rune + "." + name, common);
    }

    public static MutableComponent event(String rune, String name) {
        String common = Component.translatableWithFallback("logos.event." + name, name).getString();
        return Component.translatableWithFallback("logos.event." + rune + "." + name, common);
    }
}
