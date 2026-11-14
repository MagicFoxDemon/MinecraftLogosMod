package dev.logos;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import dev.logos.item.PageTier;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/logos-common.toml}. Common (not server) so the client's graph editor checks spells
 * against the same limits the server enforces when both run from one installation.
 */
public final class LogosConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Spell language limits (Logos Stability)").push("limits");
    }

    public static final ModConfigSpec.IntValue MAX_INJECTED_MANA = BUILDER
            .comment("Largest :mana a single spell may inject (its shared mana pool).",
                    "Default is Minecraft's largest integer, 2147483647.")
            .translation("logos.configuration.maxInjectedMana")
            .defineInRange("maxInjectedMana", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_RECURSION = BUILDER
            .comment("How many levels deep (recurse) may go before the spell destabilises.",
                    "Very deep recursion can also hit maxOpsPerTick or the Java stack; both end the spell safely.")
            .translation("logos.configuration.maxRecursion")
            .defineInRange("maxRecursion", 12, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_REPEAT = BUILDER
            .comment("Most iterations a single (repeat n …) may run.")
            .translation("logos.configuration.maxRepeat")
            .defineInRange("maxRepeat", 32, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_OPS_PER_TICK = BUILDER
            .comment("Interpreter steps one spell may take per tick before it destabilises.",
                    "Raise this together with maxRecursion / maxRepeat if large loops get cut off.")
            .translation("logos.configuration.maxOpsPerTick")
            .defineInRange("maxOpsPerTick", 800, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_OPS_PER_TICK_GLOBAL = BUILDER
            .comment("Interpreter steps all spells together may take per tick. Past this, spell bodies and delayed tasks wait for the next tick, taking turns, instead of going out.",
                    "Spell code set off by events (casts, melee hits, constructs) cannot wait, but its steps count. A step is not a fixed cost: lower this if running spells lag the server.")
            .translation("logos.configuration.maxOpsPerTickGlobal")
            .defineInRange("maxOpsPerTickGlobal", 40000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MAX_FAMILIARS = BUILDER
            .comment("How many summoned familiars one player may have at once.")
            .translation("logos.configuration.maxFamiliars")
            .defineInRange("maxFamiliars", 4, 0, 256);

    public static final ModConfigSpec.IntValue MAX_LIFEFORMS = BUILDER
            .comment("How many mana lifeforms one player may have at once (kept in the world, so they cost the server more than familiars).",
                    "Summoning one past the limit unmakes the oldest.")
            .translation("logos.configuration.maxLifeforms")
            .defineInRange("maxLifeforms", 1, 0, 64);

    public static final ModConfigSpec.IntValue MAX_COLOSSI = BUILDER
            .comment("How many alchemical colossi one player may have at once (kept in the world). Summoning one past the limit unmakes the oldest.")
            .translation("logos.configuration.maxColossi")
            .defineInRange("maxColossi", 1, 0, 16);

    public static final ModConfigSpec.IntValue MAX_ANCHORS = BUILDER
            .comment("How many named anchors (^name) one player may keep.")
            .translation("logos.configuration.maxAnchors")
            .defineInRange("maxAnchors", 16, 0, 1024);

    public static final ModConfigSpec.BooleanValue TRAVEL_UNVISITED_DIMENSIONS = BUILDER
            .comment("Whether (in-dimension …) may name a dimension the caster has never been to.",
                    "When false, a player must first travel there in person; visits are recorded either way.")
            .translation("logos.configuration.travelUnvisitedDimensions")
            .define("travelUnvisitedDimensions", false);

    public static final ModConfigSpec.BooleanValue SPELLS_LOAD_CHUNKS = BUILDER
            .comment("Whether spells may load chunks that nothing has loaded yet. Loading happens on the server thread, and a chunk nobody has visited is generated there, so on a busy server this is lag.",
                    "When false, a projectile, beam or vein ends just before it would enter an unloaded chunk, and selectors and block runes find nothing there and charge nothing for it.",
                    "When true, a projectile may fly its whole life (up to 1600 blocks) and spells may reach anywhere, loading what they pass. Blink always loads where it lands, either way.")
            .translation("logos.configuration.spellsLoadChunks")
            .define("spellsLoadChunks", false);

    static {
        BUILDER.pop();
        BUILDER.comment("Mana balance").push("balance");
    }

    public static final ModConfigSpec.DoubleValue GROUP_COST_MULTIPLIER = BUILDER
            .comment("Group effects (damage-all, ignite-all, freeze-all) pay this many times the single-target price, once,",
                    "however many targets the group holds.")
            .translation("logos.configuration.groupCostMultiplier")
            .defineInRange("groupCostMultiplier", 3.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue GROUP_RADIUS_EXPONENT = BUILDER
            .comment("Beyond radius 5, a group effect's multiplier grows by (radius / 5) to this power, the radius being",
                    "that of the selector which gathered the group: 1.5 makes radius 10 cost 2.8 times radius 5, radius 64 about 46 times.")
            .translation("logos.configuration.groupRadiusExponent")
            .defineInRange("groupRadiusExponent", 1.5, 0.0, 4.0);

    public static final ModConfigSpec.IntValue MAX_GROUP_RADIUS = BUILDER
            .comment("The largest :radius a group selector (enemies, entities, players, items, marked) searches.")
            .translation("logos.configuration.maxGroupRadius")
            .defineInRange("maxGroupRadius", 64, 1, 256);

    public static final ModConfigSpec.IntValue CHARGE_MIN_TICKS = BUILDER
            .comment("The shortest a wand cast may charge, in ticks, however little it injects.")
            .translation("logos.configuration.chargeMinTicks")
            .defineInRange("chargeMinTicks", 5, 0, 200);

    public static final ModConfigSpec.DoubleValue CHARGE_MANA_SCALE = BUILDER
            .comment("A wand's channel surge grows by 1 + max mana / this: a deeper pool gathers faster.")
            .translation("logos.configuration.chargeManaScale")
            .defineInRange("chargeManaScale", 1000.0, 1.0, 1e9);

    public static final ModConfigSpec.DoubleValue MAID_MANA_PER_FAVOR_LEVEL = BUILDER
            .comment("With Touhou Little Maid: a maid's mana cap is her owner's, plus this share of it for each favorability level (0 to 3).")
            .translation("logos.configuration.maidManaPerFavorLevel")
            .defineInRange("maidManaPerFavorLevel", 0.1, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue TIME_SKIP_MAX_RATE = BUILDER
            .comment("The fastest time-skip pushes a clock: this many ticks of day per game tick (720 passes an in-game day in under 2 seconds).")
            .translation("logos.configuration.timeSkipMaxRate")
            .defineInRange("timeSkipMaxRate", 720.0, 1.0, 24000.0);

    public static final ModConfigSpec.DoubleValue TIME_SKIP_MAX_RANDOM_TICKS = BUILDER
            .comment("The most a time-skip multiplies random ticks (crops, saplings, ice) in its dimension. They cost the server",
                    "in proportion, in every loaded chunk there, so this stays far below timeSkipMaxRate.")
            .translation("logos.configuration.timeSkipMaxRandomTicks")
            .defineInRange("timeSkipMaxRandomTicks", 300.0, 1.0, 1000.0);

    public static final ModConfigSpec.DoubleValue MANA_SPREAD_THRESHOLD = BUILDER
            .comment("Spread mana flows to a neighbouring chunk only while it holds this much more (out of 200 a chunk at most).",
                    "With 70, one full chunk settles into 200, 130 next to it and 60 two chunks off.")
            .translation("logos.configuration.manaSpreadThreshold")
            .defineInRange("manaSpreadThreshold", 70.0, 0.0, 200.0);

    public static final ModConfigSpec.DoubleValue CAPUT_MORTUUM_INSULATION = BUILDER
            .comment("How much each block of caput mortuum adds to that threshold, between its chunk and each neighbour.",
                    "Blocks in both chunks count; past 3 in one chunk, more do nothing. With 70, two blocks shut a full chunk off.")
            .translation("logos.configuration.caputMortuumInsulation")
            .defineInRange("caputMortuumInsulation", 70.0, 0.0, 200.0);

    /** Formula Immolation I, II, III: how many times a page's capacity a burning cast may inject. */
    public static final List<ModConfigSpec.DoubleValue> IMMOLATION_MULTIPLIERS = List.of(
            immolation(1, 2), immolation(2, 3), immolation(3, 5));

    private static ModConfigSpec.DoubleValue immolation(int level, double def) {
        return BUILDER.comment("Formula Immolation " + level + ": a cast that burns its page may inject this many times the page's capacity.")
                .translation("logos.configuration.immolation" + level)
                .defineInRange("immolation" + level, def, 1.0, 1000.0);
    }

    static {
        BUILDER.pop();
        BUILDER.comment("Mana growth: what a player's mana cap gains besides its permanent base. Each term is weight × a curve:",
                "experience and practice grow by log2(1 + amount / scale); health above 20, armor and toughness by value / scale",
                "up to 1 and 1 + ln(value / scale) past it, so a modpack's large numbers still help without running away.").push("growth");
    }

    public static final ModConfigSpec.DoubleValue GROWTH_XP_WEIGHT = growthValue("growthXpWeight", 150,
            "Cap from experience ever gained (spending and dying never lower it): weight × log2(1 + points / growthXpScale).");
    public static final ModConfigSpec.DoubleValue GROWTH_XP_SCALE = growthValue("growthXpScale", 1395,
            "Experience points for the first full weight (1395 is what level 30 takes).");
    public static final ModConfigSpec.DoubleValue GROWTH_HEALTH_WEIGHT = growthValue("growthHealthWeight", 100,
            "Cap from max health above 20.");
    public static final ModConfigSpec.DoubleValue GROWTH_HEALTH_SCALE = growthValue("growthHealthScale", 20,
            "Max health above 20 for the full weight; past it the gain slows to a logarithm.");
    public static final ModConfigSpec.DoubleValue GROWTH_ARMOR_WEIGHT = growthValue("growthArmorWeight", 80,
            "Cap from armor: stronger armor lets mana flow more freely.");
    public static final ModConfigSpec.DoubleValue GROWTH_ARMOR_SCALE = growthValue("growthArmorScale", 20,
            "Armor for the full weight (20 is a full set of diamond); past it the gain slows to a logarithm.");
    public static final ModConfigSpec.DoubleValue GROWTH_TOUGHNESS_WEIGHT = growthValue("growthToughnessWeight", 40,
            "Cap from armor toughness.");
    public static final ModConfigSpec.DoubleValue GROWTH_TOUGHNESS_SCALE = growthValue("growthToughnessScale", 12,
            "Armor toughness for the full weight (12 is a full set of netherite); past it the gain slows to a logarithm.");
    public static final ModConfigSpec.DoubleValue GROWTH_PRACTICE_WEIGHT = growthValue("growthPracticeWeight", 100,
            "Cap from practice, the mana ever drawn from the pool: weight × log2(1 + mana / growthPracticeScale).");
    public static final ModConfigSpec.DoubleValue GROWTH_PRACTICE_SCALE = growthValue("growthPracticeScale", 20000,
            "Mana drawn for the first full weight of practice.");
    public static final ModConfigSpec.DoubleValue OVERFLOW_DRAIN = BUILDER
            .comment("When the cap falls below the pool (armor taken off, a wand put away), this share of the surplus drains",
                    "a second, at least 1 mana a second, instead of the surplus vanishing at once.")
            .translation("logos.configuration.overflowDrain")
            .defineInRange("overflowDrain", 0.05, 0.0, 1.0);

    private static ModConfigSpec.DoubleValue growthValue(String name, double def, String comment) {
        return BUILDER.comment(comment).translation("logos.configuration." + name).defineInRange(name, def, 0.0, 1e9);
    }

    static {
        BUILDER.pop();
        BUILDER.comment("Spell pages: the most mana a cast may inject from each grade, and the share of entropic mana",
                "(left when a spell is extinguished rather than ending on its own) that returns as free mana.").push("pages");
    }

    private static final Map<PageTier, ModConfigSpec.IntValue> PAGE_CAPACITY = new EnumMap<>(PageTier.class);
    private static final Map<PageTier, ModConfigSpec.DoubleValue> PAGE_RECOVERY = new EnumMap<>(PageTier.class);

    static {
        for (PageTier t : PageTier.values()) {
            BUILDER.push(t.id);
            PAGE_CAPACITY.put(t, BUILDER.translation("logos.configuration.pageCapacity")
                    .defineInRange("capacity", t.defaultCapacity, 1, Integer.MAX_VALUE));
            PAGE_RECOVERY.put(t, BUILDER.translation("logos.configuration.pageRecovery")
                    .defineInRange("entropyRecovery", t.defaultRecovery, 0.0, 1.0));
            BUILDER.pop();
        }
        BUILDER.pop();
        BUILDER.comment("Incarnation (incarnate): what a player turned into a creature can do besides walking and biting").push("incarnation");
    }

    public static final ModConfigSpec.ConfigValue<List<? extends String>> INCARNATION_HANDS = BUILDER
            .comment("Creatures with hands: as one of these, a player still holds, uses and places items, mines and casts.",
                    "Any other form strikes with its own body and cannot use its inventory. Entity ids, such as minecraft:zombie or somemod:goblin.")
            .translation("logos.configuration.incarnationHands")
            .defineListAllowEmpty("incarnationHands", List.of(
                    "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:zombified_piglin",
                    "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:parched", "minecraft:wither_skeleton",
                    "minecraft:piglin", "minecraft:piglin_brute", "minecraft:vindicator", "minecraft:pillager", "minecraft:evoker",
                    "minecraft:illusioner", "minecraft:witch", "minecraft:vex", "minecraft:allay"),
                    () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> INCARNATION_FLIERS = BUILDER
            .comment("Creatures that fly though they show no sign of it (flying pathfinding, no gravity): as one of these, a player can fly.",
                    "Creatures that do show it fly anyway. Entity ids.")
            .translation("logos.configuration.incarnationFliers")
            .defineListAllowEmpty("incarnationFliers", List.of(
                    "minecraft:bat", "minecraft:ghast", "minecraft:happy_ghast", "minecraft:phantom", "minecraft:blaze"),
                    () -> "", o -> o instanceof String);

    static {
        BUILDER.pop();
        BUILDER.comment("Void damage: true damage, close to falling out of the world").push("voidDamage");
    }

    public static final ModConfigSpec.BooleanValue VOID_IGNORES_ABSORPTION = BUILDER
            .comment("Whether void damage goes straight to health past absorption (spell shields, golden apples), leaving the absorption intact.")
            .translation("logos.configuration.voidIgnoresAbsorption")
            .define("voidIgnoresAbsorption", true);

    public static final ModConfigSpec.BooleanValue VOID_IGNORES_TOTEM = BUILDER
            .comment("Whether void damage kills through a totem of undying.")
            .translation("logos.configuration.voidIgnoresTotem")
            .define("voidIgnoresTotem", false);

    public static final ModConfigSpec.BooleanValue VOID_IS_OUT_OF_WORLD = BUILDER
            .comment("The real thing: void damage becomes vanilla's out-of-world damage with no attacker, and execute does what /kill does (a colossus dies too).",
                    "Nothing stops it: creative players, invulnerable entities and totems included (voidIgnoresTotem no longer matters). Kills earn no experience or credit.")
            .translation("logos.configuration.voidIsOutOfWorld")
            .define("voidIsOutOfWorld", false);

    static {
        BUILDER.pop();
        BUILDER.comment("Spell effects: how often the server updates them, and how many a client plays").push("fx");
    }

    public static final ModConfigSpec.IntValue FX_NEAR_DISTANCE = BUILDER
            .comment("Server: a player within this many blocks of a lasting effect (projectile, beam, field, vein) gets every update;",
                    "out to twice this distance half of them, further off a quarter. What starts or ends an effect always arrives. 96 sends every update as far as effects reach.")
            .translation("logos.configuration.fxNearDistance")
            .defineInRange("fxNearDistance", 32, 0, 96);

    public static final ModConfigSpec.IntValue FX_EFFECTS_PER_TICK = BUILDER
            .comment("Client: most burst-like effects started in one tick; the rest are skipped.")
            .translation("logos.configuration.fxEffectsPerTick")
            .defineInRange("fxEffectsPerTick", 64, 0, 4096);

    public static final ModConfigSpec.IntValue FX_PARTICLES_PER_TICK = BUILDER
            .comment("Client: most particles spell effects add in one tick; the rest are skipped.")
            .translation("logos.configuration.fxParticlesPerTick")
            .defineInRange("fxParticlesPerTick", 512, 0, 16384);

    public static final ModConfigSpec.IntValue FX_MAX_TRAILS = BUILDER
            .comment("Client: most projectiles drawn at once; a new one waits for room.")
            .translation("logos.configuration.fxMaxTrails")
            .defineInRange("fxMaxTrails", 256, 0, 4096);

    public static final ModConfigSpec.IntValue FX_MAX_BEAMS = BUILDER
            .comment("Client: most lasting beams drawn at once; a new one waits for room.")
            .translation("logos.configuration.fxMaxBeams")
            .defineInRange("fxMaxBeams", 64, 0, 4096);

    public static final ModConfigSpec.IntValue FX_MAX_RAYS = BUILDER
            .comment("Client: most rays and beam wakes showing at once; a new one replaces the oldest.")
            .translation("logos.configuration.fxMaxRays")
            .defineInRange("fxMaxRays", 128, 0, 4096);

    public static final ModConfigSpec.IntValue FX_MAX_FIELDS = BUILDER
            .comment("Client: most fields drawn at once; a new one waits for room.")
            .translation("logos.configuration.fxMaxFields")
            .defineInRange("fxMaxFields", 64, 0, 4096);

    public static final ModConfigSpec.IntValue FX_MAX_VEIN_HEADS = BUILDER
            .comment("Client: most vein heads drawn at once (a ring has up to 32); a new one waits for room.")
            .translation("logos.configuration.fxMaxVeinHeads")
            .defineInRange("fxMaxVeinHeads", 512, 0, 16384);

    static {
        BUILDER.pop();
        BUILDER.comment("Rune unlocks: which words of the language a player must learn before writing them").push("unlocks");
    }

    public static final ModConfigSpec.BooleanValue RUNE_UNLOCKS = BUILDER
            .comment("Server: whether players write only the runes, elements and motions they have learned (a fresh player knows projectile, damage, physical and forward).",
                    "Creative players write anything either way, and pages found or given always cast and copy. When false, every player writes everything.")
            .translation("logos.configuration.runeUnlocks")
            .define("runeUnlocks", true);

    static {
        BUILDER.pop();
        BUILDER.comment("逻各斯手记, the guide book").push("guide");
    }

    public static final ModConfigSpec.BooleanValue GIVE_GUIDE_ON_FIRST_JOIN = BUILDER
            .comment("Server: whether a player is handed 逻各斯手记 (the guide book) the first time they join; each player is handed it once.",
                    "Players who joined while this was off are handed theirs once it is turned on.")
            .translation("logos.configuration.giveGuideOnFirstJoin")
            .define("giveGuideOnFirstJoin", true);

    static {
        BUILDER.pop();
        BUILDER.comment("Debug: output for whoever builds or tests spells, all off for players").push("debug");
    }

    public static final ModConfigSpec.BooleanValue SPELL_TRACE = BUILDER
            .comment("Server: after every spell, tell its caster in chat how it ended, the mana it took in and left behind, and how the mana ran down.",
                    "/logos trace on turns the same report on for one player.")
            .translation("logos.configuration.spellTrace")
            .define("spellTrace", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private LogosConfig() {}

    public static int maxInjectedMana() {
        return read(MAX_INJECTED_MANA::getAsInt, Integer.MAX_VALUE);
    }

    public static int maxRecursion() {
        return read(MAX_RECURSION::getAsInt, 12);
    }

    public static int maxRepeat() {
        return read(MAX_REPEAT::getAsInt, 32);
    }

    public static int maxOpsPerTick() {
        return read(MAX_OPS_PER_TICK::getAsInt, 800);
    }

    public static int maxOpsPerTickGlobal() {
        return read(MAX_OPS_PER_TICK_GLOBAL::getAsInt, 40000);
    }

    public static int maxFamiliars() {
        return read(MAX_FAMILIARS::getAsInt, 4);
    }

    public static int maxLifeforms() {
        return read(MAX_LIFEFORMS::getAsInt, 1);
    }

    public static int maxColossi() {
        return read(MAX_COLOSSI::getAsInt, 1);
    }

    public static int maxAnchors() {
        return read(MAX_ANCHORS::getAsInt, 16);
    }

    /** Whether a creature of {@code id} has hands as an incarnation form ({@link #INCARNATION_HANDS}). */
    public static boolean incarnationHands(String id) {
        return listed(INCARNATION_HANDS, id);
    }

    /** Whether a creature of {@code id} flies as an incarnation form though nothing about it says so ({@link #INCARNATION_FLIERS}). */
    public static boolean incarnationFlier(String id) {
        return listed(INCARNATION_FLIERS, id);
    }

    private static boolean listed(ModConfigSpec.ConfigValue<List<? extends String>> list, String id) {
        try {
            return list.get().contains(id);
        } catch (IllegalStateException e) {
            return list.getDefault().contains(id);
        }
    }

    /** Whether every caster gets the end-of-spell report (see {@link dev.logos.runtime.SpellInstance}); off unless a server turns it on. */
    public static boolean spellTrace() {
        try {
            return SPELL_TRACE.getAsBoolean();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean runeUnlocks() {
        try {
            return RUNE_UNLOCKS.getAsBoolean();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static boolean giveGuideOnFirstJoin() {
        try {
            return GIVE_GUIDE_ON_FIRST_JOIN.getAsBoolean();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static boolean travelUnvisitedDimensions() {
        try {
            return TRAVEL_UNVISITED_DIMENSIONS.getAsBoolean();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean voidIgnoresAbsorption() {
        try {
            return VOID_IGNORES_ABSORPTION.getAsBoolean();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static boolean voidIgnoresTotem() {
        try {
            return VOID_IGNORES_TOTEM.getAsBoolean();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean spellsLoadChunks() {
        try {
            return SPELLS_LOAD_CHUNKS.getAsBoolean();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean voidIsOutOfWorld() {
        try {
            return VOID_IS_OUT_OF_WORLD.getAsBoolean();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static int pageCapacity(PageTier t) {
        return read(PAGE_CAPACITY.get(t)::getAsInt, t.defaultCapacity);
    }

    public static double pageRecovery(PageTier t) {
        try {
            return PAGE_RECOVERY.get(t).getAsDouble();
        } catch (IllegalStateException e) {
            return t.defaultRecovery;
        }
    }

    /** Capacity multiplier for Formula Immolation at {@code level} (1~3; higher levels use III's). */
    public static double immolationMultiplier(int level) {
        int i = Math.clamp(level, 1, IMMOLATION_MULTIPLIERS.size()) - 1;
        try {
            return IMMOLATION_MULTIPLIERS.get(i).getAsDouble();
        } catch (IllegalStateException e) {
            return new double[] {2, 3, 5}[i];
        }
    }

    public static double groupCostMultiplier() {
        try {
            return GROUP_COST_MULTIPLIER.getAsDouble();
        } catch (IllegalStateException e) {
            return 3.0;
        }
    }

    public static double groupRadiusExponent() {
        try {
            return GROUP_RADIUS_EXPONENT.getAsDouble();
        } catch (IllegalStateException e) {
            return 1.5;
        }
    }

    public static int maxGroupRadius() {
        return read(MAX_GROUP_RADIUS::getAsInt, 64);
    }

    public static int chargeMinTicks() {
        return read(CHARGE_MIN_TICKS::getAsInt, 5);
    }

    public static double manaSpreadThreshold() {
        try {
            return MANA_SPREAD_THRESHOLD.getAsDouble();
        } catch (IllegalStateException e) {
            return 70.0;
        }
    }

    public static double caputMortuumInsulation() {
        try {
            return CAPUT_MORTUUM_INSULATION.getAsDouble();
        } catch (IllegalStateException e) {
            return 70.0;
        }
    }

    public static double chargeManaScale() {
        try {
            return CHARGE_MANA_SCALE.getAsDouble();
        } catch (IllegalStateException e) {
            return 1000.0;
        }
    }

    public static double maidManaPerFavorLevel() {
        try {
            return MAID_MANA_PER_FAVOR_LEVEL.getAsDouble();
        } catch (IllegalStateException e) {
            return 0.1;
        }
    }

    public static double timeSkipMaxRate() {
        try {
            return TIME_SKIP_MAX_RATE.getAsDouble();
        } catch (IllegalStateException e) {
            return 720.0;
        }
    }

    public static double timeSkipMaxRandomTicks() {
        try {
            return TIME_SKIP_MAX_RANDOM_TICKS.getAsDouble();
        } catch (IllegalStateException e) {
            return 300.0;
        }
    }

    /** A growth value ({@link #GROWTH_XP_WEIGHT} and the rest), or its default before the config file is loaded. */
    public static double growth(ModConfigSpec.DoubleValue value) {
        try {
            return value.getAsDouble();
        } catch (IllegalStateException e) {
            return value.getDefault();
        }
    }

    /** Falls back to the default before the config file is loaded (e.g. in unit tests). */
    private static int read(IntSupplier value, int fallback) {
        try {
            return value.getAsInt();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
