package dev.logos.graph;

import java.util.List;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;

import net.minecraft.network.chat.Component;

/** Small string-backed choices keep math modes readable without adding spell value types. */
final class MathChoices {
    static final ITypeConfigurable RANDOM = choices("kind", List.of("real", "integer"));
    static final ITypeConfigurable ROUND = choices("mode", List.of("nearest", "up", "down"));

    private MathChoices() {}

    private static ITypeConfigurable choices(String family, List<String> words) {
        return (value, type) -> IConfigurable.create(group -> {
            String initial = value.getValue() instanceof String s && words.contains(s) ? s : words.getFirst();
            group.addConfigurator(new SelectorConfigurator<>("", () -> value.getValue() instanceof String s ? s : initial,
                    value::setValue, initial, value.forceUpdate(), words,
                    word -> Component.translatable("logos.math." + family + "." + word).getString()));
        });
    }
}
