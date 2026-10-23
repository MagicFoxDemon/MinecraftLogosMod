package dev.logos.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wire.WireModel;

import dev.logos.lang.Type;

/** Static meanings at each use of a context wire; value nodes are evaluated in their consumer's scope. */
public final class ContextSources {
    private ContextSources() {}

    /** Semantic descriptions, localized only when presented by the editor. */
    public enum Meaning {
        CASTER, EMITTER_CASTER, CAST_POSITION, EMITTER_POSITION, ATTACK_POSITION, KILL_POSITION,
        CAST_TARGET, HURT_TARGET, ATTACK_TARGET, KILL_TARGET, CAST_AIM, EMITTER_AIM,
        UP, DOWN, NO_SELF, ZERO_INDEX, REPEAT_INDEX, SPLIT_AIM,
        EACH_POSITION, EACH_TARGET, BLOCK_POSITION,
        PROJECTILE_POSITION, PROJECTILE_CONTACT, PROJECTILE_AIM, HIT_TARGET, IMPACT_BLOCK,
        FIELD_POSITION, ENTER_TARGET, ENTITY_CENTER, BEAM_END, BEAM_AIM, RAY_END, RAY_AIM,
        CONSTRUCT_POSITION, CONSTRUCT_CONTACT, CONSTRUCT_SELF, CONSTRUCT_NORMAL,
        CONSTRUCT_TARGET, PASS_POSITION, PASS_AIM, KILLED_TARGET, FORM_SELF, NO_FORM, UNKNOWN
    }

    public record Binding(Meaning meaning, LogosNode source) {}
    public record Use(LogosNode consumer, Binding binding, List<PortModel> route) {}
    public record Analysis(String context, List<Use> uses, boolean incomplete, boolean recursive) {}

    /** Returns null for ordinary wires; detached/unused context wires retain an explicit unresolved result. */
    public static @Nullable Analysis analyze(SpellGraph graph, WireModel wire) {
        if (wire.getFromPort() == null || !(LogosNode.of(wire.getFromPort()) instanceof SpecialNodes.ContextValue context)) return null;
        Walker walker = new Walker(context.contextName());
        walker.consumers(wire.getToPort(), new HashSet<>());
        for (var node : graph.getNodes()) {
            if (node instanceof SpecialNodes.SpellRoot root) {
                walker.flow(root.out(SpecialNodes.SpellRoot.START), initial(root), List.of(root.out(SpecialNodes.SpellRoot.START)), new HashSet<>());
            }
        }
        return new Analysis(context.contextName(), List.copyOf(walker.uses), walker.incomplete, walker.recursive);
    }

    private static Map<String, Binding> initial(SpecialNodes.SpellRoot root) {
        Map<String, Binding> scope = new HashMap<>();
        put(scope, root, "caster", root instanceof SpecialNodes.EmitterRoot ? Meaning.EMITTER_CASTER : Meaning.CASTER);
        put(scope, root, "aim", root instanceof SpecialNodes.EmitterRoot ? Meaning.EMITTER_AIM : Meaning.CAST_AIM);
        put(scope, root, "here", root instanceof SpecialNodes.EmitterRoot ? Meaning.EMITTER_POSITION : Meaning.CAST_POSITION);
        put(scope, root, "target", Meaning.CAST_TARGET);
        put(scope, root, "self", Meaning.NO_SELF);
        put(scope, root, "form", Meaning.NO_FORM);
        put(scope, root, "i", Meaning.ZERO_INDEX);
        put(scope, root, "up", Meaning.UP);
        put(scope, root, "down", Meaning.DOWN);
        if (!(root instanceof SpecialNodes.EmitterRoot)) {
            var trigger = root.option(SpecialNodes.SpellRoot.TRIGGER, SpecialNodes.Trigger.class);
            if (trigger == SpecialNodes.Trigger.HURT) put(scope, root, "target", Meaning.HURT_TARGET);
            if (trigger == SpecialNodes.Trigger.ATTACK) {
                put(scope, root, "target", Meaning.ATTACK_TARGET);
                put(scope, root, "here", Meaning.ATTACK_POSITION);
            }
            if (trigger == SpecialNodes.Trigger.KILL) {
                put(scope, root, "target", Meaning.KILL_TARGET);
                put(scope, root, "here", Meaning.KILL_POSITION);
            }
        }
        return scope;
    }

    private static void put(Map<String, Binding> scope, LogosNode source, String name, Meaning meaning) {
        scope.put(name, new Binding(meaning, source));
    }

    /** Only handlers/bodies replace context; a form's ordinary next output keeps its incoming scope. */
    private static Map<String, Binding> inside(RuneNode node, String output, Map<String, Binding> outer) {
        Map<String, Binding> scope = new HashMap<>(outer);
        String word = node.word();
        if (output.equals(RuneNode.BODY)) {
            switch (word) {
                case "each" -> {
                    put(scope, node, "here", Meaning.EACH_POSITION);
                    put(scope, node, "target", Meaning.EACH_TARGET);
                }
                case "each-block" -> put(scope, node, "here", Meaning.BLOCK_POSITION);
                case "repeat" -> put(scope, node, "i", Meaning.REPEAT_INDEX);
                case "split" -> put(scope, node, "aim", Meaning.SPLIT_AIM);
                default -> { /* do and delay retain the captured context */ }
            }
        }
        if (!output.startsWith("ev_")) return scope;
        String event = output.substring(3);
        // Every body-making form names its body in its own events.
        if (node.rune() != null && node.rune().yields == Type.FORM) put(scope, node, "form", Meaning.FORM_SELF);
        boolean hit = event.equals("on-hit");
        boolean enter = event.equals("on-enter");
        boolean impact = event.equals("on-impact");
        switch (word) {
            case "projectile" -> {
                put(scope, node, "here", hit ? Meaning.PROJECTILE_CONTACT : impact ? Meaning.IMPACT_BLOCK : Meaning.PROJECTILE_POSITION);
                put(scope, node, "aim", Meaning.PROJECTILE_AIM);
            }
            case "field" -> put(scope, node, "here", enter ? Meaning.ENTITY_CENTER : Meaning.FIELD_POSITION);
            case "beam", "ray" -> {
                put(scope, node, "here", hit || enter ? Meaning.ENTITY_CENTER : impact ? Meaning.IMPACT_BLOCK
                        : word.equals("beam") ? Meaning.BEAM_END : Meaning.RAY_END);
                put(scope, node, "aim", word.equals("beam") ? Meaning.BEAM_AIM : Meaning.RAY_AIM);
            }
            // A switch's fallback runs where the switch stands.
            case "switch" -> { }
            case "construct" -> {
                put(scope, node, "self", Meaning.CONSTRUCT_SELF);
                put(scope, node, "here", switch (event) {
                    case "on-block", "on-stand" -> Meaning.CONSTRUCT_CONTACT;
                    case "on-pass" -> Meaning.PASS_POSITION;
                    default -> Meaning.CONSTRUCT_POSITION;
                });
                // SpellObject.end preserves the construct's creation aim, unlike its tick/arrival handlers.
                if (!event.equals("on-expire")) put(scope, node, "aim", event.equals("on-pass") ? Meaning.PASS_AIM : Meaning.CONSTRUCT_NORMAL);
            }
            default -> {
                if (event.equals("on-kill")) {
                    put(scope, node, "here", Meaning.ENTITY_CENTER);
                    put(scope, node, "target", Meaning.KILLED_TARGET);
                } else {
                    // New events must not silently inherit a falsely precise explanation.
                    put(scope, node, "here", Meaning.UNKNOWN);
                    put(scope, node, "aim", Meaning.UNKNOWN);
                    put(scope, node, "target", Meaning.UNKNOWN);
                }
            }
        }
        if (hit) put(scope, node, "target", Meaning.HIT_TARGET);
        if (enter) put(scope, node, "target", Meaning.ENTER_TARGET);
        if (word.equals("construct") && Set.of("on-block", "on-stand", "on-pass").contains(event)) {
            put(scope, node, "target", Meaning.CONSTRUCT_TARGET);
        }
        return scope;
    }

    private static final class Walker {
        private final String context;
        private final Set<LogosNode> consumers = new HashSet<>();
        private final Set<Use> uses = new LinkedHashSet<>();
        private int visits;
        private boolean incomplete, recursive;

        private Walker(String context) { this.context = context; }

        /** Stop at actions and let: a remembered result is not evaluated again at its downstream uses. */
        private void consumers(@Nullable PortModel input, Set<LogosNode> seen) {
            var pending = new java.util.ArrayDeque<PortModel>();
            if (input != null) pending.add(input);
            while (!pending.isEmpty()) {
                LogosNode node = LogosNode.of(pending.removeFirst());
                if (node == null || !seen.add(node)) continue;
                if (seen.size() > 4096) { incomplete = true; return; }
                if (node instanceof SpecialNodes.Remember || node instanceof RuneNode r && r.rune().returns == Type.ACTION) {
                    consumers.add(node);
                    continue;
                }
                for (PortModel output : node.model().getOutputsById().values()) {
                    if (!output.getDataTypeHandle().equals(LTypes.FLOW)) pending.addAll(LogosNode.connected(output));
                }
            }
        }

        private void flow(@Nullable PortModel output, Map<String, Binding> scope, List<PortModel> route, Set<LogosNode> active) {
            for (PortModel input : LogosNode.connected(output)) {
                LogosNode node = LogosNode.of(input);
                if (node == null) continue;
                // Invalid graphs and extreme fan-out must never hang the editor.
                if (++visits > 4096 || active.size() >= 128 || uses.size() >= 64) { incomplete = true; return; }
                if (!active.add(node)) { incomplete = true; continue; }
                if (consumers.contains(node)) uses.add(new Use(node, scope.get(context), List.copyOf(route)));
                if (node.word().equals("recurse")) recursive = true;
                for (PortModel next : node.model().getOutputsById().values()) {
                    if (!next.getDataTypeHandle().equals(LTypes.FLOW)) continue;
                    boolean nested = !next.getPortId().equals(RuneNode.NEXT);
                    List<PortModel> path = route;
                    if (nested) {
                        path = new ArrayList<>(route);
                        path.add(next);
                    }
                    flow(next, node instanceof RuneNode r ? inside(r, next.getPortId(), scope) : scope, path, active);
                }
                active.remove(node);
            }
        }
    }
}
