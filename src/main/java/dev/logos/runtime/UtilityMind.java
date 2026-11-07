package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The mind of a creature that chooses for itself, a small utility AI: every half second it senses its
 * surroundings, scores each behaviour it knows (0 = pointless, about 1 = pressing) and does the best
 * one; the one it is already doing gets a little extra, so it does not flicker between two. A new
 * behaviour is one inner class of the mind plus an entry in the list its constructor fills.
 */
public abstract class UtilityMind<T extends Mob> extends Goal {
    static final int THINK_EVERY = 10;
    private static final double HYSTERESIS = 0.1;

    final T mob;
    /** How long a freshly chosen behaviour is stuck to unless something is far more pressing; 0: not at all. */
    private final int commit;
    final List<Behavior> behaviors = new ArrayList<>();
    @Nullable Behavior current;
    private long committedUntil;
    final Map<Behavior, Double> lastScores = new LinkedHashMap<>();

    UtilityMind(T mob, int commit) {
        this.mob = mob;
        this.commit = commit;
    }

    /** Refreshes what it knows of its surroundings, just before it thinks. */
    abstract void sense();

    /** What it is doing, in words. */
    public abstract String doingZh();

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Thinks when it is time to (or it has nothing to do), then carries on with what it chose. */
    final void thinkAndAct() {
        long now = mob.level().getGameTime();
        if (current == null || now % THINK_EVERY == 0) {
            sense();
            think(now);
        }
        if (current != null) current.tick(now);
    }

    private void think(long now) {
        Behavior best = null;
        double bestScore = 0;
        lastScores.clear();
        for (Behavior b : behaviors) {
            double s = b.score();
            lastScores.put(b, s);
            if (b == current && s > 0) s += HYSTERESIS;
            if (s > bestScore) {
                best = b;
                bestScore = s;
            }
        }
        // Freshly chosen, it sticks to it a moment unless something is far more pressing.
        if (commit > 0 && current != null && now < committedUntil && lastScores.getOrDefault(current, 0.0) > 0 && bestScore < lastScores.get(current) + 0.3) return;
        if (best != current) {
            if (current != null) current.stop();
            current = best;
            committedUntil = now + commit;
            if (current != null) current.start();
        }
    }

    /** Its scores at the last think, best first, for {@code /logos mind}. */
    public String describe() {
        StringBuilder sb = new StringBuilder("正在" + doingZh() + "｜");
        lastScores.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(e.getKey().zh).append(' ').append(String.format("%.2f", e.getValue())).append("  "));
        return sb.toString().strip();
    }

    abstract static class Behavior {
        final String zh;

        Behavior(String zh) {
            this.zh = zh;
        }

        abstract double score();

        void start() {}

        abstract void tick(long now);

        void stop() {}
    }
}
