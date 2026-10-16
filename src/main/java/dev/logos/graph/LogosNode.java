package dev.logos.graph;

import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeOption;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;

import dev.logos.lang.Category;
import net.minecraft.network.chat.Component;

/** Base of every node in a spell graph: shared port/option access for the graph compiler and builder. */
public abstract class LogosNode extends Node {
    public abstract Category category();

    /** The language word, e.g. "damage"; also the fallback name when the lang file has no entry. */
    public abstract String word();

    /** Translation key of the node's display name (assets/logos/lang). */
    public abstract String nameKey();

    /** Localized name plus the language word, e.g. "伤害  damage"; just the word when they coincide (en_us). */
    public String title() {
        String name = Component.translatableWithFallback(nameKey(), word()).getString();
        return name.equals(word()) ? name : name + "  " + word();
    }

    public String doc() {
        return "";
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(category().glyph + " " + title());
    }

    @Override
    public IGuiTexture getNodeIcon() {
        // The glyph is already in the title; a second copy as the icon only adds noise.
        return IGuiTexture.EMPTY;
    }

    @Override
    public @Nullable UIElement createDescriptionUI() {
        Label label = new Label();
        label.setText(Component.literal(category().label() + "\n" + Component.translatableWithFallback(nameKey(), word()).getString() + "\n\n" + doc()));
        label.textStyle(s -> s.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        label.layout(l -> l.widthPercent(100));
        return label;
    }

    public NodeModel model() {
        return (NodeModel) getNodeModel();
    }

    public @Nullable PortModel in(String id) {
        return model().getInputsById().get(id);
    }

    public @Nullable PortModel out(String id) {
        return model().getOutputsById().get(id);
    }

    public <T> @Nullable T option(String id, Class<T> type) {
        var o = getNodeOptionById(id);
        if (o == null) return null;
        return o.tryGetValue(type).result().map(type::cast).orElse(null);
    }

    /** Sets an option's value and rebuilds the node's ports (options may change the port layout). */
    public void setOption(String id, Object value) {
        if (getNodeOptionById(id) instanceof NodeOption o && o.getPortModel().getEmbeddedValue() != null) {
            o.getPortModel().getEmbeddedValue().setValue(value);
            model().defineNode();
        }
    }

    public static @Nullable LogosNode of(PortModel port) {
        return port.getNodeModel() instanceof ICustomNodeModel c && c.getNode() instanceof LogosNode n ? n : null;
    }

    /** Nodes wired to a port, top-to-bottom then left-to-right, so parallel branches run in reading order. */
    public static List<PortModel> connected(@Nullable PortModel port) {
        if (port == null) return List.of();
        return port.getConnectedPorts().stream()
                .sorted(Comparator.<PortModel>comparingDouble(p -> p.getNodeModel().getPosition().y)
                        .thenComparingDouble(p -> p.getNodeModel().getPosition().x))
                .toList();
    }
}
