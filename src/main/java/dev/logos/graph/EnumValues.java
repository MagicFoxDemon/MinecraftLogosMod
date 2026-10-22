package dev.logos.graph;

import java.util.Locale;
import java.util.function.BiFunction;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.ui.StringConfigurator;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.ITypeConfigurable;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;

import dev.logos.lang.Category;
import dev.logos.lang.Type;
import net.minecraft.network.chat.Component;

/** Typed, reusable literals. Their string keys survive registry reordering, language changes and missing mods. */
public final class EnumValues {
    public static final String VALUE = "registry_value";
    public static final String FILTER = "filter";
    public static final String GROUP = "◇ 枚举";

    /** Installed by the client; graph compilation and dedicated servers need no rendering classes. */
    public static BiFunction<Kind, String, Component> names = (kind, id) -> Component.literal(id);

    /** Ports have distinct semantic types, while their embedded legacy IDs keep the string codec/editor. */
    public static final ITypeConfigurable STRING_EDITOR = (value, type) -> IConfigurable.create(group ->
            group.addConfigurator(new StringConfigurator("", () -> value.getValue() instanceof String s ? s : "",
                    value::setValue, "", value.forceUpdate())));

    public enum Kind {
        CREATURE(Type.CREATURE, "minecraft:zombie"), ITEM(Type.LOOK, "minecraft:diamond_sword"),
        BLOCK(Type.LOOK, "minecraft:stone"), PARTICLE(Type.PARTICLE, "minecraft:end_rod"),
        DIMENSION(Type.DIMENSION, "minecraft:overworld"), EFFECT(Type.MOB_EFFECT, "minecraft:speed"),
        ELEMENT(Type.ELEMENT, "fire"), MOTION(Type.MOTION, "forward"), SHAPE(Type.SHAPE, "wall"),
        WEATHER(Type.WEATHER, "rain"), KIND(Type.KIND, "enemies");

        public final Type type;
        public final String initial;

        Kind(Type type, String initial) { this.type = type; this.initial = initial; }
        public String key() { return "logos.enum." + name().toLowerCase(Locale.ROOT); }
        public boolean registry() { return ordinal() <= EFFECT.ordinal(); }
        public boolean filterable() { return this == CREATURE || this == ITEM || this == BLOCK; }

        public Value create() {
            return switch (this) {
                case CREATURE -> new Creature(); case ITEM -> new Item(); case BLOCK -> new Block();
                case PARTICLE -> new Particle(); case DIMENSION -> new Dimension(); case EFFECT -> new Effect();
                case ELEMENT -> new Element(); case MOTION -> new Motion(); case SHAPE -> new Shape();
                case WEATHER -> new Weather(); case KIND -> new TargetKind();
            };
        }
    }

    private EnumValues() {}

    public static boolean stringPort(Type type) {
        return switch (type) {
            case CREATURE, DIMENSION, MOB_EFFECT, LOOK, PARTICLE, KIND, FILTER -> true;
            default -> false;
        };
    }

    /** One registered node class per family, never one class per Minecraft registry entry. */
    public abstract static class Value extends LogosNode {
        public final Kind kind;
        private String initial;
        protected Value(Kind kind) { this.kind = kind; this.initial = kind.initial; }
        /** Supplies the default before the toolkit defines this newly created card's option. */
        public Value selected(String id) { initial = id; return this; }
        public String value() {
            String value = option(VALUE, String.class);
            return value == null ? initial : value.strip();
        }
        @Override public Category category() { return Category.VALUE; }
        @Override public String word() { return "enum_" + kind.name().toLowerCase(Locale.ROOT); }
        @Override public String nameKey() { return kind.key(); }
        @Override public String title() { return names.apply(kind, value()).getString(); }
        @Override public String doc() {
            return Component.translatable("logos.enum.value_help").getString() + "\n"
                    + Component.translatable("logos.enum.id").getString() + "：" + value()
                    + (kind.filterable() ? "\n" + Component.translatable("logos.enum.filter_description").getString() : "");
        }
        @Override public void onDefineOptions(IOptionDefinitionContext context) {
            context.addOption(VALUE, String.class).withDefaultValue(initial)
                    .withDisplayName(Component.translatable("logos.enum.id"));
        }
        @Override public void onDefinePorts(IPortDefinitionContext context) {
            context.addOutputPort(RuneNode.OUT, LTypes.of(kind.type, false))
                    .withDisplayName(Component.literal(kind.type.label())).build();
            if (kind.filterable()) context.addOutputPort(FILTER, LTypes.of(Type.FILTER, false))
                    .withDisplayName(Component.translatable("logos.enum.filter")).build();
        }
    }

    @NodeAttribute(name="enum_creature", group=GROUP, graphTypes=SpellGraph.class)
    public static class Creature extends Value { public Creature() { super(Kind.CREATURE); } }
    @NodeAttribute(name="enum_item", group=GROUP, graphTypes=SpellGraph.class)
    public static class Item extends Value { public Item() { super(Kind.ITEM); } }
    @NodeAttribute(name="enum_block", group=GROUP, graphTypes=SpellGraph.class)
    public static class Block extends Value { public Block() { super(Kind.BLOCK); } }
    @NodeAttribute(name="enum_particle", group=GROUP, graphTypes=SpellGraph.class)
    public static class Particle extends Value { public Particle() { super(Kind.PARTICLE); } }
    @NodeAttribute(name="enum_dimension", group=GROUP, graphTypes=SpellGraph.class)
    public static class Dimension extends Value { public Dimension() { super(Kind.DIMENSION); } }
    @NodeAttribute(name="enum_effect", group=GROUP, graphTypes=SpellGraph.class)
    public static class Effect extends Value { public Effect() { super(Kind.EFFECT); } }
    @NodeAttribute(name="enum_element", group=GROUP, graphTypes=SpellGraph.class)
    public static class Element extends Value { public Element() { super(Kind.ELEMENT); } }
    @NodeAttribute(name="enum_motion", group=GROUP, graphTypes=SpellGraph.class)
    public static class Motion extends Value { public Motion() { super(Kind.MOTION); } }
    @NodeAttribute(name="enum_shape", group=GROUP, graphTypes=SpellGraph.class)
    public static class Shape extends Value { public Shape() { super(Kind.SHAPE); } }
    @NodeAttribute(name="enum_weather", group=GROUP, graphTypes=SpellGraph.class)
    public static class Weather extends Value { public Weather() { super(Kind.WEATHER); } }
    @NodeAttribute(name="enum_kind", group=GROUP, graphTypes=SpellGraph.class)
    public static class TargetKind extends Value { public TargetKind() { super(Kind.KIND); } }
}
