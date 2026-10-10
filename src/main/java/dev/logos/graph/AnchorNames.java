package dev.logos.graph;

import java.util.List;
import java.util.function.Supplier;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;

/**
 * ⚓ Anchor choices for the graph editor: the names of the player's own anchors (the client
 * installs a supplier reading what the server synced). A name typed into an {@code anchor} node
 * may be new; an anchor read as a position must already exist, so it is picked from this list.
 */
public final class AnchorNames {
    public static Supplier<List<String>> known = List::of;

    public static final ITypeConfigurable SELECTOR = (value, type) -> IConfigurable.create(group -> {
        List<String> names = known.get();
        Object current = value.getValue();
        String initial = current instanceof String s && !s.isBlank() ? s : names.isEmpty() ? "" : names.getFirst();
        List<String> choices = names.contains(initial) || initial.isEmpty() ? names : concat(initial, names);
        group.addConfigurator(new SelectorConfigurator<>("", () -> value.getValue() instanceof String s ? s : initial,
                value::setValue, initial, value.forceUpdate(), choices, n -> "⚓ " + n));
    });

    private static List<String> concat(String first, List<String> rest) {
        var out = new java.util.ArrayList<String>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private AnchorNames() {}
}
