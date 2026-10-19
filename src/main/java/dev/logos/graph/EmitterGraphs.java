package dev.logos.graph;

import java.util.List;

import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;

import dev.logos.graph.SpecialNodes.EmitterMode;
import dev.logos.graph.SpecialNodes.EmitterRoot;
import dev.logos.graph.SpecialNodes.IntentChoice;
import dev.logos.graph.SpecialNodes.SpellRoot;
import dev.logos.graph.SpecialNodes.Trigger;

/**
 * A page edited inside a spell emitter shows the emitter's node where its invocation node stands,
 * and gets its own invocation back when saved, so the page still works on a wand. What the emitter
 * node does not show (intent, immolation) is kept aside in {@link Kept} and put back.
 */
public final class EmitterGraphs {
    private EmitterGraphs() {}

    /** The page's invocation settings the emitter node has no place for. */
    public record Kept(boolean immolate, IntentChoice intent) {
        public static final Kept DEFAULT = new Kept(true, IntentChoice.AUTO);
    }

    /** The graph's root of exactly {@code type} (not a subclass), or null. */
    @SuppressWarnings("unchecked")
    public static <T extends SpellRoot> @Nullable T root(SpellGraph graph, Class<T> type) {
        for (var n : graph.getNodes()) if (n.getClass() == type) return (T) n;
        return null;
    }

    /** Puts the emitter's node in place of the page's invocation, carrying its name, mana and players over; returns what it could not carry. */
    public static Kept toEmitter(SpellGraph graph, EmitterMode mode, int interval) {
        graph.emitter(true);
        SpellRoot old = root(graph, SpellRoot.class);
        EmitterRoot root = new EmitterRoot();
        graph.graphModel.createNodeModel(root, old == null ? new Vector2f() : new Vector2f(old.model().getPosition()));
        root.setOption(EmitterRoot.MODE, mode);
        root.setOption(EmitterRoot.INTERVAL, interval);
        if (old == null) return Kept.DEFAULT;
        copy(old, root);
        Boolean immolate = old.option(SpellRoot.IMMOLATE, Boolean.class);
        IntentChoice intent = old.option(SpellRoot.INTENT, IntentChoice.class);
        rewire(graph, old, root);
        return new Kept(!Boolean.FALSE.equals(immolate), intent == null ? IntentChoice.AUTO : intent);
    }

    /** Gives the page its invocation back in place of the emitter's node: cast by hand, with what was kept aside. */
    public static void toPage(SpellGraph graph, Kept kept) {
        graph.emitter(false);
        EmitterRoot old = root(graph, EmitterRoot.class);
        if (old == null) return;
        SpellRoot root = new SpellRoot();
        graph.graphModel.createNodeModel(root, new Vector2f(old.model().getPosition()));
        copy(old, root);
        root.setOption(SpellRoot.TRIGGER, Trigger.CAST);
        root.setOption(SpellRoot.IMMOLATE, kept.immolate());
        root.setOption(SpellRoot.INTENT, kept.intent());
        rewire(graph, old, root);
    }

    private static void copy(SpellRoot from, SpellRoot to) {
        String name = from.option(SpellRoot.NAME, String.class);
        Integer mana = from.option(SpellRoot.MANA, Integer.class);
        Boolean players = from.option(SpellRoot.PLAYERS, Boolean.class);
        if (name != null) to.setOption(SpellRoot.NAME, name);
        if (mana != null) to.setOption(SpellRoot.MANA, mana);
        if (players != null) to.setOption(SpellRoot.PLAYERS, players);
    }

    /** Moves the flow from {@code from}'s start to {@code to}'s, and takes {@code from} away. */
    private static void rewire(SpellGraph graph, SpellRoot from, SpellRoot to) {
        for (PortModel next : List.copyOf(LogosNode.connected(from.out(SpellRoot.START)))) graph.graphModel.createWire(next, to.out(SpellRoot.START));
        graph.graphModel.deleteElements(List.of(from.model()));
    }
}
