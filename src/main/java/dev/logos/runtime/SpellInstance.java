package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

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
    public final LivingEntity caster;
    public final ServerLevel level;
    public final double injected;
    /** Share of entropic mana that returns as free mana (the page's grade). */
    public final double recovery;
    public double mana;
    /** Where the spell last acted; free mana gathers there when it ends. */
    private @Nullable ServerLevel lastLevel;
    private @Nullable Vec3 lastPos;
    /** Extinguished on purpose ({@code stop}): its leftover mana is free, not entropic. */
    private boolean stopped;
    /** Every entity this spell has affected; {@code :fresh} selectors skip them. */
    public final Set<UUID> affected = new LinkedHashSet<>();
    public final List<Double> trace = new ArrayList<>();
    public int objects;
    public boolean dead;
    private int pending;
    private boolean finished;
    private String endReason;
    private final long startTick;
    private long opsTick = -1;
    private int ops;

    public SpellInstance(Spell spell, LivingEntity caster, ServerLevel level, double injected, double recovery) {
        this.spell = spell;
        this.caster = caster;
        this.level = level;
        this.injected = injected;
        this.recovery = recovery;
        this.mana = injected;
        this.startTick = SpellRuntime.now();
        trace.add(mana);
    }

    /** Records where the spell is acting now (a rune ran there, or a spell body is there). */
    public void touch(ServerLevel at, Vec3 pos) {
        lastLevel = at;
        lastPos = pos;
    }

    /** Pays for one rune execution. Returns false (and extinguishes the spell) if the pool is dry. */
    public boolean spend(double amount, String what) {
        if (dead) return false;
        if (amount > mana + 1e-9) {
            terminate("魔力耗尽（" + what + " 需要 " + Node.fmt(amount) + "，仅剩 " + Node.fmt(mana) + "）");
            return false;
        }
        mana -= amount;
        if (amount > 0) trace.add(mana);
        return true;
    }

    /** Counts interpreter steps per tick; runaway programs destabilize. */
    public boolean step() {
        long now = SpellRuntime.now();
        if (now != opsTick) {
            opsTick = now;
            ops = 0;
        }
        int max = dev.logos.LogosConfig.maxOpsPerTick();
        if (++ops > max) terminate("法则失稳：单刻运算超过 " + max + " 步（配置 maxOpsPerTick）");
        return !dead;
    }

    public boolean finished() {
        return finished;
    }

    public String endReason() {
        return endReason;
    }

    public void terminate(String reason) {
        if (dead) return;
        dead = true;
        endReason = reason;
        finish();
    }

    /** Ends the spell as it asked to ({@code stop}): like a natural end, all leftover mana is set free. */
    public void stop(String reason) {
        if (dead) return;
        stopped = true;
        terminate(reason);
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
        if (endReason == null) endReason = "自然消散";
        double free = endedNaturally() ? mana : mana * recovery;
        // A vessel that cast it (a mana lifeform) takes its leftover back; anyone else's goes free.
        if (caster instanceof ManaVessel vessel) vessel.receiveMana(free);
        else FreeManaEntity.release(this, lastLevel, lastPos, free);
        if (!(caster instanceof ServerPlayer player) || !PlayerMagic.of(player).trace) return;
        double secs = (SpellRuntime.now() - startTick) / 20.0;
        ChatFormatting color = endedNaturally() ? ChatFormatting.GRAY : ChatFormatting.RED;
        String left = mana <= 0 ? "" : endedNaturally() ? " | 游离魔力 " + Node.fmt(free)
                : " | 熵化魔力 " + Node.fmt(mana) + " × " + Math.round(recovery * 100) + "% → 游离 " + Node.fmt(free);
        player.sendSystemMessage(Component.literal("《" + spell.name() + "》 " + endReason).withStyle(color)
                .append(Component.literal(String.format(" | 魔力 %s → %s%s | 影响 %d 个实体 | %.1fs",
                        Node.fmt(injected), Node.fmt(mana), left, affected.size(), secs)).withStyle(ChatFormatting.DARK_GRAY)));
        if (trace.size() > 2) {
            player.sendSystemMessage(Component.literal("  轨迹: " + sampleTrace()).withStyle(ChatFormatting.DARK_GRAY));
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
