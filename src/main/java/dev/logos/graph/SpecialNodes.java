package dev.logos.graph;

import java.util.Locale;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.port.PortOrientation;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IOptionDefinitionContext;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext;

import dev.logos.lang.Category;
import dev.logos.lang.Type;
import net.minecraft.network.chat.Component;

/** Nodes that are not runes: the spell's invocation root, context values and "remember" (let). */
public final class SpecialNodes {
    public static final String CONTEXT = "☽ 上下文";
    public static final String INVOCATION = "☉ 启式";

    private SpecialNodes() {}

    public enum Trigger {
        CAST, HURT, ATTACK, KILL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Trigger of(String id) {
            for (Trigger t : values()) if (t.id().equals(id)) return t;
            return CAST;
        }
    }

    /** What the spell is for, as a lifeform reads it: AUTO lets the compiler infer it from the runes. */
    public enum IntentChoice {
        AUTO, STRIKE, AREA, CONTROL, HEAL, WARD, MOVE, UTILITY;

        public dev.logos.lang.@org.jspecify.annotations.Nullable Intent intent() {
            return this == AUTO ? null : dev.logos.lang.Intent.valueOf(name());
        }

        public static IntentChoice of(dev.logos.lang.@org.jspecify.annotations.Nullable Intent intent) {
            return intent == null ? AUTO : valueOf(intent.name());
        }
    }

    /** ☉ The entry point: spell name, injected mana and invocation trigger. */
    @NodeAttribute(name = "spell", group = INVOCATION, graphTypes = SpellGraph.class, priority = 100)
    public static class SpellRoot extends LogosNode {
        public static final String NAME = "name";
        public static final String MANA = "mana";
        public static final String TRIGGER = "trigger";
        public static final String PLAYERS = "players";
        public static final String IMMOLATE = "immolate";
        public static final String INTENT = "intent";
        public static final String START = "start";

        @Override
        public Category category() {
            return Category.INVOCATION;
        }

        @Override
        public String word() {
            return "spell";
        }

        @Override
        public String nameKey() {
            return "logos.node.spell";
        }

        @Override
        public String doc() {
            return NodeDescriptions.describe(this);
        }

        @Override
        public void onDefineOptions(IOptionDefinitionContext context) {
            context.addOption(NAME, String.class).withDefaultValue("无名术式").withDisplayName(NodeTerms.parameter(word(), NAME));
            context.addOption(MANA, Integer.class).withDefaultValue(40).withDisplayName(NodeTerms.parameter(word(), MANA));
            context.addOption(TRIGGER, Trigger.class).withDefaultValue(Trigger.CAST).withDisplayName(NodeTerms.parameter(word(), TRIGGER));
            context.addOption(PLAYERS, Boolean.class).withDefaultValue(false).withDisplayName(NodeTerms.parameter(word(), PLAYERS));
            context.addOption(IMMOLATE, Boolean.class).withDefaultValue(true).withDisplayName(NodeTerms.parameter(word(), IMMOLATE));
            context.addOption(INTENT, IntentChoice.class).withDefaultValue(IntentChoice.AUTO).withDisplayName(NodeTerms.parameter(word(), INTENT));
        }

        @Override
        public void onDefinePorts(IPortDefinitionContext context) {
            context.addOutputPort(START, LTypes.FLOW).withOrientation(PortOrientation.Vertical)
                    .withDisplayName(Component.literal("▶ ").append(NodeTerms.parameter(word(), START))).build();
        }
    }

    /** When a spell emitter lets go of a full charge. */
    public enum EmitterMode {
        /** On a redstone pulse, once. */
        PULSE,
        /** Each time it is full, while it has a redstone signal. */
        HOLD,
        /** Every so many seconds. */
        TIMED,
        /** As soon as it is full. */
        FULL;

        public static EmitterMode of(String name) {
            for (EmitterMode m : values()) if (m.name().equals(name)) return m;
            return PULSE;
        }
    }

    /**
     * ☉ The spell emitter itself, standing in for the invocation node while its page is edited in the
     * emitter: the spell's name, injected mana and whether it may touch players, then the emitter's
     * trigger. It compiles as an invocation cast by hand; saving puts the page's own invocation back.
     */
    @NodeAttribute(name = "emitter", group = INVOCATION, graphTypes = SpellGraph.class, priority = 99)
    public static class EmitterRoot extends SpellRoot {
        public static final String MODE = "mode";
        public static final String INTERVAL = "interval";

        @Override
        public String word() {
            return "emitter";
        }

        @Override
        public String nameKey() {
            return "logos.node.emitter";
        }

        public EmitterMode mode() {
            EmitterMode m = option(MODE, EmitterMode.class);
            return m == null ? EmitterMode.PULSE : m;
        }

        public int interval() {
            Integer i = option(INTERVAL, Integer.class);
            return i == null ? 5 : i;
        }

        @Override
        public void onDefineOptions(IOptionDefinitionContext context) {
            context.addOption(NAME, String.class).withDefaultValue("无名术式").withDisplayName(NodeTerms.parameter(word(), NAME));
            context.addOption(MANA, Integer.class).withDefaultValue(40).withDisplayName(NodeTerms.parameter(word(), MANA));
            context.addOption(PLAYERS, Boolean.class).withDefaultValue(false).withDisplayName(NodeTerms.parameter(word(), PLAYERS));
            context.addOption(MODE, EmitterMode.class).withDefaultValue(EmitterMode.PULSE).withDisplayName(NodeTerms.parameter(word(), MODE));
            context.addOption(INTERVAL, Integer.class).withDefaultValue(5).withDisplayName(NodeTerms.parameter(word(), INTERVAL));
        }
    }

    /** A value provided by the surrounding event: caster, target, here, aim… */
    public abstract static class ContextValue extends LogosNode {
        private final String name;
        private final Type type;

        protected ContextValue(String name, Type type) {
            this.name = name;
            this.type = type;
        }

        public String contextName() {
            return name;
        }

        @Override
        public Category category() {
            return Category.BINDING;
        }

        @Override
        public String word() {
            return name;
        }

        @Override
        public String nameKey() {
            return "logos.node.ctx." + name;
        }

        @Override
        public String doc() {
            return NodeDescriptions.describe(this);
        }

        @Override
        public void onDefinePorts(IPortDefinitionContext context) {
            context.addOutputPort(RuneNode.OUT, LTypes.of(type, false)).withDisplayName(Component.literal(type.label())).build();
        }
    }

    @NodeAttribute(name = "ctx_caster", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Caster extends ContextValue {
        public Caster() {
            super("caster", Type.ENTITY);
        }
    }

    @NodeAttribute(name = "ctx_target", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Target extends ContextValue {
        public Target() {
            super("target", Type.ENTITY);
        }
    }

    @NodeAttribute(name = "ctx_here", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Here extends ContextValue {
        public Here() {
            super("here", Type.POS);
        }
    }

    @NodeAttribute(name = "ctx_aim", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Aim extends ContextValue {
        public Aim() {
            super("aim", Type.DIR);
        }
    }

    @NodeAttribute(name = "ctx_up", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Up extends ContextValue {
        public Up() {
            super("up", Type.DIR);
        }
    }

    @NodeAttribute(name = "ctx_down", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Down extends ContextValue {
        public Down() {
            super("down", Type.DIR);
        }
    }

    /** ⚓ One of your anchors, read as a position ({@code ^name}); chosen from the anchors you have set. */
    @NodeAttribute(name = "anchor_value", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class AnchorValue extends LogosNode {
        public static final String NAME = "anchor_name";

        public String anchor() {
            String n = option(NAME, String.class);
            return n == null || n.isBlank() ? "家" : n.strip();
        }

        @Override
        public Category category() {
            return Category.BINDING;
        }

        @Override
        public String word() {
            return "^";
        }

        @Override
        public String nameKey() {
            return "logos.node.anchor";
        }

        @Override
        public String doc() {
            return NodeDescriptions.describe(this);
        }

        @Override
        public void onDefineOptions(IOptionDefinitionContext context) {
            context.addOption(NAME, String.class).withDefaultValue("").withConfigurable(AnchorNames.SELECTOR)
                    .withDisplayName(Component.literal("⚓ ").append(NodeTerms.parameter(word(), "anchor")));
        }

        @Override
        public void onDefinePorts(IPortDefinitionContext context) {
            context.addOutputPort(RuneNode.OUT, LTypes.of(Type.POS, false)).withDisplayName(Component.literal(Type.POS.label())).build();
        }
    }

    @NodeAttribute(name = "ctx_self", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Self extends ContextValue {
        public Self() {
            super("self", Type.ENTITY);
        }
    }

    /** 此形: inside a form's events, the body that form made. */
    @NodeAttribute(name = "ctx_form", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Form extends ContextValue {
        public Form() {
            super("form", Type.FORM);
        }
    }

    @NodeAttribute(name = "ctx_i", group = CONTEXT, graphTypes = SpellGraph.class)
    public static class Index extends ContextValue {
        public Index() {
            super("i", Type.NUM);
        }
    }

    public enum RememberKind {
        ENTITY(Type.ENTITY), ENTITIES(Type.ENTITIES), POS(Type.POS), DIR(Type.DIR), NUM(Type.NUM);

        public final Type type;

        RememberKind(Type type) {
            this.type = type;
        }

        public static RememberKind of(Type t) {
            for (RememberKind k : values()) if (k.type == t) return k;
            return NUM;
        }
    }

    /**
     * ♄ Remember (let): evaluates a value once and keeps it for everything after it in the flow.
     * Without it, a value wired to several inputs is re-evaluated at each use.
     */
    @NodeAttribute(name = "remember", group = RuneNodes.LOGIC, graphTypes = SpellGraph.class)
    public static class Remember extends LogosNode {
        public static final String KIND = "kind";
        public static final String VALUE = "value";

        @Override
        public Category category() {
            return Category.LOGIC;
        }

        @Override
        public String word() {
            return "let";
        }

        @Override
        public String nameKey() {
            return "logos.node.let";
        }

        @Override
        public String doc() {
            return NodeDescriptions.describe(this);
        }

        public RememberKind kind() {
            RememberKind k = option(KIND, RememberKind.class);
            return k == null ? RememberKind.POS : k;
        }

        @Override
        public void onDefineOptions(IOptionDefinitionContext context) {
            context.addOption(KIND, RememberKind.class).withDefaultValue(RememberKind.POS).withDisplayName(NodeTerms.parameter(word(), KIND));
        }

        @Override
        public void onDefinePorts(IPortDefinitionContext context) {
            TypeHandle h = LTypes.of(kind().type, false);
            context.addInputPort(RuneNode.IN, LTypes.FLOW).withOrientation(PortOrientation.Vertical).withDisplayName(Component.literal("▶")).build();
            var value = context.addInputPort(VALUE, h).withDisplayName(Component.literal(kind().type.glyph + " ").append(NodeTerms.parameter(word(), VALUE)));
            Object def = RuneNode.defaultValue(kind().type, null, VALUE);
            if (def != null) value.withDefaultValue(def);
            else value.withoutConfigurator();
            value.build();
            context.addOutputPort(RuneNode.OUT, h).withDisplayName(Component.literal(kind().type.glyph + " ").append(NodeTerms.parameter(word(), "remembered"))).build();
            context.addOutputPort(RuneNode.NEXT, LTypes.FLOW).withOrientation(PortOrientation.Vertical).withDisplayName(Component.literal("▶")).build();
        }
    }
}
