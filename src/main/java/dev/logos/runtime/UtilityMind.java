package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The mind of a creature that chooses for itself, a small utility AI: every half second it senses its
 * surroundings, scores each behaviour it knows (0 = pointless, about 1 = pressing) and does the best
 * one; the one it is already doing gets a little extra, so it does not flicker between two. A new
 * behaviour is one class (an inner class of the mind, or a nested class beside it as in
 * {@link ColossusBehavior}) plus an entry in the list the mind's constructor fills.
 * <p>
 * Its parts are protected, so another mod's creature can have one too (a Touhou Little Maid maid, in
 * {@code compat/tlm}). It is a {@link Goal} for creatures run by goals; one run by a brain instead calls
 * {@link #thinkAndAct} from a brain behaviour of its own.
 */
public abstract class UtilityMind<T extends Mob> extends Goal {
    static final int THINK_EVERY = 10;
    private static final double HYSTERESIS = 0.1;

    protected final T mob;
    /** How long a freshly chosen behaviour is stuck to unless something is far more pressing; 0: not at all. */
    private final int commit;
    protected final List<Behavior> behaviors = new ArrayList<>();
    protected @Nullable Behavior current;
    private long committedUntil;
    protected final Map<Behavior, Double> lastScores = new LinkedHashMap<>();

    protected UtilityMind(T mob, int commit) {
        this.mob = mob;
        this.commit = commit;
    }

    /** Refreshes what it knows of its surroundings, just before it thinks. */
    protected abstract void sense();

    /** What it is doing: the id of a behaviour or act, or of what it does with none ({@code logos.mind.<id>} reads it out). */
    public abstract String doing();

    /** {@link #doing}, for players to read. */
    public Component doingName() {
        return Component.translatable("logos.mind." + doing());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Thinks when it is time to (or it has nothing to do), then carries on with what it chose. */
    protected final void thinkAndAct() {
        long now = mob.level().getGameTime();
        if (current == null || now % THINK_EVERY == 0) {
            sense();
            think(now);
        }
        if (current != null) current.tick(now);
    }

    /** Stops what it is doing, so it chooses afresh when it next thinks (its creature stopped thinking a while). */
    protected final void stopCurrent() {
        if (current != null) current.stop();
        current = null;
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
    public Component describe() {
        MutableComponent scores = Component.empty();
        lastScores.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> scores.append("  ")
                .append(Component.translatable("logos.mind." + e.getKey().id)).append(String.format(java.util.Locale.ROOT, " %.2f", e.getValue())));
        return Component.translatable("logos.mind.describe", doingName(), scores);
    }

    protected abstract static class Behavior {
        /** Its id, read out as {@code logos.mind.<id>}. */
        public final String id;

        protected Behavior(String id) {
            this.id = id;
        }

        protected abstract double score();

        protected void start() {}

        protected abstract void tick(long now);

        protected void stop() {}
    }
}
