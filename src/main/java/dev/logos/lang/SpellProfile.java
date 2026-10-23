package dev.logos.lang;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.logos.runtime.Group;

/**
 * A spell's intent file: what it is for, how far it reaches, and roughly what it costs. Read off the
 * compiled tree without running it, so the editor can show it as the player builds and a lifeform
 * can choose its pages by it.
 * <p>
 * The estimate assumes things a static reading cannot know: that a group selector catches
 * {@value #ASSUMED_TARGETS} targets, that a projectile hits once, that recursion goes
 * {@value #ASSUMED_DEPTH} deep. {@link #assumptions} lists the ones this spell relied on.
 *
 * @param primary   the intent the lifeform files it under (declared, or the strongest inferred)
 * @param intents   every intent found in it, primary first
 * @param declared  true if the player wrote {@code :intent}
 * @param range     roughly how far away it can act, in blocks
 * @param estimate  roughly the mana one cast spends
 */
public record SpellProfile(Intent primary, List<Intent> intents, boolean declared, double range, double estimate, List<String> assumptions) {
    public static final int ASSUMED_TARGETS = 3, ASSUMED_BLOCKS = 5, ASSUMED_DEPTH = 3;
    /** When several intents are found, the one it is filed under: harm first, then help. */
    private static final List<Intent> PRIORITY = List.of(Intent.AREA, Intent.STRIKE, Intent.CONTROL, Intent.HEAL, Intent.WARD, Intent.MOVE, Intent.UTILITY);
    private static final Set<String> GROUP_SELECTORS = Set.of("enemies", "entities", "players", "items", "hit-list", "marked");
    private static final Set<String> FRIENDLY_EFFECTS = Set.of("regeneration", "speed", "strength", "resistance", "absorption", "fire_resistance",
            "instant_health", "night_vision", "water_breathing", "invisibility", "haste", "jump_boost", "slow_falling", "luck", "health_boost",
            "saturation", "conduit_power", "dolphins_grace", "hero_of_the_village");

    public String intentsZh() {
        StringBuilder sb = new StringBuilder();
        for (Intent i : intents) sb.append(sb.isEmpty() ? "" : "、").append(i.zh);
        return sb.toString();
    }

    /** Reads {@code body}; {@code declared} (from {@code :intent}, may be empty) overrides the inferred intents. */
    public static SpellProfile of(List<Node> body, List<Intent> declared) {
        Reader r = new Reader();
        double cost = 0;
        for (Node n : body) cost += r.cost(n, false);
        List<Intent> intents = new ArrayList<>();
        if (!declared.isEmpty()) {
            intents.addAll(declared);
        } else {
            for (Intent i : PRIORITY) if (r.found.contains(i)) intents.add(i);
            if (intents.isEmpty()) intents.add(Intent.UTILITY);
        }
        return new SpellProfile(intents.getFirst(), List.copyOf(intents), !declared.isEmpty(), r.range,
                Math.round(cost * 10) / 10.0, List.copyOf(r.assumptions));
    }

    /** One pass over the tree: intents found, reach, and cost (each node's own price times how often it runs). */
    private static final class Reader {
        final EnumSet<Intent> found = EnumSet.noneOf(Intent.class);
        final Set<String> assumptions = new LinkedHashSet<>();
        double range = 0;
        /** Whether the spell reaches more than one creature somewhere (a group selector, field, group effect). */
        boolean group;
        /** Inside a handler that runs again and again (on-tick): a group there usually catches far fewer each time. */
        int repeating;

        private static double lit(Node n, double fallback) {
            if (n != null && n.kind == Node.Kind.LIT && n.value instanceof Double d) return d;
            return fallback;
        }

        private double kwNum(Node n, String key, double fallback) {
            Node k = n.kw.get(key);
            if (k == null) return fallback;
            if (k.kind != Node.Kind.LIT) assumptions.add("算式算出的数值按默认值估算");
            return lit(k, fallback);
        }

        private double argNum(Node n, int i, double fallback) {
            Node a = n.arg(i);
            if (a != null && a.kind != Node.Kind.LIT) assumptions.add("算式算出的数值按默认值估算");
            return lit(a, fallback);
        }

        /** A group effect's price multiple, from the radius of the selector written into it; anything else at the reference radius. */
        private double groupMultiplier(Node targets) {
            if (targets != null && targets.kind == Node.Kind.CALL && targets.rune != null && targets.kw.containsKey("radius")) {
                return Group.costMultiplier(Group.clampRadius(kwNum(targets, "radius", Group.REFERENCE_RADIUS)));
            }
            assumptions.add("群体效果的群不是直接写的选择器时，按半径 " + (int) Group.REFERENCE_RADIUS + " 估算");
            return Group.costMultiplier(Group.REFERENCE_RADIUS);
        }

        /** How many things a target argument stands for. */
        private double count(Node target) {
            if (target == null) return 1;
            if (target.type == Type.BLOCKS) {
                assumptions.add("方块群按 " + ASSUMED_BLOCKS + " 个方块估算");
                return ASSUMED_BLOCKS;
            }
            if (target.type == Type.ENTITIES) {
                group = true;
                if (repeating > 0) {
                    assumptions.add("反复触发的事件里，群体选择器按每次命中 1 个目标估算");
                    return 1;
                }
                assumptions.add("群体选择器按命中 " + ASSUMED_TARGETS + " 个目标估算");
                return ASSUMED_TARGETS;
            }
            return 1;
        }

        private void reach(double r) {
            range = Math.max(range, Math.min(r, 32));
        }

        private boolean aimsAtOwnSide(Node target) {
            return target != null && target.kind == Node.Kind.CALL && target.rune != null && target.rune.name.equals("master")
                    || target != null && target.kind == Node.Kind.CTX && "caster".equals(target.value);
        }

        /** {@code n} and everything under it, once; {@code inGroup} if something above already spreads over many. */
        double cost(Node n, boolean inGroup) {
            if (n == null || n.kind != Node.Kind.CALL || n.rune == null) return 0;
            String r = n.rune.name;
            double own = 0;
            double children = 0;
            for (Node a : n.args) children += cost(a, inGroup);
            for (Node k : n.kw.values()) children += cost(k, inGroup);
            double body = 0;
            for (Node b : n.body) body += cost(b, inGroup);
            if (GROUP_SELECTORS.contains(r)) {
                group = true;
                reach(kwNum(n, "radius", 6));
            }
            switch (r) {
                case "projectile" -> {
                    own = 4 + (n.kw.get("motion") != null && n.kw.get("motion").value instanceof Motion m ? m.mana : 0);
                    double life = kwNum(n, "life", 3);
                    reach(kwNum(n, "speed", 1.2) * life * 20);
                    double pierce = kwNum(n, "pierce", 0);
                    // Piercing lets it hit more; reckoned as two hits at most.
                    own += Math.max(events(n, "on-hit", inGroup) * Math.min(pierce + 1, 2), events(n, "on-impact", inGroup));
                    own += events(n, "on-expire", inGroup) + repeated(n, "on-tick", inGroup) * life * 20;
                    if (n.events.containsKey("on-tick")) assumptions.add("on-tick 按寿命内的每一刻计算");
                    if (n.events.containsKey("on-hit")) assumptions.add("弹丸按命中一次估算");
                }
                case "beam" -> {
                    double radius = kwNum(n, "radius", 0.5), length = kwNum(n, "range", 24);
                    double pulses = Math.max(1, kwNum(n, "life", 2) / Math.max(0.05, kwNum(n, "every", 0.25)));
                    reach(length);
                    own = 6 + (0.3 + 0.05 * radius * length) * pulses;
                    own += (repeated(n, "on-hit", inGroup) * pulses) + events(n, "on-enter", inGroup) + events(n, "on-expire", inGroup);
                }
                case "construct" -> {
                    double measure = switch (n.kw.get("shape") != null ? String.valueOf(n.kw.get("shape").value).toLowerCase() : "wall") {
                        case "disk", "dome" -> 3 * Math.pow(kwNum(n, "radius", 3), 2);
                        default -> kwNum(n, "width", 5) * kwNum(n, "height", 3) * kwNum(n, "depth", 1);
                    };
                    double pulses = Math.max(1, kwNum(n, "life", 10) / Math.max(0.05, kwNum(n, "every", 1)));
                    own = 4 + 0.3 * measure + (0.2 + 0.02 * measure) * pulses;
                    found.add(Intent.WARD);
                    for (var e : n.events.entrySet()) own += e.getKey().equals("on-tick") ? repeated(n, "on-tick", inGroup) * pulses : events(n, e.getKey(), inGroup);
                }
                case "field" -> {
                    group = true;
                    double pulses = Math.max(1, kwNum(n, "life", 4) / Math.max(0.05, kwNum(n, "every", 0.5)));
                    reach(kwNum(n, "radius", 3));
                    own = 6 + 0.5 * pulses + repeated(n, "on-tick", true) * pulses + events(n, "on-enter", true) * ASSUMED_TARGETS + events(n, "on-expire", true);
                    if (n.events.containsKey("on-enter")) assumptions.add("领域按进入 " + ASSUMED_TARGETS + " 个目标估算");
                }
                case "ray" -> {
                    reach(kwNum(n, "range", 16));
                    own = 3 + events(n, "on-hit", inGroup) * Math.min(kwNum(n, "pierce", 0) + 1, 2);
                }
                case "nearest", "nearest-player" -> {
                    own = 1;
                    reach(kwNum(n, "radius", 10));
                }
                case "enemies", "entities", "blocks" -> own = 2;
                case "players", "items", "hit-list", "marked", "look-point", "look-block" -> own = 1;
                case "in-dimension" -> own = 2;
                case "foe" -> reach(16);
                case "damage", "drain" -> {
                    double amt = argNum(n, 1, 5);
                    double factor = r.equals("drain") ? 1.5 : n.arg(2) != null && n.arg(2).value instanceof Element el ? el.costFactor : Element.PHYSICAL.costFactor;
                    own = amt * factor * count(n.arg(0)) + events(n, "on-kill", inGroup);
                    found.add(group || inGroup || n.arg(0) != null && n.arg(0).type == Type.ENTITIES ? Intent.AREA : Intent.STRIKE);
                }
                case "heal" -> {
                    own = argNum(n, 1, 4) * 1.5 * count(n.arg(0));
                    found.add(Intent.HEAL);
                }
                case "shield" -> {
                    own = argNum(n, 1, 4) * 1.5 * count(n.arg(0));
                    found.add(Intent.WARD);
                }
                case "infuse", "infuse-block" -> {
                    own = argNum(n, 1, 10) * dev.logos.runtime.ManaInfusion.COST * count(n.arg(0));
                    assumptions.add("渡魔、灌魔按目标装得下全部魔力估算；装不下的部分不花魔力");
                    found.add(Intent.UTILITY);
                }
                case "ignite" -> {
                    own = argNum(n, 1, 3) * 0.6 * count(n.arg(0));
                    found.add(group || inGroup ? Intent.AREA : Intent.STRIKE);
                }
                case "freeze" -> {
                    own = argNum(n, 1, 3) * 1.2 * count(n.arg(0));
                    found.add(Intent.CONTROL);
                }
                case "time-stop" -> {
                    own = Math.min(argNum(n, 1, 3), 10) * 4 * count(n.arg(0));
                    found.add(Intent.CONTROL);
                }
                case "damage-all" -> {
                    double factor = n.arg(2) != null && n.arg(2).value instanceof Element el ? el.costFactor : Element.PHYSICAL.costFactor;
                    own = argNum(n, 1, 5) * factor * groupMultiplier(n.arg(0)) + events(n, "on-kill", true);
                    found.add(Intent.AREA);
                }
                case "ignite-all" -> {
                    own = argNum(n, 1, 3) * 0.6 * groupMultiplier(n.arg(0));
                    found.add(Intent.AREA);
                }
                case "freeze-all" -> {
                    own = argNum(n, 1, 3) * 1.2 * groupMultiplier(n.arg(0));
                    found.add(Intent.CONTROL);
                }
                case "disarm" -> {
                    own = 8;
                    found.add(Intent.CONTROL);
                }
                case "equip" -> {
                    own = 8 * count(n.arg(0));
                    found.add(Intent.UTILITY);
                }
                case "potion" -> {
                    String id = String.valueOf(n.arg(1) == null ? "" : n.arg(1).value).replace("minecraft:", "");
                    double level = argNum(n, 3, 1);
                    own = (id.startsWith("instant") ? 6 * level : argNum(n, 2, 10) * level * 0.8) * count(n.arg(0));
                    found.add(FRIENDLY_EFFECTS.contains(id) ? (id.equals("instant_health") || id.equals("regeneration") ? Intent.HEAL : Intent.WARD) : Intent.CONTROL);
                }
                case "launch" -> {
                    own = argNum(n, 1, 1) * 1.5 * count(n.arg(0));
                    found.add(aimsAtOwnSide(n.arg(0)) ? Intent.MOVE : Intent.CONTROL);
                }
                case "push", "pull" -> {
                    own = argNum(n, 1, 1) * 1.5 * count(n.arg(0));
                    found.add(Intent.CONTROL);
                }
                case "mark" -> own = argNum(n, 2, 1) * count(n.arg(0));
                case "unmark" -> own = 0.5 * count(n.arg(0));
                case "glow" -> own = argNum(n, 1, 5) * 0.2 * count(n.arg(0));
                case "execute" -> {
                    own = 4 * 10 + events(n, "on-kill", inGroup);
                    assumptions.add("处决的代价由证明决定，按 10 点生命、没有折减估算");
                    found.add(Intent.STRIKE);
                }
                case "blink" -> {
                    own = (2 + 0.5 * 10) * count(n.arg(1));
                    assumptions.add("瞬移按 10 格估算");
                    found.add(Intent.MOVE);
                }
                case "summon" -> {
                    if (Compiler.MANA_LIFEFORM.equals(n.arg(0) == null ? null : n.arg(0).value)) {
                        Node m = n.kw.get("mana");
                        own = m != null ? lit(m, 50) : ManaLifeformCosts.min((int) lit(n.kw.get("level"), 1));
                        if (m == null) assumptions.add("魔力生命体默认带走术式剩下的全部魔力，这里只算最低需求");
                    } else {
                        own = 40;
                        assumptions.add("召唤的代价是生物最大生命 ×2，按 20 点生命估算");
                    }
                    found.add(Intent.UTILITY);
                }
                case "gather", "sic", "move" -> own = 1;
                case "stance" -> own = 2;
                case "copy", "recurse" -> own = 3;
                case "toggle-state" -> own = 5;
                case "time-skip" -> own = Math.min(argNum(n, 0, 60), 36000) * 4;
                case "world-weather" -> own = n.arg(0) != null && n.arg(0).value == Weather.THUNDER ? 200 : 100;
                case "weather" -> {
                    double radius = Math.clamp(kwNum(n, "radius", 8), 1, 48);
                    reach(radius);
                    own = 10 + 0.02 * radius * radius * (n.arg(0) != null && n.arg(0).value == Weather.THUNDER ? 2 : 1) * Math.clamp(kwNum(n, "life", 30), 1, 600);
                }
                case "grow", "decay" -> {
                    if (n.arg(0) != null && n.arg(0).type == Type.BLOCKS) own = 2 * count(n.arg(0));
                    else {
                        double r2 = 2 * argNum(n, 1, 1) + 1;
                        own = 2 * r2 * r2;
                        assumptions.add("生长/腐败按范围内每格地面都有一个对象估算");
                    }
                }
                case "lightning" -> {
                    own = 50 * count(n.arg(0));
                    assumptions.add("落雷按晴天每道 50 估算（雨中 40、雷暴中 20）");
                    found.add(group || inGroup || n.arg(0) != null && n.arg(0).type == Type.BLOCKS ? Intent.AREA : Intent.STRIKE);
                }
                case "set-fire" -> own = 2 * count(n.arg(0));
                case "break-block" -> own = 4 * count(n.arg(0));
                case "if", "switch", "each-block", "delay" -> own = 0.5;
                default -> own = 0;
            }
            switch (r) {
                case "each" -> {
                    double times = count(n.arg(0));
                    return children + 0.5 + body * times;
                }
                case "split" -> {
                    double copies = argNum(n, 0, 3);
                    return children + (2 + body) * copies;
                }
                case "repeat" -> {
                    double times = argNum(n, 0, 3);
                    return children + (1 + body) * times;
                }
                case "each-block" -> {
                    return children + own + body * count(n.arg(0));
                }
                case "recurse" -> {
                    assumptions.add("递归按 " + ASSUMED_DEPTH + " 层估算");
                    return (children + own + body) * ASSUMED_DEPTH;
                }
                default -> {
                    // Events not priced above (on-kill of damage etc. are included there) still run at most once.
                    if (!priced(r)) for (String e : n.events.keySet()) own += events(n, e, inGroup);
                    return own + children + body;
                }
            }
        }

        private static boolean priced(String rune) {
            return switch (rune) {
                case "projectile", "beam", "construct", "field", "ray", "damage", "drain", "damage-all", "execute" -> true;
                default -> false;
            };
        }

        private double repeated(Node n, String event, boolean inGroup) {
            repeating++;
            try {
                return events(n, event, inGroup);
            } finally {
                repeating--;
            }
        }

        private double events(Node n, String event, boolean inGroup) {
            List<Node> handlers = n.events.get(event);
            if (handlers == null) return 0;
            double sum = 0;
            for (Node h : handlers) sum += cost(h, inGroup);
            return sum;
        }
    }

    /** The lifeform's level rules without a dependency on the runtime (the language is compiled in tests too). */
    private static final class ManaLifeformCosts {
        static double min(int level) {
            return 50 * Math.pow(Math.max(1, Math.min(5, level)), 1.5);
        }
    }
}
