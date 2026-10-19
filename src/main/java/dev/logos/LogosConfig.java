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

    public static boolean travelUnvisitedDimensions() {
        try {
            return TRAVEL_UNVISITED_DIMENSIONS.getAsBoolean();
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

    /** Falls back to the default before the config file is loaded (e.g. in unit tests). */
    private static int read(IntSupplier value, int fallback) {
        try {
            return value.getAsInt();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
