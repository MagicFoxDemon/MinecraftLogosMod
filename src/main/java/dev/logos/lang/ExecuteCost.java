package dev.logos.lang;

import java.util.List;

import dev.logos.runtime.StatusData;
import net.minecraft.world.entity.LivingEntity;

/**
 * Lex Proof: execute rewrites "this creature is alive" to "it is not". The less of the world's
 * state the proof leaves undetermined, the cheaper the rewrite. Conditions don't grant discounts;
 * they bound the work.
 */
public final class ExecuteCost {
    public static final double PER_HP = 4;
    public static final double PER_STATUS = 0.75;
    public static final double PER_MARK = 0.9;
    public static final double MARK_FLOOR = 0.4;

    private ExecuteCost() {}

    public static double cost(LivingEntity e, List<Fact> proof) {
        double max = e.getMaxHealth();
        double bound = max;
        int statuses = 0;
        double marks = 0;
        for (Fact f : proof) {
            switch (f.kind()) {
                case HP_RATIO_MAX -> bound = Math.min(bound, max * Math.max(0, f.value()));
                case HP_MAX -> bound = Math.min(bound, Math.max(0, f.value()));
                case HAS -> statuses++;
                case STACKS_MIN -> marks += Math.max(0, f.value());
            }
        }
        return PER_HP * bound * Math.pow(PER_STATUS, statuses) * Math.max(MARK_FLOOR, Math.pow(PER_MARK, marks));
    }

    /** Checks that every premise still holds at the moment of execution. */
    public static Fact firstBroken(LivingEntity e, List<Fact> proof) {
        for (Fact f : proof) {
            boolean ok = switch (f.kind()) {
                case HP_RATIO_MAX -> e.getHealth() / e.getMaxHealth() <= f.value() + 1e-6;
                case HP_MAX -> e.getHealth() <= f.value() + 1e-6;
                case HAS -> StatusData.has(e, f.status());
                case STACKS_MIN -> StatusData.stacks(e, f.status()) >= f.value();
            };
            if (!ok) return f;
        }
        return null;
    }

    public static String formula(List<Fact> proof) {
        String bound = "最大生命";
        int statuses = 0;
        double marks = 0;
        for (Fact f : proof) {
            switch (f.kind()) {
                case HP_RATIO_MAX -> bound = "最大生命×" + Node.fmt(f.value());
                case HP_MAX -> bound = "min(最大生命, " + Node.fmt(f.value()) + ")";
                case HAS -> statuses++;
                case STACKS_MIN -> marks += f.value();
            }
        }
        double mult = Math.pow(PER_STATUS, statuses) * Math.max(MARK_FLOOR, Math.pow(PER_MARK, marks));
        return "代价 = 4 × " + bound + (mult < 1 ? " × " + String.format("%.2f", mult) : "");
    }
}
