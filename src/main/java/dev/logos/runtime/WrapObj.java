package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Category;
import dev.logos.lang.Node;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Salt form "imbue" (魔力缠绕): a spell wound about a living thing's main hand for a while. Whatever the hand holds
 * wears it, and its events go off on that hand's blows (see {@link ManaWrap}, which tells it of them): on-hit when a
 * melee blow charged to {@link ManaWrap#CHARGE} (a mob's, always) or an arrow or trident the hand loosed strikes,
 * on-kill when that blow, or what on-hit set off, kills, on-swing when a player swings at that charge, hit or miss.
 * It is kept up and pulses on-tick like a field. Its events know the holder as {@code self}; it is drawn through the
 * holder's {@link ManaWrap} attachment, which it adds no damage to.
 */
public final class WrapObj extends SpellObject {
    public static final double DEPLOY = 6, UPKEEP = 0.5;

    /** The spell wrap each living thing wears, by its UUID; one at a time. */
    private static final Map<UUID, WrapObj> WORN = new HashMap<>();

    private final LivingEntity holder;
    private final int every;
    /** Game time of the last swing that counted, or -1. */
    private long lastSwing = -1;

    public WrapObj(Node node, Ctx ctx, LivingEntity holder, int life, int every) {
        super(node, ctx.withVar("self", holder).withVar(Ctx.IN_WRAP, true), Targeting.center(holder), life);
        this.holder = holder;
        this.every = Math.max(1, every);
    }

    /** The spell wrap {@code t} wears, if any. */
    public static @Nullable WrapObj on(LivingEntity t) {
        WrapObj w = WORN.get(t.getUUID());
        return w == null || w.isEnded() ? null : w;
    }

    /** Winds it about the holder's hand once spawned, ending the wrap the hand wore before (whose on-expire runs). */
    public void wear() {
        WrapObj old = WORN.put(holder.getUUID(), this);
        if (old != null && old != this) old.end();
        ManaWrap.show(holder, node.element, level.getGameTime() + life - age);
    }

    @Override
    public void tick() {
        if (++age > life || !holder.isAlive() || holder.level() != level) {
            end();
            return;
        }
        pos = Targeting.center(holder);
        if (age % every == 0) {
            if (!inst.inWrap(() -> inst.spend(UPKEEP, "缠绕维持", Category.FORM))) return;
            Interp.fire(node, "on-tick", ctx.at(pos).aiming(heading()));
        }
    }

    @Override
    protected Vec3 heading() {
        return holder.getLookAngle();
    }

    /** A blow of the hand has struck {@code t} at {@code here}, going {@code aim}. */
    void hit(LivingEntity t, Vec3 here, Vec3 aim) {
        if (isEnded()) return;
        inst.touch(level, here);
        Interp.fire(node, "on-hit", ctx.on(t, here).aiming(aim));
        if (t.isDeadOrDying() && !isEnded()) Interp.fire(node, "on-kill", ctx.on(t, Targeting.center(t)).aiming(aim));
    }

    /** The hand swings, from {@code here} towards {@code aim}. */
    void swing(Vec3 here, Vec3 aim) {
        if (isEnded()) return;
        Interp.fire(node, "on-swing", ctx.at(here).aiming(aim));
    }

    /**
     * A player's swing, hit or miss, as their client tells it once the blow is charged to {@link ManaWrap#CHARGE}.
     * The server holds it to the same pace: a swing counts only that far into the hand's attack time after the last.
     */
    public void swingOf(Player p) {
        long now = level.getGameTime();
        if (p != holder || lastSwing >= 0 && now - lastSwing < ManaWrap.CHARGE * p.getCurrentItemAttackStrengthDelay() - 1) return;
        lastSwing = now;
        swing(p.getEyePosition(), p.getLookAngle());
    }

    @Override
    protected void onEnded() {
        WORN.remove(holder.getUUID(), this);
        ManaWrap.hide(holder);
    }
}
