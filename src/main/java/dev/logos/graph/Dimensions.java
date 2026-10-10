package dev.logos.graph;

import java.util.List;
import java.util.function.Supplier;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;

/**
 * ◐ Dimension choices for the graph editor. The list is read from the game's registered
 * dimensions (the client installs a supplier that asks its connection), so modded dimensions show
 * up automatically.
 */
public final class Dimensions {
    public static final String DEFAULT = "minecraft:the_nether";

    /** Replaced on the client with the dimensions the server actually has. */
    public static Supplier<List<String>> known = () -> List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    /** A dropdown of {@link #known} dimensions for a String option. */
    public static final ITypeConfigurable SELECTOR = (value, type) -> IConfigurable.create(group -> {
        List<String> ids = known.get();
        Object current = value.getValue();
        String initial = current instanceof String s && !s.isBlank() ? s : DEFAULT;
        group.addConfigurator(new SelectorConfigurator<>("", () -> value.getValue() instanceof String s ? s : initial,
                value::setValue, initial, value.forceUpdate(), ids, id -> id));
    });

    private Dimensions() {}
}
