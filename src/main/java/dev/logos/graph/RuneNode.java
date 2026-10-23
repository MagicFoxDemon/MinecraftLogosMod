package dev.logos.graph;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.SelectorConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.port.PortModelOptions;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.port.PortOrientation;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;

import dev.logos.lang.Category;
import dev.logos.lang.Compiler;
import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Type;
import dev.logos.lang.Weather;
import net.minecraft.network.chat.Component;

/**
 * A node generated from a rune's declaration. Ports follow the rune's signature, so adding a rune
 * to the lexicon is enough to make it available in the graph editor:
 * <ul>
 * <li>actions get a ▶ flow input on top and a ▶ "next" flow output at the bottom;</li>
 * <li>values get one typed output;</li>
 * <li>positional and keyword slots become typed inputs, holding an inline constant while unwired;</li>
 * <li>events ({@code on-hit}…) and bodies become extra flow outputs.</li>
 * </ul>
 */
public abstract class RuneNode extends LogosNode {
    public static final String IN = "in";
    public static final String NEXT = "next";
    public static final String OUT = "out";
    public static final String BODY = "body";
    public static final String COUNT = "count";
    /** What an action hands back (a form's 此形, summon's 吾之使魔), for what follows it in the flow. */
    public static final String YIELD = "yield";
    /** On a rune returning ✱ (choose): which type it chooses among, picked like remember's. */
    public static final String KIND = "kind";

    private static final Map<String, Float> PARAM_HINTS = Map.of(
            "amount", 5f, "seconds", 3f, "strength", 1f, "times", 3f, "count", 3f, "distance", 4f, "stacks", 1f);

    private final String runeName;

    protected RuneNode(String runeName) {
        this.runeName = runeName;
    }

    public Rune rune() {
        return Runes.get(runeName);
    }

    @Override
    public Category category() {
        return rune().category;
    }

    @Override
    public String word() {
        return runeName;
    }

    @Override
    public String nameKey() {
        return "logos.rune." + runeName;
    }

    @Override
    public String doc() {
        return NodeDescriptions.describe(this);
    }

    /** What a ✱ slot of a rune returning ✱ carries on this node: its kind option, a number unless set. */
    public Type slot(Type t) {
        if (t != Type.ANY || rune().returns != Type.ANY) return t;
        SpecialNodes.RememberKind k = option(KIND, SpecialNodes.RememberKind.class);
        return (k == null ? SpecialNodes.RememberKind.NUM : k).type;
    }

    public static String kw(String name) {
        return "kw_" + name;
    }

    public static String flag(String name) {
        return "flag_" + name;
    }

    public static String choice(String name) {
        return "choice_" + name;
    }

    public static String event(String name) {
        return "ev_" + name;
    }

    public static String dim(String name) {
        return "dim_" + name;
    }

    /** Legacy inline choices, retained as defaults for catalogue-connected inputs. */
    public static boolean isOption(Rune.Param p) {
        return p.type() == Type.DIMENSION || p.type() == Type.KIND || p.type() == Type.ANCHOR || p.type() == Type.CREATURE || p.type() == Type.MOB_EFFECT;
    }

    /** The option id holding such a slot's choice. */
    public static String option(Rune.Param p) {
        return p.type() == Type.KIND ? "kind_" + p.name() : p.type() == Type.ANCHOR ? "anchor_" + p.name() : p.type() == Type.CREATURE ? "creature_" + p.name() : p.type() == Type.MOB_EFFECT ? "effect_" + p.name() : dim(p.name());
    }

    @Override
    public void onDefineOptions(IOptionDefinitionContext context) {
        Rune r = rune();
        if (r.returns == Type.ANY) {
            context.addOption(KIND, SpecialNodes.RememberKind.class).withDefaultValue(SpecialNodes.RememberKind.NUM)
                    .withDisplayName(NodeTerms.parameter(word(), KIND));
        }
        for (Rune.Param p : r.params) {
            if (p.type() == Type.DIMENSION) {
                // Not a port: dimensions are chosen from the registered list, not computed.
                context.addOption(dim(p.name()), String.class).withDefaultValue(Dimensions.DEFAULT)
                        .withConfigurable(Dimensions.SELECTOR).withDisplayName(Component.literal("◐ ").append(NodeTerms.parameter(word(), p.name())));
            }
            if (p.type() == Type.CREATURE) {
                context.addOption(option(p), String.class).withDefaultValue("zombie").withDisplayName(Component.literal("❦ ").append(NodeTerms.parameter(word(), p.name())));
            }
            if (p.type() == Type.MOB_EFFECT) {
                context.addOption(option(p), String.class).withDefaultValue("speed").withDisplayName(Component.literal("⚗ ").append(NodeTerms.parameter(word(), p.name())));
            }
            if (p.type() == Type.ANCHOR) {
                // Typed, not picked: anchor may name a new one.
                context.addOption(option(p), String.class).withDefaultValue("家").withDisplayName(Component.literal("⚓ ").append(NodeTerms.parameter(word(), p.name())));
            }
            if (p.type() == Type.KIND) {
                context.addOption(option(p), String.class).withDefaultValue(TargetKinds.DEFAULT)
                        .withConfigurable(TargetKinds.SELECTOR).withDisplayName(Component.literal("◈ ").append(NodeTerms.parameter(word(), p.name())));
            }
            if (p.rest()) {
                context.addOption(COUNT, Integer.class).withDefaultValue(2).withDisplayName(NodeTerms.parameter(word(), "argument-count"));
            }
        }
        for (Rune.Kw k : r.keywords.values()) {
            if (k.flag()) {
                context.addOption(flag(k.name()), Boolean.class).withDefaultValue(false).withDisplayName(NodeTerms.parameter(word(), k.name()));
            }
            if (k.choice()) {
                context.addOption(choice(k.name()), String.class).withDefaultValue(k.defaultSrc())
                        .withConfigurable(Choices.selector(word(), k)).withDisplayName(NodeTerms.parameter(word(), k.name()));
            }
        }
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        Rune r = rune();
        if (r.returns == Type.ACTION) {
            context.addInputPort(IN, LTypes.FLOW).withOrientation(PortOrientation.Vertical).withDisplayName(Component.literal("▶")).build();
        } else {
            context.addOutputPort(OUT, LTypes.of(slot(r.returns), false)).withDisplayName(Component.literal(slot(r.returns).label())).build();
        }
        for (Rune.Param p : r.params) {
            if (isOption(p)) {
                // Keep the old option as the unwired fallback, and add a typed connector above it.
                if (p.type() != Type.ANCHOR) context.addInputPort(p.name(), LTypes.of(p.type(), false))
                        .withDisplayName(NodeTerms.parameter(word(), p.name())).withoutConfigurator().withoutSerialization().build();
                continue;
            }
            if (p.type() == Type.ACTION) {
                if (p.rest()) {
                    // Numbered branches, counted from 0 like the index that picks them.
                    Integer n = option(COUNT, Integer.class);
                    for (int i = 0; i < Math.max(1, n == null ? 2 : n); i++) {
                        context.addOutputPort(p.name() + i, LTypes.FLOW).withDisplayName(Component.literal("⤷ ").append(NodeTerms.parameter(word(), p.name())).append(" " + i)).build();
                    }
                    continue;
                }
                String glyph = p.name().equals("then") ? "✔ " : p.name().equals("else") ? "✘ " : "⤷ ";
                context.addOutputPort(p.name(), LTypes.FLOW).withDisplayName(Component.literal(glyph).append(NodeTerms.parameter(word(), p.name()))).build();
            } else if (p.rest()) {
                Integer n = option(COUNT, Integer.class);
                for (int i = 0; i < Math.max(1, n == null ? 2 : n); i++) {
                    // Values to choose among (✱) are numbered from 0, like the index that picks them.
                    int shown = p.type() == Type.ANY ? i : i + 1;
                    input(context, p.name() + i, NodeTerms.parameter(word(), p.name()).append(" " + shown), slot(p.type()), false, null, p.name());
                }
            } else {
                input(context, p.name(), NodeTerms.parameter(word(), p.name()), p.type(), p.broadcast(), p.defaultSrc(), p.name());
            }
        }
        PortModel shape = null;
        for (Rune.Kw k : r.keywords.values()) {
            if (k.flag() || k.choice()) continue;
            if (k.type() == Type.SHAPE) {
                // Picking a shape redraws the node with only the sizes that shape uses.
                shape = (PortModel) context.addInputPort(kw(k.name()), LTypes.of(k.type(), false)).withDisplayName(NodeTerms.parameter(word(), k.name()))
                        .withDefaultValue(defaultValue(k.type(), k.defaultSrc(), k.name())).withConfigurable(shapeSelector()).build();
                continue;
            }
            PortModel port = input(context, kw(k.name()), NodeTerms.parameter(word(), k.name()), k.type(), false, k.defaultSrc(), k.name());
            // Kept but hidden, so a size set for another shape is not lost on the way.
            if (shape != null && unused(shape, k.name())) port.setOptions(PortModelOptions.HIDDEN);
        }
        for (Rune.EventSpec e : r.events.values()) {
            context.addOutputPort(event(e.name()), LTypes.FLOW).withDisplayName(Component.literal("☽ ").append(NodeTerms.event(word(), e.name()))).build();
        }
        if (r.body) {
            context.addOutputPort(BODY, LTypes.FLOW).withDisplayName(Component.literal("⤷ ").append(NodeTerms.parameter(word(), BODY))).build();
        }
        if (r.yields != null) {
            context.addOutputPort(YIELD, LTypes.of(r.yields, false))
                    .withDisplayName(Component.literal(r.yields.glyph + " ").append(NodeTerms.parameter(word(), YIELD))).build();
        }
        if (r.returns == Type.ACTION) {
            context.addOutputPort(NEXT, LTypes.FLOW).withOrientation(PortOrientation.Vertical).withDisplayName(Component.literal("▶")).build();
        }
    }

    /**
     * A construct keyword its shape does not use (a shield's width, a wall's radius), hidden on the node.
     * While the shape is computed rather than picked, nothing is hidden.
     */
    private static boolean unused(PortModel shape, String keyword) {
        if (!ConstructShape.SHAPED.contains(keyword)) return false;
        ConstructShape s = picked(shape);
        return s != null && !s.uses(keyword);
    }

    /** Whether this port is a construct size its shape hides; the graph compiler leaves those out. */
    public static boolean hidden(@Nullable PortModel port) {
        return port != null && port.getOptions() == PortModelOptions.HIDDEN;
    }

    /** The picked shape: the inline choice, or a shape card wired in; null when something else computes it. */
    private static @Nullable ConstructShape picked(PortModel port) {
        if (port.isConnected()) {
            return LogosNode.of(port.getConnectedPorts().getFirst()) instanceof EnumValues.Value v && v.kind == EnumValues.Kind.SHAPE
                    ? ConstructShape.parse(v.value()) : null;
        }
        return port.getEmbeddedValue() != null && port.getEmbeddedValue().getValue() instanceof ConstructShape s ? s : null;
    }

    /** The shape dropdown; a new pick redefines the node so its size inputs follow. */
    private ITypeConfigurable shapeSelector() {
        return (value, type) -> IConfigurable.create(group -> {
            ConstructShape initial = value.getValue() instanceof ConstructShape s ? s : ConstructShape.WALL;
            group.addConfigurator(new SelectorConfigurator<>("", () -> value.getValue() instanceof ConstructShape s ? s : initial, s -> {
                value.setValue(s);
                model().defineNode();
            }, initial, value.forceUpdate(), List.of(ConstructShape.values()),
                    s -> Component.translatable("dev.logos.lang.ConstructShape." + s.name()).getString()));
        });
    }

    private static PortModel input(IPortDefinitionContext context, String id, Component label, Type t, boolean broadcast, String defaultSrc, String paramName) {
        var builder = context.addInputPort(id, LTypes.of(t, broadcast))
                .withDisplayName(label);
        if (EnumValues.stringPort(t)) builder.withCodec(com.mojang.serialization.Codec.STRING)
                .withConfigurable(EnumValues.STRING_EDITOR);
        Object def = defaultValue(t, defaultSrc, paramName);
        if (def != null) builder.withDefaultValue(def);
        else builder.withoutConfigurator();
        return (PortModel) builder.build();
    }

    /** The inline constant an unwired input starts with, or null if the slot can only be wired. */
    public static Object defaultValue(Type t, String defaultSrc, String paramName) {
        return switch (t) {
            case NUM, ANY -> {
                if (defaultSrc != null && Compiler.MAGNITUDES.containsKey(defaultSrc)) yield Compiler.MAGNITUDES.get(defaultSrc).floatValue();
                if (defaultSrc != null && defaultSrc.matches("-?\\d+(\\.\\d+)?")) yield Float.parseFloat(defaultSrc);
                yield PARAM_HINTS.getOrDefault(paramName, defaultSrc == null ? 1f : null);
            }
            case BOOL -> "true".equals(defaultSrc);
            case ELEMENT -> defaultSrc != null && Element.parse(defaultSrc) != null ? Element.parse(defaultSrc) : Element.PHYSICAL;
            case MOTION -> defaultSrc != null && Motion.parse(defaultSrc) != null ? Motion.parse(defaultSrc) : Motion.FORWARD;
            case SHAPE -> defaultSrc != null && ConstructShape.parse(defaultSrc) != null ? ConstructShape.parse(defaultSrc) : ConstructShape.WALL;
            case WEATHER -> defaultSrc != null && Weather.parse(defaultSrc) != null ? Weather.parse(defaultSrc) : Weather.RAIN;
            case STATUS -> "#mark";
            // ❖ an item id / ✧ a particle name typed on the node (mace, portal…); blank = none.
            // ⊙ filters start as a typed id/#tag; wire a (kind …) node in for a kind.
            case TEXT, LOOK, PARTICLE, FILTER -> "";
            default -> null;
        };
    }
}
