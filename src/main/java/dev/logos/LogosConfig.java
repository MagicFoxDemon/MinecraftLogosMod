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

    static {
        BUILDER.pop();
        BUILDER.comment("Mana balance").push("balance");
    }

    public static final ModConfigSpec.DoubleValue GROUP_COST_MULTIPLIER = BUILDER
            .comment("Group effects (damage-all, ignite-all, freeze-all) pay this many times the single-target price, once,",
                    "however many targets the group holds.")
            .translation("logos.configuration.groupCostMultiplier")
            .defineInRange("groupCostMultiplier", 3.0, 0.0, 1000.0);

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

    /** Falls back to the default before the config file is loaded (e.g. in unit tests). */
    private static int read(IntSupplier value, int fallback) {
        try {
            return value.getAsInt();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
