package dev.logos.graph;

import java.util.Arrays;
import java.util.List;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;

import dev.logos.lang.TargetKind;

/** ◈ The kind dropdown on a {@code kind} node: a choice from a fixed list, like ◐ dimensions. */
public final class TargetKinds {
    public static final String DEFAULT = TargetKind.ENEMIES.word;
    private static final List<String> WORDS = Arrays.stream(TargetKind.values()).map(k -> k.word).toList();

    public static final ITypeConfigurable SELECTOR = (value, type) -> IConfigurable.create(group -> {
        Object current = value.getValue();
        String initial = current instanceof String s && TargetKind.parse(s) != null ? s : DEFAULT;
        group.addConfigurator(new SelectorConfigurator<>("", () -> value.getValue() instanceof String s ? s : initial,
                value::setValue, initial, value.forceUpdate(), WORDS, w -> w + "  " + TargetKind.parse(w).zh));
    });

    private TargetKinds() {}
}
