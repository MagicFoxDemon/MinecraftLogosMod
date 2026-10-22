package dev.logos.graph;

import java.util.Map;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.port.PortOrientation;
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

    public static String kw(String name) {
        return "kw_" + name;
    }

    public static String flag(String name) {
        return "flag_" + name;
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
        }
    }

    @Override
    public void onDefinePorts(IPortDefinitionContext context) {
        Rune r = rune();
        if (r.returns == Type.ACTION) {
            context.addInputPort(IN, LTypes.FLOW).withOrientation(PortOrientation.Vertical).withDisplayName(Component.literal("▶")).build();
        } else {
            context.addOutputPort(OUT, LTypes.of(r.returns, false)).withDisplayName(Component.literal(r.returns.label())).build();
        }
        for (Rune.Param p : r.params) {
            if (isOption(p)) {
                // Keep the old option as the unwired fallback, and add a typed connector above it.
                if (p.type() != Type.ANCHOR) context.addInputPort(p.name(), LTypes.of(p.type(), false))
                        .withDisplayName(NodeTerms.parameter(word(), p.name())).withoutConfigurator().withoutSerialization().build();
                continue;
            }
            if (p.type() == Type.ACTION) {
                String glyph = p.name().equals("then") ? "✔ " : p.name().equals("else") ? "✘ " : "⤷ ";
                context.addOutputPort(p.name(), LTypes.FLOW).withDisplayName(Component.literal(glyph).append(NodeTerms.parameter(word(), p.name()))).build();
            } else if (p.rest()) {
                Integer n = option(COUNT, Integer.class);
                for (int i = 0; i < Math.max(1, n == null ? 2 : n); i++) {
                    input(context, p.name() + i, NodeTerms.parameter(word(), p.name()).append(" " + (i + 1)), p.type(), false, null, p.name());
                }
            } else {
                input(context, p.name(), NodeTerms.parameter(word(), p.name()), p.type(), p.broadcast(), p.defaultSrc(), p.name());
            }
        }
        for (Rune.Kw k : r.keywords.values()) {
            if (!k.flag()) input(context, kw(k.name()), NodeTerms.parameter(word(), k.name()), k.type(), false, k.defaultSrc(), k.name());
        }
        for (Rune.EventSpec e : r.events.values()) {
            context.addOutputPort(event(e.name()), LTypes.FLOW).withDisplayName(Component.literal("☽ ").append(NodeTerms.event(word(), e.name()))).build();
        }
        if (r.body) {
            context.addOutputPort(BODY, LTypes.FLOW).withDisplayName(Component.literal("⤷ ").append(NodeTerms.parameter(word(), BODY))).build();
        }
        if (r.returns == Type.ACTION) {
            context.addOutputPort(NEXT, LTypes.FLOW).withOrientation(PortOrientation.Vertical).withDisplayName(Component.literal("▶")).build();
        }
    }

    private static void input(IPortDefinitionContext context, String id, Component label, Type t, boolean broadcast, String defaultSrc, String paramName) {
        var builder = context.addInputPort(id, LTypes.of(t, broadcast))
                .withDisplayName(label);
        if (EnumValues.stringPort(t)) builder.withCodec(com.mojang.serialization.Codec.STRING)
                .withConfigurable(EnumValues.STRING_EDITOR);
        Object def = defaultValue(t, defaultSrc, paramName);
        if (def != null) builder.withDefaultValue(def);
        else builder.withoutConfigurator();
        builder.build();
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
