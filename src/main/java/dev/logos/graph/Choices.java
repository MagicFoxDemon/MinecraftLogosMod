package dev.logos.graph;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;

import dev.logos.lang.Rune;
import net.minecraft.network.chat.Component;

/** The dropdown for a rune's choice keyword ({@code :mode up}): one of its fixed words, named per rune. */
public final class Choices {
    private Choices() {}

    /** {@code logos.choice.<rune>.<keyword>.<word>}, falling back to the word itself. */
    public static String label(String rune, String keyword, String word) {
        return Component.translatableWithFallback("logos.choice." + rune + "." + keyword + "." + word, word).getString();
    }

    public static ITypeConfigurable selector(String rune, Rune.Kw k) {
        return (value, type) -> IConfigurable.create(group -> {
            String initial = value.getValue() instanceof String s && k.choices().contains(s) ? s : k.defaultSrc();
            group.addConfigurator(new SelectorConfigurator<>("", () -> value.getValue() instanceof String s ? s : initial,
                    value::setValue, initial, value.forceUpdate(), k.choices(), w -> label(rune, k.name(), w)));
        });
    }
}
