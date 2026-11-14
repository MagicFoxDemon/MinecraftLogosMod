package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Category;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * One running invocation of a spell. Owns the injected mana pool ("gas") that every rune
 * execution draws from; when it runs dry the whole spell goes out.
 */
public final class SpellInstance {
    public final Spell spell;
    /** Who cast it; a player who respawns hands it on to their new body ({@link SpellCasters}). */
    public LivingEntity caster;
    public final ServerLevel level;
    public final double injected;
    /** Share of entropic mana that returns as free mana (the page's grade). */
    public final double recovery;
    /** The caster's spell power and rune costs as they were when it was cast. */
    public final SpellModifiers modifiers;
    public double mana;
    /** Where the spell last acted; free mana gathers there when it ends. */
    private @Nullable ServerLevel lastLevel;
    private @Nullable Vec3 lastPos;
    /** Extinguished on purpose ({@code stop}): its leftover mana is free, not entropic. */
    private boolean stopped;
    /** Every entity this spell has affected; {@code :fresh} selectors skip them. */
    public final Set<UUID> affected = new LinkedHashSet<>();
    public final List<Double> trace = new ArrayList<>();
    /** Whether anyone will read {@link #trace}: only a real player who wants the report when the spell ends. */
    private final boolean tracing;
    public int objects;
    public boolean dead;
    private int pending;
    private boolean finished;
    private @Nullable Component endReason;
    /** Whether the caster hears why it was extinguished when they get no report (see {@link #terminate(Component, boolean)}). */
    private boolean told;
    private final long startTick;
    private long opsTick = -1;
    private int ops;
    /** How deep the running rune is inside a mana wrap's reach (see {@link #inWrap(java.util.function.Supplier)}). */
    private int wrapDepth;

    public SpellInstance(Spell spell, LivingEntity caster, ServerLevel level, double injected, double recovery) {
        this.spell = spell;
        this.caster = caster;
        this.level = level;
        this.injected = injected;
        this.recovery = recovery;
        this.modifiers = SpellModifiers.of(caster);
        this.mana = injected;
        SpellCasters.started(this);
        this.startTick = SpellRuntime.now();
        this.tracing = caster instanceof ServerPlayer p && !(p instanceof net.neoforged.neoforge.common.util.FakePlayer) && reports(p);
        if (tracing) trace.add(mana);
    }

    /** Records where the spell is acting now (a rune ran there, or a spell body is there). */
    public void touch(ServerLevel at, Vec3 pos) {
        lastLevel = at;
        lastPos = pos;
    }

    /** Pays for one execution of the rune named {@code what}. Returns false (and extinguishes the spell) if the pool is dry. */
    public boolean spend(double amount, String what) {
        dev.logos.lang.Rune rune = dev.logos.lang.Runes.get(what);
        return spend(amount, what, rune == null ? null : rune.category);
    }

    /**
     * Runs {@code body} in a mana wrap's reach: the imbue rune, its upkeep, and every rune in what the wrap
     * sets off. There runes pay {@link SpellModifiers#wrapCost} more and deal {@link SpellModifiers#wrapPower} more.
     */
    public <T> T inWrap(java.util.function.Supplier<T> body) {
        wrapDepth++;
        try {
            return body.get();
        } finally {
            wrapDepth--;
        }
    }

    /** Whether the rune running now is in a mana wrap's reach. */
    public boolean inWrap() {
        return wrapDepth > 0;
    }

    /** Pays {@code amount}, scaled by the caster's cost for runes of {@code category} (none: the overall cost only). */
    public boolean spend(double amount, String what, @Nullable Category category) {
        if (dead) return false;
        amount *= modifiers.cost(category);
        if (inWrap()) amount *= modifiers.wrapCost();
        if (amount > mana + 1e-9) {
            terminate(Component.translatable("logos.spell.end.out_of_mana", what, Node.fmt(amount), Node.fmt(mana)), false);
            return false;
        }
        mana -= amount;
        if (amount > 0 && tracing) trace.add(mana);
        return true;
    }

    /** Counts interpreter steps per tick; runaway programs destabilize. */
    public boolean step() {
        SpellRuntime.countStep();
        long now = SpellRuntime.now();
        if (now != opsTick) {
            opsTick = now;
            ops = 0;
        }
        int max = dev.logos.LogosConfig.maxOpsPerTick();
        if (++ops > max) terminate(Component.translatable("logos.spell.end.too_many_steps", max));
        return !dead;
    }

    public boolean finished() {
        return finished;
    }

    /** Why it ended, as its caster reads it; null while it runs. */
    public @Nullable Component endReasonText() {
        return endReason;
    }

    /** The language key of why it ended ({@code logos.spell.end.*} and the like), null for a reason that is not one: what tests compare. */
    public @Nullable String endKey() {
        return endReason != null && endReason.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : null;
    }

    /** {@link #endReasonText} as plain text (in the server's language), for logs and tests. */
    public @Nullable String endReason() {
        return endReason == null ? null : endReason.getString();
    }

    /** Extinguishes the spell because something went wrong (a missing anchor, a limit passed); its caster is told why. */
    public void terminate(Component reason) {
        terminate(reason, true);
    }

    /**
     * Extinguishes the spell; {@code tell} false when the caster needs no word of it: the mana ran out (how spells
     * end), the spell stopped itself, or whoever put it out tells them already.
     */
    public void terminate(Component reason, boolean tell) {
        if (dead) return;
        dead = true;
        told = tell;
        endReason = reason;
        finish();
    }

    /** Ends the spell as it asked to ({@code stop}): like a natural end, all leftover mana is set free. */
    public void stop(Component reason) {
        if (dead) return;
        stopped = true;
        terminate(reason, false);
    }

    /** Whether {@code p} gets the end-of-spell report: their own {@code /logos trace}, or the server's debug option. */
    private static boolean reports(ServerPlayer p) {
        return PlayerMagic.of(p).trace || dev.logos.LogosConfig.spellTrace();
    }

    /** The server is stopping and the world may already be closed: it ends here, setting no mana free and telling no one. */
    void abandon() {
        if (finished) return;
        dead = finished = true;
        endReason = Component.translatable("logos.spell.end.world_closed");
    }

    /** True if the spell ended on its own (or by its own stop); false if it was extinguished. */
    public boolean endedNaturally() {
        return !dead || stopped;
    }

    public void hold() {
        pending++;
    }

    public void release() {
        pending--;
        if (pending <= 0 && !dead) finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        SpellCasters.ended(this);
        if (endReason == null) endReason = Component.translatable("logos.spell.end.faded");
        // Runs wherever a spell ends, even while handling a crash: what it reaches (another mod's join-level listener) must not get out.
        try {
            settle();
        } catch (RuntimeException e) {
            SpellRuntime.report("Ending spell " + spell.name(), e);
        }
    }

    private void settle() {
        double free = endedNaturally() ? mana : mana * recovery;
        // A vessel that cast it (a mana lifeform, a maid) takes its leftover back; anyone else's goes free.
        ManaVessel vessel = ManaVessels.of(caster);
        if (vessel != null) vessel.receiveMana(free);
        else FreeManaEntity.release(this, lastLevel, lastPos, free);
        if (!(caster instanceof ServerPlayer player)) return;
        if (!reports(player)) {
            // Without the report, a spell the world broke off still says why, once and briefly, where alerts go.
            if (told && !player.hasDisconnected()) PlayerMagic.alert(player, Component.translatable("logos.spell.broken_off", spell.name(), endReason).withStyle(ChatFormatting.RED));
            return;
        }
        double secs = (SpellRuntime.now() - startTick) / 20.0;
        ChatFormatting color = endedNaturally() ? ChatFormatting.GRAY : ChatFormatting.RED;
        Component left = mana <= 0 ? Component.empty() : endedNaturally() ? Component.translatable("logos.spell.report.free", Node.fmt(free))
                : Component.translatable("logos.spell.report.entropic", Node.fmt(mana), Math.round(recovery * 100), Node.fmt(free));
        player.sendSystemMessage(Component.translatable("logos.spell.report.ended", spell.name(), endReason).withStyle(color)
                .append(Component.translatable("logos.spell.report.numbers", Node.fmt(injected), Node.fmt(mana), left, affected.size(),
                        String.format(java.util.Locale.ROOT, "%.1f", secs)).withStyle(ChatFormatting.DARK_GRAY)));
        if (trace.size() > 2) {
            player.sendSystemMessage(Component.translatable("logos.spell.report.trace", sampleTrace()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private String sampleTrace() {
        int n = trace.size();
        int max = 12;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(n, max); k++) {
            int idx = n <= max ? k : (int) Math.round(k * (n - 1) / (double) (max - 1));
            if (k > 0) sb.append(" → ");
            sb.append(Node.fmt(trace.get(idx)));
        }
        return sb.toString();
    }
}
