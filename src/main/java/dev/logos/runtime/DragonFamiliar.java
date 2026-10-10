package dev.logos.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import dev.logos.mixin.EnderDragonAccessor;
import dev.logos.mixin.EnderDragonFightAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The ender dragon as a familiar: the one familiar whose behaviour is changed, because its own
 * belongs to the End fight. Its vanilla phases all stay — the holding pattern, the strafing
 * fireballs, the charge — and a driver only decides which one runs: it circles its master (its
 * flight nodes rebuilt around them, the vanilla 40-block ring), never lands or perches, and only
 * strafes or charges what its master is fighting.
 * <p>
 * Rules of the End: while the End's own dragon lives no false one may be summoned there, and when
 * the true dragon is brought back every summoned one is unmade.
 */
public final class DragonFamiliar {
    public static final String THRONE_TAKEN = "真龙尚居王座，岂容伪物僭越？";
    public static final String THRONE_RECLAIMED = "真龙既归王座，伪物焉敢争辉？";
    /** Its ring is 40 blocks wide, so it is called back from much further than other familiars. */
    public static final double CALM_LEASH = 100, LOST = 160;
    private static final double RECENTRE = 32;

    /**
     * Who flies it. Today only the driver's AI; a future rider would install a pilot that names
     * where to fly each tick, and the driver steps aside while one is set.
     */
    public interface Pilot {
        @Nullable Vec3 steer(EnderDragon dragon);
    }

    private static final Map<EnderDragon, Pilot> PILOTS = new WeakHashMap<>();
    private static final Map<EnderDragon, LivingEntity> STRAFING = new WeakHashMap<>();
    private static final Map<EnderDragon, Long> NEXT_ATTACK = new WeakHashMap<>();
    private static final java.util.Set<EnderDragon> CENTRED = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    private DragonFamiliar() {}

    public static void setPilot(EnderDragon dragon, @Nullable Pilot pilot) {
        if (pilot == null) PILOTS.remove(dragon);
        else PILOTS.put(dragon, pilot);
    }

    /** Why a dragon may not be summoned in {@code level} right now, or null. */
    public static @Nullable String forbidden(ServerLevel level) {
        var fight = level.getDragonFight();
        if (level.dimension() == Level.END && fight != null && !((EnderDragonFightAccessor) fight).logos$dragonKilled()) return THRONE_TAKEN;
        return null;
    }

    /** The true dragon has returned to {@code level}: every summoned dragon there is unmade. */
    public static void throneReclaimed(ServerLevel level) {
        for (var m : List.copyOf(Familiars.live())) {
            if (!(m instanceof EnderDragon d) || d.level() != level) continue;
            var b = Familiars.bondOf(d);
            Player owner = b == null ? null : level.getPlayerByUUID(b.owner());
            if (owner != null) owner.sendSystemMessage(Component.literal(THRONE_RECLAIMED).withStyle(ChatFormatting.RED));
            Familiars.dismiss(d);
        }
    }

    /** The master's side is not swept by its wings nor struck by its head. */
    public static void spare(EnderDragon dragon, List<Entity> entities) {
        UUID master = Familiars.masterOf(dragon);
        // Nor whoever rides it: the companion behind its master is not on the master's side by any other measure.
        if (master != null) entities.removeIf(e -> Familiars.friendly(master, e) || e.getVehicle() == dragon);
    }

    static void tick(EnderDragon d, Player owner, @Nullable LivingEntity target, @Nullable Vec3 goTo, @Nullable Vec3 post) {
        if (d.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING) {
            Familiars.dismiss(d); // never its death throes
            return;
        }
        Pilot pilot = PILOTS.get(d);
        if (pilot == null && d.getFirstPassenger() == owner) pilot = MASTER_ON_ITS_BACK;
        // It circles its post if it has one, else its master.
        recentre(d, post != null ? post : owner.position());
        if (pilot != null) {
            fly(d, pilot.steer(d));
            return;
        }
        if (goTo != null) {
            fly(d, goTo); // a gather order: straight there
            return;
        }
        EnderDragonPhaseManager pm = d.getPhaseManager();
        EnderDragonPhase<?> phase = pm.getCurrentPhase().getPhase();
        // Landing, perching and hovering belong to the End's portal; a familiar stays on the wing.
        if (phase == EnderDragonPhase.LANDING_APPROACH || phase == EnderDragonPhase.LANDING || phase == EnderDragonPhase.TAKEOFF
                || phase == EnderDragonPhase.SITTING_SCANNING || phase == EnderDragonPhase.SITTING_FLAMING
                || phase == EnderDragonPhase.SITTING_ATTACKING || phase == EnderDragonPhase.HOVERING) {
            pm.setPhase(EnderDragonPhase.HOLDING_PATTERN);
            phase = EnderDragonPhase.HOLDING_PATTERN;
        }
        if (target == null) {
            if (phase == EnderDragonPhase.STRAFE_PLAYER || phase == EnderDragonPhase.CHARGING_PLAYER) pm.setPhase(EnderDragonPhase.HOLDING_PATTERN);
            STRAFING.remove(d);
            return;
        }
        if (phase == EnderDragonPhase.STRAFE_PLAYER && STRAFING.get(d) != target) {
            // It chose its own prey (the nearest player, perhaps its master): turn it on the ordered target.
            pm.getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(target);
            STRAFING.put(d, target);
        }
        long now = d.level().getGameTime();
        if (phase == EnderDragonPhase.HOLDING_PATTERN && now >= NEXT_ATTACK.getOrDefault(d, 0L)) {
            NEXT_ATTACK.put(d, now + 60 + d.getRandom().nextInt(60));
            if (d.getRandom().nextInt(3) == 0) {
                pm.setPhase(EnderDragonPhase.CHARGING_PLAYER);
                pm.getPhase(EnderDragonPhase.CHARGING_PLAYER).setTarget(target.position());
            } else {
                pm.setPhase(EnderDragonPhase.STRAFE_PLAYER);
                pm.getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(target);
                STRAFING.put(d, target);
            }
        }
    }

    /**
     * Its master on its back flies it by their gaze: holding forward, it heads where they look
     * (looking up climbs, looking down dives); letting go, it circles where it is. No attacks while ridden.
     */
    private static final Pilot MASTER_ON_ITS_BACK = dragon -> {
        if (!(dragon.getFirstPassenger() instanceof net.minecraft.server.level.ServerPlayer rider) || !rider.getLastClientInput().forward()) return null;
        return rider.getEyePosition().add(rider.getLookAngle().scale(40));
    };

    private static void fly(EnderDragon d, @Nullable Vec3 toward) {
        EnderDragonPhaseManager pm = d.getPhaseManager();
        if (toward == null) {
            if (pm.getCurrentPhase().getPhase() != EnderDragonPhase.HOLDING_PATTERN) pm.setPhase(EnderDragonPhase.HOLDING_PATTERN);
            return;
        }
        // The charge phase flies straight at a point; kept 40 blocks ahead, it never arrives and so never ends.
        if (pm.getCurrentPhase().getPhase() != EnderDragonPhase.CHARGING_PLAYER) pm.setPhase(EnderDragonPhase.CHARGING_PLAYER);
        pm.getPhase(EnderDragonPhase.CHARGING_PLAYER).setTarget(toward);
    }

    /** Moves its fight origin to {@code center} (its master, or its post) and has its flight nodes built again around it. */
    private static void recentre(EnderDragon d, Vec3 center) {
        var origin = d.getFightOrigin();
        double dx = origin.getX() + 0.5 - center.x, dz = origin.getZ() + 0.5 - center.z;
        if (CENTRED.contains(d) && dx * dx + dz * dz < RECENTRE * RECENTRE) return;
        CENTRED.add(d);
        d.setFightOrigin(net.minecraft.core.BlockPos.containing(center));
        Arrays.fill(((EnderDragonAccessor) d).logos$nodes(), null);
    }

    /** Brought back high above its master: a dragon needs sky, not a spot on the ground. */
    static boolean teleportNear(EnderDragon d, Player owner) {
        d.snapTo(owner.getX(), owner.getY() + 20, owner.getZ(), d.getYRot(), d.getXRot());
        d.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
        return true;
    }
}
