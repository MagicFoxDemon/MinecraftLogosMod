package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 嘲讽: for a while a creature fights only its bait, whatever else strikes it or comes near. Its own mind is
 * never replaced, only overruled: every target it would take is turned back to the bait (as a familiar's is to
 * its master's fight), and when the taunt ends it is let go with no target and no grudge against the bait, to
 * pick its fight again by itself. A familiar answers to its master alone and is never taunted. Nothing is saved:
 * a creature reloaded with its chunk is free.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Taunts {
    /** The longest a taunt holds, in seconds. */
    public static final double MAX_SECONDS = 30;
    /** A creature further than this from its bait lets go of it. */
    public static final double REACH = 32;

    private record Lease(LivingEntity bait, long until) {}

    private static final Map<Mob, Lease> HELD = new WeakHashMap<>();

    private Taunts() {}

    /** Whether {@code m} can be made to fight {@code bait} at all: not a familiar, not itself, and a foe it could attack. */
    public static boolean mayTaunt(Mob m, LivingEntity bait) {
        return m != bait && m.isAlive() && bait.isAlive() && !bait.isSpectator() && m.level() == bait.level()
                && !Familiars.isFamiliar(m) && m.canAttack(bait);
    }

    /** Makes {@code m} fight only {@code bait} for {@code ticks}; a new taunt replaces the one it is under. */
    public static boolean taunt(Mob m, LivingEntity bait, int ticks) {
        if (!mayTaunt(m, bait)) return false;
        Lease old = HELD.put(m, new Lease(bait, m.level().getGameTime() + ticks));
        if (old != null && old.bait != bait) forgive(m, old.bait);
        Familiars.fightTarget(m, bait);
        return true;
    }

    /** What {@code m} is taunted onto, if anything. */
    public static @Nullable LivingEntity baitOf(Mob m) {
        Lease l = HELD.get(m);
        return l == null ? null : l.bait;
    }

    @SubscribeEvent
    static void onChangeTarget(LivingChangeTargetEvent event) {
        if (HELD.isEmpty() || !(event.getEntity() instanceof Mob m)) return;
        Lease l = HELD.get(m);
        if (l == null || event.getNewAboutToBeSetTarget() == l.bait || !mayTaunt(m, l.bait)) return;
        event.setNewAboutToBeSetTarget(l.bait);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (HELD.isEmpty()) return;
        for (var e : new ArrayList<>(HELD.entrySet())) {
            Mob m = e.getKey();
            Lease l = e.getValue();
            if (m.isRemoved() || !mayTaunt(m, l.bait) || m.level().getGameTime() >= l.until || m.distanceTo(l.bait) > REACH) {
                release(m);
                continue;
            }
            // A brain forgets its target by itself (out of sight, out of range): give it back.
            if (m.getTarget() != l.bait) Familiars.fightTarget(m, l.bait);
            // A warden's fury fades; keep it on the bait.
            if (m instanceof Warden w && m.level().getGameTime() % 20 == 0) w.increaseAngerAt(l.bait, 20, false);
        }
    }

    /** Ends the taunt on {@code m}, if any: it lets go of the bait and its own mind chooses again. */
    public static void release(Mob m) {
        Lease l = HELD.remove(m);
        if (l == null) return;
        forgive(m, l.bait);
        if (fights(m, l.bait)) Familiars.fightTarget(m, null);
    }

    /** Whether {@code m} still holds {@code bait} as its target anywhere, even where getTarget would hide it (a bait now in creative). */
    private static boolean fights(Mob m, LivingEntity bait) {
        if (m.getTarget() == bait || m.getTargetUnchecked() == bait) return true;
        var brain = m.getBrain();
        return brain.checkMemory(MemoryModuleType.ATTACK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)
                && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == bait;
    }

    /** Leaves no grudge the taunt made: a neutral creature's anger, a piglin's, a warden's. */
    private static void forgive(Mob m, LivingEntity bait) {
        if (m instanceof NeutralMob n && n.getPersistentAngerTarget() != null && n.getPersistentAngerTarget().matches(bait)) n.stopBeingAngry();
        if (m instanceof Warden w) w.clearAnger(bait);
        var brain = m.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.ANGRY_AT)) {
            UUID at = brain.getMemory(MemoryModuleType.ANGRY_AT).orElse(null);
            if (bait.getUUID().equals(at)) brain.eraseMemory(MemoryModuleType.ANGRY_AT);
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        HELD.clear();
    }
}
