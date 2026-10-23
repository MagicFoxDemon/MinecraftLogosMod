package dev.logos.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.NodeAttribute;

/**
 * One node class per rune (the toolkit discovers node types by annotated class). Each is a
 * one-liner: the ports come from the rune's declaration in {@link dev.logos.lang.Runes}.
 */
public final class RuneNodes {
    public static final String FORM = "🜔 盐·形";
    public static final String BINDING = "☌ 汞·指向";
    public static final String EFFECT = "🜍 硫·变";
    public static final String LOGIC = "♄ 律式";
    public static final String VALUE = "⚖ 数·量";

    /** Rune name → node factory, used when building a graph from source. */
    public static final Map<String, Supplier<RuneNode>> BY_RUNE = new LinkedHashMap<>();

    private RuneNodes() {}

    @NodeAttribute(name = "projectile", group = FORM, graphTypes = SpellGraph.class)
    public static class Projectile extends RuneNode {
        public Projectile() {
            super("projectile");
        }
    }

    @NodeAttribute(name = "construct", group = FORM, graphTypes = SpellGraph.class)
    public static class Construct extends RuneNode {
        public Construct() {
            super("construct");
        }
    }

    @NodeAttribute(name = "beam", group = FORM, graphTypes = SpellGraph.class)
    public static class Beam extends RuneNode {
        public Beam() {
            super("beam");
        }
    }

    @NodeAttribute(name = "field", group = FORM, graphTypes = SpellGraph.class)
    public static class Field extends RuneNode {
        public Field() {
            super("field");
        }
    }

    @NodeAttribute(name = "ray", group = FORM, graphTypes = SpellGraph.class)
    public static class Ray extends RuneNode {
        public Ray() {
            super("ray");
        }
    }

    @NodeAttribute(name = "split", group = FORM, graphTypes = SpellGraph.class)
    public static class Split extends RuneNode {
        public Split() {
            super("split");
        }
    }

    @NodeAttribute(name = "nearest", group = BINDING, graphTypes = SpellGraph.class)
    public static class Nearest extends RuneNode {
        public Nearest() {
            super("nearest");
        }
    }

    @NodeAttribute(name = "enemies", group = BINDING, graphTypes = SpellGraph.class)
    public static class Enemies extends RuneNode {
        public Enemies() {
            super("enemies");
        }
    }

    @NodeAttribute(name = "hit_list", group = BINDING, graphTypes = SpellGraph.class)
    public static class HitList extends RuneNode {
        public HitList() {
            super("hit-list");
        }
    }

    @NodeAttribute(name = "marked", group = BINDING, graphTypes = SpellGraph.class)
    public static class Marked extends RuneNode {
        public Marked() {
            super("marked");
        }
    }

    @NodeAttribute(name = "pos_of", group = BINDING, graphTypes = SpellGraph.class)
    public static class PosOf extends RuneNode {
        public PosOf() {
            super("pos-of");
        }
    }

    @NodeAttribute(name = "above", group = BINDING, graphTypes = SpellGraph.class)
    public static class Above extends RuneNode {
        public Above() {
            super("above");
        }
    }

    @NodeAttribute(name = "ahead", group = BINDING, graphTypes = SpellGraph.class)
    public static class Ahead extends RuneNode {
        public Ahead() {
            super("ahead");
        }
    }

    @NodeAttribute(name = "offset", group = BINDING, graphTypes = SpellGraph.class)
    public static class Offset extends RuneNode {
        public Offset() {
            super("offset");
        }
    }

    @NodeAttribute(name = "look_point", group = BINDING, graphTypes = SpellGraph.class)
    public static class LookPoint extends RuneNode {
        public LookPoint() {
            super("look-point");
        }
    }

    @NodeAttribute(name = "toward", group = BINDING, graphTypes = SpellGraph.class)
    public static class Toward extends RuneNode {
        public Toward() {
            super("toward");
        }
    }

    @NodeAttribute(name = "random_dir", group = BINDING, graphTypes = SpellGraph.class)
    public static class RandomDir extends RuneNode {
        public RandomDir() {
            super("random-dir");
        }
    }

    @NodeAttribute(name = "damage", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Damage extends RuneNode {
        public Damage() {
            super("damage");
        }
    }

    @NodeAttribute(name = "drain", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Drain extends RuneNode {
        public Drain() {
            super("drain");
        }
    }

    @NodeAttribute(name = "heal", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Heal extends RuneNode {
        public Heal() {
            super("heal");
        }
    }

    @NodeAttribute(name = "infuse", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Infuse extends RuneNode {
        public Infuse() {
            super("infuse");
        }
    }

    @NodeAttribute(name = "shield", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Shield extends RuneNode {
        public Shield() {
            super("shield");
        }
    }

    @NodeAttribute(name = "ignite", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Ignite extends RuneNode {
        public Ignite() {
            super("ignite");
        }
    }

    @NodeAttribute(name = "freeze", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Freeze extends RuneNode {
        public Freeze() {
            super("freeze");
        }
    }

    @NodeAttribute(name = "time_stop", group = EFFECT, graphTypes = SpellGraph.class)
    public static class TimeStop extends RuneNode {
        public TimeStop() {
            super("time-stop");
        }
    }

    @NodeAttribute(name = "damage_all", group = EFFECT, graphTypes = SpellGraph.class)
    public static class DamageAll extends RuneNode {
        public DamageAll() {
            super("damage-all");
        }
    }

    @NodeAttribute(name = "ignite_all", group = EFFECT, graphTypes = SpellGraph.class)
    public static class IgniteAll extends RuneNode {
        public IgniteAll() {
            super("ignite-all");
        }
    }

    @NodeAttribute(name = "freeze_all", group = EFFECT, graphTypes = SpellGraph.class)
    public static class FreezeAll extends RuneNode {
        public FreezeAll() {
            super("freeze-all");
        }
    }

    @NodeAttribute(name = "equip", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Equip extends RuneNode {
        public Equip() {
            super("equip");
        }
    }

    @NodeAttribute(name = "disarm", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Disarm extends RuneNode {
        public Disarm() {
            super("disarm");
        }
    }

    @NodeAttribute(name = "potion", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Potion extends RuneNode {
        public Potion() {
            super("potion");
        }
    }

    @NodeAttribute(name = "push", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Push extends RuneNode {
        public Push() {
            super("push");
        }
    }

    @NodeAttribute(name = "pull", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Pull extends RuneNode {
        public Pull() {
            super("pull");
        }
    }

    @NodeAttribute(name = "launch", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Launch extends RuneNode {
        public Launch() {
            super("launch");
        }
    }

    @NodeAttribute(name = "mark", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Mark extends RuneNode {
        public Mark() {
            super("mark");
        }
    }

    @NodeAttribute(name = "unmark", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Unmark extends RuneNode {
        public Unmark() {
            super("unmark");
        }
    }

    @NodeAttribute(name = "glow", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Glow extends RuneNode {
        public Glow() {
            super("glow");
        }
    }

    @NodeAttribute(name = "execute", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Execute extends RuneNode {
        public Execute() {
            super("execute");
        }
    }

    @NodeAttribute(name = "blink", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Blink extends RuneNode {
        public Blink() {
            super("blink");
        }
    }

    @NodeAttribute(name = "echo", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Echo extends RuneNode {
        public Echo() {
            super("echo");
        }
    }

    @NodeAttribute(name = "sic", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Sic extends RuneNode {
        public Sic() {
            super("sic");
        }
    }

    @NodeAttribute(name = "stance", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Stance extends RuneNode {
        public Stance() {
            super("stance");
        }
    }

    @NodeAttribute(name = "gather", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Gather extends RuneNode {
        public Gather() {
            super("gather");
        }
    }

    @NodeAttribute(name = "master", group = BINDING, graphTypes = SpellGraph.class)
    public static class Master extends RuneNode {
        public Master() {
            super("master");
        }
    }

    @NodeAttribute(name = "foe", group = BINDING, graphTypes = SpellGraph.class)
    public static class Foe extends RuneNode {
        public Foe() {
            super("foe");
        }
    }

    @NodeAttribute(name = "familiar", group = BINDING, graphTypes = SpellGraph.class)
    public static class Familiar extends RuneNode {
        public Familiar() {
            super("familiar");
        }
    }

    @NodeAttribute(name = "summon", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Summon extends RuneNode {
        public Summon() {
            super("summon");
        }
    }

    @NodeAttribute(name = "dismiss", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Dismiss extends RuneNode {
        public Dismiss() {
            super("dismiss");
        }
    }

    @NodeAttribute(name = "anchor", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Anchor extends RuneNode {
        public Anchor() {
            super("anchor");
        }
    }

    @NodeAttribute(name = "unanchor", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Unanchor extends RuneNode {
        public Unanchor() {
            super("unanchor");
        }
    }

    @NodeAttribute(name = "move", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Move extends RuneNode {
        public Move() {
            super("move");
        }
    }

    @NodeAttribute(name = "halt", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Halt extends RuneNode {
        public Halt() {
            super("halt");
        }
    }

    @NodeAttribute(name = "copy", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Copy extends RuneNode {
        public Copy() {
            super("copy");
        }
    }

    @NodeAttribute(name = "phantasm", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Phantasm extends RuneNode {
        public Phantasm() {
            super("phantasm");
        }
    }

    @NodeAttribute(name = "if", group = LOGIC, graphTypes = SpellGraph.class)
    public static class If extends RuneNode {
        public If() {
            super("if");
        }
    }

    @NodeAttribute(name = "each", group = LOGIC, graphTypes = SpellGraph.class)
    public static class Each extends RuneNode {
        public Each() {
            super("each");
        }
    }

    @NodeAttribute(name = "repeat", group = LOGIC, graphTypes = SpellGraph.class)
    public static class Repeat extends RuneNode {
        public Repeat() {
            super("repeat");
        }
    }

    @NodeAttribute(name = "delay", group = LOGIC, graphTypes = SpellGraph.class)
    public static class Delay extends RuneNode {
        public Delay() {
            super("delay");
        }
    }

    @NodeAttribute(name = "recurse", group = LOGIC, graphTypes = SpellGraph.class)
    public static class Recurse extends RuneNode {
        public Recurse() {
            super("recurse");
        }
    }

    @NodeAttribute(name = "stop", group = LOGIC, graphTypes = SpellGraph.class)
    public static class Stop extends RuneNode {
        public Stop() {
            super("stop");
        }
    }

    @NodeAttribute(name = "add", group = VALUE, graphTypes = SpellGraph.class)
    public static class Add extends RuneNode {
        public Add() {
            super("+");
        }
    }

    @NodeAttribute(name = "mul", group = VALUE, graphTypes = SpellGraph.class)
    public static class Mul extends RuneNode {
        public Mul() {
            super("*");
        }
    }

    @NodeAttribute(name = "min", group = VALUE, graphTypes = SpellGraph.class)
    public static class Min extends RuneNode {
        public Min() {
            super("min");
        }
    }

    @NodeAttribute(name = "max", group = VALUE, graphTypes = SpellGraph.class)
    public static class Max extends RuneNode {
        public Max() {
            super("max");
        }
    }

    @NodeAttribute(name = "sub", group = VALUE, graphTypes = SpellGraph.class)
    public static class Sub extends RuneNode {
        public Sub() {
            super("-");
        }
    }

    @NodeAttribute(name = "div", group = VALUE, graphTypes = SpellGraph.class)
    public static class Div extends RuneNode {
        public Div() {
            super("/");
        }
    }

    @NodeAttribute(name = "hp", group = VALUE, graphTypes = SpellGraph.class)
    public static class Hp extends RuneNode {
        public Hp() {
            super("hp");
        }
    }

    @NodeAttribute(name = "max_hp", group = VALUE, graphTypes = SpellGraph.class)
    public static class MaxHp extends RuneNode {
        public MaxHp() {
            super("max-hp");
        }
    }

    @NodeAttribute(name = "hp_ratio", group = VALUE, graphTypes = SpellGraph.class)
    public static class HpRatio extends RuneNode {
        public HpRatio() {
            super("hp-ratio");
        }
    }

    @NodeAttribute(name = "missing_hp", group = VALUE, graphTypes = SpellGraph.class)
    public static class MissingHp extends RuneNode {
        public MissingHp() {
            super("missing-hp");
        }
    }

    @NodeAttribute(name = "stacks", group = VALUE, graphTypes = SpellGraph.class)
    public static class Stacks extends RuneNode {
        public Stacks() {
            super("stacks");
        }
    }

    @NodeAttribute(name = "distance", group = VALUE, graphTypes = SpellGraph.class)
    public static class Distance extends RuneNode {
        public Distance() {
            super("distance");
        }
    }

    @NodeAttribute(name = "count", group = VALUE, graphTypes = SpellGraph.class)
    public static class Count extends RuneNode {
        public Count() {
            super("count");
        }
    }

    @NodeAttribute(name = "mana_left", group = VALUE, graphTypes = SpellGraph.class)
    public static class ManaLeft extends RuneNode {
        public ManaLeft() {
            super("mana-left");
        }
    }

    @NodeAttribute(name = "depth", group = VALUE, graphTypes = SpellGraph.class)
    public static class Depth extends RuneNode {
        public Depth() {
            super("depth");
        }
    }

    @NodeAttribute(name = "random", group = VALUE, graphTypes = SpellGraph.class)
    public static class Random extends RuneNode {
        public Random() {
            super("random");
        }
    }

    @NodeAttribute(name = "mix", group = VALUE, graphTypes = SpellGraph.class)
    public static class Mix extends RuneNode {
        public Mix() {
            super("mix");
        }
    }

    @NodeAttribute(name = "round", group = VALUE, graphTypes = SpellGraph.class)
    public static class Round extends RuneNode {
        public Round() {
            super("round");
        }
    }

    @NodeAttribute(name = "clamp", group = VALUE, graphTypes = SpellGraph.class)
    public static class Clamp extends RuneNode {
        public Clamp() {
            super("clamp");
        }
    }

    @NodeAttribute(name = "wave", group = VALUE, graphTypes = SpellGraph.class)
    public static class Wave extends RuneNode {
        public Wave() {
            super("wave");
        }
    }

    @NodeAttribute(name = "mix_pos", group = BINDING, graphTypes = SpellGraph.class)
    public static class MixPos extends RuneNode {
        public MixPos() {
            super("mix-pos");
        }
    }

    @NodeAttribute(name = "lt", group = VALUE, graphTypes = SpellGraph.class)
    public static class Lt extends RuneNode {
        public Lt() {
            super("<");
        }
    }

    @NodeAttribute(name = "gt", group = VALUE, graphTypes = SpellGraph.class)
    public static class Gt extends RuneNode {
        public Gt() {
            super(">");
        }
    }

    @NodeAttribute(name = "le", group = VALUE, graphTypes = SpellGraph.class)
    public static class Le extends RuneNode {
        public Le() {
            super("<=");
        }
    }

    @NodeAttribute(name = "ge", group = VALUE, graphTypes = SpellGraph.class)
    public static class Ge extends RuneNode {
        public Ge() {
            super(">=");
        }
    }

    @NodeAttribute(name = "eq", group = VALUE, graphTypes = SpellGraph.class)
    public static class Eq extends RuneNode {
        public Eq() {
            super("=");
        }
    }

    @NodeAttribute(name = "and", group = VALUE, graphTypes = SpellGraph.class)
    public static class And extends RuneNode {
        public And() {
            super("and");
        }
    }

    @NodeAttribute(name = "or", group = VALUE, graphTypes = SpellGraph.class)
    public static class Or extends RuneNode {
        public Or() {
            super("or");
        }
    }

    @NodeAttribute(name = "not", group = VALUE, graphTypes = SpellGraph.class)
    public static class Not extends RuneNode {
        public Not() {
            super("not");
        }
    }

    @NodeAttribute(name = "has", group = VALUE, graphTypes = SpellGraph.class)
    public static class Has extends RuneNode {
        public Has() {
            super("has");
        }
    }

    @NodeAttribute(name = "alive", group = VALUE, graphTypes = SpellGraph.class)
    public static class Alive extends RuneNode {
        public Alive() {
            super("alive");
        }
    }

    @NodeAttribute(name = "players", group = BINDING, graphTypes = SpellGraph.class)
    public static class Players extends RuneNode {
        public Players() {
            super("players");
        }
    }

    @NodeAttribute(name = "nearest-player", group = BINDING, graphTypes = SpellGraph.class)
    public static class NearestPlayer extends RuneNode {
        public NearestPlayer() {
            super("nearest-player");
        }
    }

    @NodeAttribute(name = "entities", group = BINDING, graphTypes = SpellGraph.class)
    public static class Entities extends RuneNode {
        public Entities() {
            super("entities");
        }
    }

    @NodeAttribute(name = "kind", group = BINDING, graphTypes = SpellGraph.class)
    public static class Kind extends RuneNode {
        public Kind() {
            super("kind");
        }
    }

    @NodeAttribute(name = "any_of", group = BINDING, graphTypes = SpellGraph.class)
    public static class AnyOf extends RuneNode {
        public AnyOf() {
            super("any-of");
        }
    }

    @NodeAttribute(name = "all_of", group = BINDING, graphTypes = SpellGraph.class)
    public static class AllOf extends RuneNode {
        public AllOf() {
            super("all-of");
        }
    }

    @NodeAttribute(name = "except", group = BINDING, graphTypes = SpellGraph.class)
    public static class Except extends RuneNode {
        public Except() {
            super("except");
        }
    }

    @NodeAttribute(name = "items", group = BINDING, graphTypes = SpellGraph.class)
    public static class Items extends RuneNode {
        public Items() {
            super("items");
        }
    }

    @NodeAttribute(name = "blocks", group = BINDING, graphTypes = SpellGraph.class)
    public static class BlocksSel extends RuneNode {
        public BlocksSel() {
            super("blocks");
        }
    }

    @NodeAttribute(name = "look_block", group = BINDING, graphTypes = SpellGraph.class)
    public static class LookBlock extends RuneNode {
        public LookBlock() {
            super("look-block");
        }
    }

    @NodeAttribute(name = "in_dimension", group = BINDING, graphTypes = SpellGraph.class)
    public static class InDimension extends RuneNode {
        public InDimension() {
            super("in-dimension");
        }
    }

    @NodeAttribute(name = "toggle_state", group = EFFECT, graphTypes = SpellGraph.class)
    public static class ToggleState extends RuneNode {
        public ToggleState() {
            super("toggle-state");
        }
    }

    @NodeAttribute(name = "grow", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Grow extends RuneNode {
        public Grow() {
            super("grow");
        }
    }

    @NodeAttribute(name = "time_skip", group = EFFECT, graphTypes = SpellGraph.class)
    public static class TimeSkip extends RuneNode {
        public TimeSkip() {
            super("time-skip");
        }
    }

    @NodeAttribute(name = "world_weather", group = EFFECT, graphTypes = SpellGraph.class)
    public static class WorldWeather extends RuneNode {
        public WorldWeather() {
            super("world-weather");
        }
    }

    @NodeAttribute(name = "weather", group = EFFECT, graphTypes = SpellGraph.class)
    public static class AreaWeather extends RuneNode {
        public AreaWeather() {
            super("weather");
        }
    }

    @NodeAttribute(name = "decay", group = EFFECT, graphTypes = SpellGraph.class)
    public static class Decay extends RuneNode {
        public Decay() {
            super("decay");
        }
    }

    @NodeAttribute(name = "break_block", group = EFFECT, graphTypes = SpellGraph.class)
    public static class BreakBlock extends RuneNode {
        public BreakBlock() {
            super("break-block");
        }
    }

    @NodeAttribute(name = "lightning", group = EFFECT, graphTypes = SpellGraph.class)
    public static class LightningStrike extends RuneNode {
        public LightningStrike() {
            super("lightning");
        }
    }

    @NodeAttribute(name = "set_fire", group = EFFECT, graphTypes = SpellGraph.class)
    public static class SetFire extends RuneNode {
        public SetFire() {
            super("set-fire");
        }
    }

    @NodeAttribute(name = "infuse_block", group = EFFECT, graphTypes = SpellGraph.class)
    public static class InfuseBlock extends RuneNode {
        public InfuseBlock() {
            super("infuse-block");
        }
    }

    @NodeAttribute(name = "each_block", group = LOGIC, graphTypes = SpellGraph.class)
    public static class EachBlock extends RuneNode {
        public EachBlock() {
            super("each-block");
        }
    }

    @NodeAttribute(name = "is", group = VALUE, graphTypes = SpellGraph.class)
    public static class Is extends RuneNode {
        public Is() {
            super("is");
        }
    }

    @NodeAttribute(name = "block_is", group = VALUE, graphTypes = SpellGraph.class)
    public static class BlockIs extends RuneNode {
        public BlockIs() {
            super("block-is");
        }
    }

    @NodeAttribute(name = "is_air", group = VALUE, graphTypes = SpellGraph.class)
    public static class IsAir extends RuneNode {
        public IsAir() {
            super("is-air");
        }
    }

    @NodeAttribute(name = "block_count", group = VALUE, graphTypes = SpellGraph.class)
    public static class BlockCount extends RuneNode {
        public BlockCount() {
            super("block-count");
        }
    }

    static {
        BY_RUNE.put("players", Players::new);
        BY_RUNE.put("nearest-player", NearestPlayer::new);
        BY_RUNE.put("items", Items::new);
        BY_RUNE.put("entities", Entities::new);
        BY_RUNE.put("kind", Kind::new);
        BY_RUNE.put("any-of", AnyOf::new);
        BY_RUNE.put("all-of", AllOf::new);
        BY_RUNE.put("except", Except::new);
        BY_RUNE.put("blocks", BlocksSel::new);
        BY_RUNE.put("look-block", LookBlock::new);
        BY_RUNE.put("in-dimension", InDimension::new);
        BY_RUNE.put("toggle-state", ToggleState::new);
        BY_RUNE.put("grow", Grow::new);
        BY_RUNE.put("time-skip", TimeSkip::new);
        BY_RUNE.put("world-weather", WorldWeather::new);
        BY_RUNE.put("weather", AreaWeather::new);
        BY_RUNE.put("decay", Decay::new);
        BY_RUNE.put("break-block", BreakBlock::new);
        BY_RUNE.put("lightning", LightningStrike::new);
        BY_RUNE.put("set-fire", SetFire::new);
        BY_RUNE.put("infuse-block", InfuseBlock::new);
        BY_RUNE.put("each-block", EachBlock::new);
        BY_RUNE.put("block-is", BlockIs::new);
        BY_RUNE.put("is", Is::new);
        BY_RUNE.put("is-air", IsAir::new);
        BY_RUNE.put("block-count", BlockCount::new);
        BY_RUNE.put("projectile", Projectile::new);
        BY_RUNE.put("field", Field::new);
        BY_RUNE.put("beam", Beam::new);
        BY_RUNE.put("construct", Construct::new);
        BY_RUNE.put("ray", Ray::new);
        BY_RUNE.put("split", Split::new);
        BY_RUNE.put("nearest", Nearest::new);
        BY_RUNE.put("enemies", Enemies::new);
        BY_RUNE.put("hit-list", HitList::new);
        BY_RUNE.put("marked", Marked::new);
        BY_RUNE.put("pos-of", PosOf::new);
        BY_RUNE.put("above", Above::new);
        BY_RUNE.put("ahead", Ahead::new);
        BY_RUNE.put("offset", Offset::new);
        BY_RUNE.put("look-point", LookPoint::new);
        BY_RUNE.put("toward", Toward::new);
        BY_RUNE.put("random-dir", RandomDir::new);
        BY_RUNE.put("damage", Damage::new);
        BY_RUNE.put("drain", Drain::new);
        BY_RUNE.put("heal", Heal::new);
        BY_RUNE.put("infuse", Infuse::new);
        BY_RUNE.put("shield", Shield::new);
        BY_RUNE.put("ignite", Ignite::new);
        BY_RUNE.put("freeze", Freeze::new);
        BY_RUNE.put("time-stop", TimeStop::new);
        BY_RUNE.put("damage-all", DamageAll::new);
        BY_RUNE.put("ignite-all", IgniteAll::new);
        BY_RUNE.put("freeze-all", FreezeAll::new);
        BY_RUNE.put("disarm", Disarm::new);
        BY_RUNE.put("equip", Equip::new);
        BY_RUNE.put("potion", Potion::new);
        BY_RUNE.put("push", Push::new);
        BY_RUNE.put("pull", Pull::new);
        BY_RUNE.put("launch", Launch::new);
        BY_RUNE.put("mark", Mark::new);
        BY_RUNE.put("unmark", Unmark::new);
        BY_RUNE.put("glow", Glow::new);
        BY_RUNE.put("execute", Execute::new);
        BY_RUNE.put("blink", Blink::new);
        BY_RUNE.put("echo", Echo::new);
        BY_RUNE.put("phantasm", Phantasm::new);
        BY_RUNE.put("copy", Copy::new);
        BY_RUNE.put("move", Move::new);
        BY_RUNE.put("anchor", Anchor::new);
        BY_RUNE.put("summon", Summon::new);
        BY_RUNE.put("gather", Gather::new);
        BY_RUNE.put("sic", Sic::new);
        BY_RUNE.put("stance", Stance::new);
        BY_RUNE.put("familiar", Familiar::new);
        BY_RUNE.put("master", Master::new);
        BY_RUNE.put("foe", Foe::new);
        BY_RUNE.put("dismiss", Dismiss::new);
        BY_RUNE.put("unanchor", Unanchor::new);
        BY_RUNE.put("halt", Halt::new);
        BY_RUNE.put("if", If::new);
        BY_RUNE.put("each", Each::new);
        BY_RUNE.put("repeat", Repeat::new);
        BY_RUNE.put("delay", Delay::new);
        BY_RUNE.put("recurse", Recurse::new);
        BY_RUNE.put("stop", Stop::new);
        BY_RUNE.put("+", Add::new);
        BY_RUNE.put("*", Mul::new);
        BY_RUNE.put("min", Min::new);
        BY_RUNE.put("max", Max::new);
        BY_RUNE.put("-", Sub::new);
        BY_RUNE.put("/", Div::new);
        BY_RUNE.put("hp", Hp::new);
        BY_RUNE.put("max-hp", MaxHp::new);
        BY_RUNE.put("hp-ratio", HpRatio::new);
        BY_RUNE.put("missing-hp", MissingHp::new);
        BY_RUNE.put("stacks", Stacks::new);
        BY_RUNE.put("distance", Distance::new);
        BY_RUNE.put("count", Count::new);
        BY_RUNE.put("mana-left", ManaLeft::new);
        BY_RUNE.put("depth", Depth::new);
        BY_RUNE.put("random", Random::new);
        BY_RUNE.put("mix", Mix::new);
        BY_RUNE.put("round", Round::new);
        BY_RUNE.put("clamp", Clamp::new);
        BY_RUNE.put("wave", Wave::new);
        BY_RUNE.put("mix-pos", MixPos::new);
        BY_RUNE.put("<", Lt::new);
        BY_RUNE.put(">", Gt::new);
        BY_RUNE.put("<=", Le::new);
        BY_RUNE.put(">=", Ge::new);
        BY_RUNE.put("=", Eq::new);
        BY_RUNE.put("and", And::new);
        BY_RUNE.put("or", Or::new);
        BY_RUNE.put("not", Not::new);
        BY_RUNE.put("has", Has::new);
        BY_RUNE.put("alive", Alive::new);
    }
}
