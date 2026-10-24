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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
    /** On its back: top speed and climb in blocks a tick, how much of the way to them it closes each tick, and its turn in degrees a tick. */
    private static final double RIDDEN_SPEED = 1.2, CLIMB = 0.5, EASE = 0.25;
    private static final float TURN = 8;
    private static final int FIRE_COOLDOWN = 20, BREATH_MAX = 100, BREATH_RESTART = 20;
    private static final double BREATH_RANGE = 14, BREATH_CONE = Math.cos(Math.toRadians(20));
    private static final float BREATH_DAMAGE = 4;

    /**
     * Who flies it besides its master on its back: a pilot names where to fly each tick, and the
     * driver steps aside while one is set.
     */
    public interface Pilot {
        @Nullable Vec3 steer(EnderDragon dragon);
    }

    private static final Map<EnderDragon, Pilot> PILOTS = new WeakHashMap<>();
    private static final Map<EnderDragon, LivingEntity> STRAFING = new WeakHashMap<>();
    private static final Map<EnderDragon, Long> NEXT_ATTACK = new WeakHashMap<>();
    private static final java.util.Set<EnderDragon> CENTRED = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<EnderDragon, Long> NEXT_FIRE = new WeakHashMap<>();
    /** Ticks of breath left, and who is breathing now. */
    private static final Map<EnderDragon, Integer> BREATH = new WeakHashMap<>();
    private static final java.util.Set<EnderDragon> BREATHING = java.util.Collections.newSetFromMap(new WeakHashMap<>());

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

    /** Whether {@code shot} is a familiar dragon's own and {@code e} is that dragon, one of its parts or a rider: its breath flies through them. */
    public static boolean ownBody(Projectile shot, Entity e) {
        if (!(shot.getOwner() instanceof EnderDragon d) || !Familiars.isFamiliar(d)) return false;
        return e == d || e instanceof EnderDragonPart part && part.parentMob == d || e.getVehicle() == d;
    }

    static void tick(EnderDragon d, Player owner, @Nullable LivingEntity target, @Nullable Vec3 goTo, @Nullable Vec3 post) {
        if (d.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING) {
            Familiars.dismiss(d); // never its death throes
            return;
        }
        Pilot pilot = PILOTS.get(d);
        // It circles its post if it has one, else its master.
        recentre(d, post != null ? post : owner.position());
        if (pilot != null) {
            fly(d, pilot.steer(d));
            return;
        }
        if (d.getFirstPassenger() == owner && owner instanceof ServerPlayer rider) {
            flyRidden(d, rider);
            breathe(d, rider);
            return;
        }
        BREATHING.remove(d);
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

    /** Whether its master on its back is flying it: then its own flight step stands aside, whatever its phase (see {@code EnderDragonMixin}). */
    public static boolean flownByRider(EnderDragon d) {
        return !PILOTS.containsKey(d) && d.getFirstPassenger() instanceof Player p && FamiliarRiding.isMaster(d, p);
    }

    /**
     * Its master on its back flies it, in either mode: with no key held it hovers where it is, as over the
     * End's portal. Forward heads where they look (looking up climbs, looking down dives), back eases it
     * backwards, left and right slip sideways; jump climbs and sprint (Ctrl by default) descends, whatever
     * the gaze. It turns to face where its rider faces.
     */
    private static void flyRidden(EnderDragon d, ServerPlayer rider) {
        var input = rider.getLastClientInput();
        float yaw = rider.getYRot() * Mth.DEG_TO_RAD;
        Vec3 ahead = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), left = new Vec3(Mth.cos(yaw), 0, Mth.sin(yaw));
        Vec3 want = Vec3.ZERO;
        if (input.forward()) want = want.add(rider.getLookAngle());
        if (input.backward()) want = want.subtract(ahead.scale(0.4));
        if (input.left()) want = want.add(left.scale(0.5));
        if (input.right()) want = want.subtract(left.scale(0.5));
        want = want.scale(RIDDEN_SPEED);
        int vertical = (input.jump() ? 1 : 0) - (input.sprint() ? 1 : 0);
        if (vertical != 0) want = new Vec3(want.x, vertical * CLIMB, want.z);
        // The phase is only its look: hovering beats its wings hard and bows its head, as a perched dragon
        // does, so on the move it is charging (a point ahead it never reaches), and glides as it flies.
        EnderDragonPhaseManager pm = d.getPhaseManager();
        if (want.equals(Vec3.ZERO)) {
            if (pm.getCurrentPhase().getPhase() != EnderDragonPhase.HOVERING) pm.setPhase(EnderDragonPhase.HOVERING);
        } else {
            if (pm.getCurrentPhase().getPhase() != EnderDragonPhase.CHARGING_PLAYER) pm.setPhase(EnderDragonPhase.CHARGING_PLAYER);
            pm.getPhase(EnderDragonPhase.CHARGING_PLAYER).setTarget(d.position().add(want.normalize().scale(40)));
        }
        // It gathers speed and slows to a hover over a few ticks rather than at once.
        Vec3 v = d.getDeltaMovement();
        v = v.add(want.subtract(v).scale(EASE));
        d.setDeltaMovement(v);
        d.move(MoverType.SELF, v);
        // A dragon flies along (sin yaw, -cos yaw), the opposite of a player's yaw.
        d.setYRot(d.getYRot() + Mth.clamp(Mth.wrapDegrees(rider.getYRot() + 180 - d.getYRot()), -TURN, TURN));
        d.yBodyRot = d.getYRot();
    }

    /** What its master on its back may do with it in drive mode ({@link dev.logos.net.DragonControlPayload}). */
    public static final byte FIRE = 0, BREATH_ON = 1, BREATH_OFF = 2;

    public static void control(ServerPlayer p, byte action) {
        if (!(p.getVehicle() instanceof EnderDragon d) || d.getFirstPassenger() != p || !FamiliarRiding.isMaster(d, p)) return;
        switch (action) {
            case FIRE -> fireball(d, p);
            case BREATH_ON -> {
                // Run dry, it must catch its breath before breathing again.
                if (BREATH.getOrDefault(d, BREATH_MAX) >= BREATH_RESTART) BREATHING.add(d);
                else p.sendOverlayMessage(breathGauge(BREATH.get(d)));
            }
            case BREATH_OFF -> BREATHING.remove(d);
            default -> {}
        }
    }

    /** A dragon fireball from its mouth at where its rider looks, once a second. */
    private static void fireball(EnderDragon d, ServerPlayer rider) {
        long now = d.level().getGameTime();
        if (now < NEXT_FIRE.getOrDefault(d, 0L)) return;
        NEXT_FIRE.put(d, now + FIRE_COOLDOWN);
        Vec3 from = mouth(d), dir = aimPoint(rider, 96).subtract(from).normalize();
        var f = new DragonFireball(d.level(), d, dir);
        f.snapTo(from.x, from.y, from.z, 0, 0);
        // Launched faster than it flies, so the dragon does not overtake its own fireball.
        f.setDeltaMovement(dir.scale(1.2).add(d.getDeltaMovement()));
        d.level().addFreshEntity(f);
        d.level().levelEvent(null, 1017, d.blockPosition(), 0);
    }

    /**
     * Its breath, while its rider holds it on: a stream of dragon's breath from its mouth where they
     * look, burning what it reaches (never its master's side) and leaving a lingering cloud where it
     * strikes the ground. Five seconds of it at most; let go, it comes back over ten.
     */
    private static void breathe(EnderDragon d, ServerPlayer rider) {
        int left = BREATH.getOrDefault(d, BREATH_MAX);
        long now = d.level().getGameTime();
        if (!BREATHING.contains(d) || left <= 0) {
            BREATHING.remove(d);
            if (left < BREATH_MAX) {
                if (now % 2 == 0) BREATH.put(d, ++left);
                if (now % 10 == 0) rider.sendOverlayMessage(breathGauge(left));
            }
            return;
        }
        BREATH.put(d, --left);
        ServerLevel level = (ServerLevel) d.level();
        Vec3 from = mouth(d), aim = aimPoint(rider, 96).subtract(from).normalize();
        BlockHitResult hit = level.clip(new ClipContext(from, from.add(aim.scale(BREATH_RANGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, d));
        double reach = hit.getType() == HitResult.Type.MISS ? BREATH_RANGE : hit.getLocation().distanceTo(from);
        var breath = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);
        var r = d.getRandom();
        for (int i = 0; i < 6; i++) {
            Vec3 v = aim.add(r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08).scale(0.8 + r.nextDouble() * 0.6);
            level.sendParticles(breath, from.x, from.y, from.z, 0, v.x, v.y, v.z, 1.0);
        }
        if (now % 10 == 0) {
            UUID master = rider.getUUID();
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, from.add(aim.scale(reach))).inflate(4))) {
                if (e == d || e.getVehicle() == d || Familiars.friendly(master, e)) continue;
                Vec3 to = e.getBoundingBox().getCenter().subtract(from);
                if (to.length() > reach + 1 || to.normalize().dot(aim) < BREATH_CONE) continue;
                e.hurtServer(level, d.damageSources().source(DamageTypes.DRAGON_BREATH, d), BREATH_DAMAGE);
            }
            rider.sendOverlayMessage(breathGauge(left));
        }
        if (now % 20 == 0) {
            level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.5f, 1.2f);
            if (hit.getType() != HitResult.Type.MISS) {
                // As its fireballs do: a cloud of breath where it strikes, its owner the dragon (so its master's side is spared).
                var cloud = new AreaEffectCloud(level, hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
                cloud.setOwner(d);
                cloud.setCustomParticle(breath);
                cloud.setRadius(2);
                cloud.setDuration(60);
                cloud.setPotionDurationScale(0.25F);
                cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 1));
                level.addFreshEntity(cloud);
            }
        }
    }

    private static Component breathGauge(int left) {
        int full = Math.round(10f * left / BREATH_MAX);
        return Component.literal("🐉 龙息 " + "■".repeat(full) + "□".repeat(10 - full)).withStyle(left < BREATH_RESTART ? ChatFormatting.GRAY : ChatFormatting.LIGHT_PURPLE);
    }

    private static Vec3 mouth(EnderDragon d) {
        return d.head.getBoundingBox().getCenter();
    }

    /** What its rider is looking at, up to {@code range} blocks off. */
    private static Vec3 aimPoint(ServerPlayer rider, double range) {
        Vec3 eye = rider.getEyePosition(), end = eye.add(rider.getLookAngle().scale(range));
        var hit = rider.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, rider));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
    }

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
        // Its path and the point it was heading for belong to the old ring.
        var pm = d.getPhaseManager();
        if (pm.getCurrentPhase().getPhase() == EnderDragonPhase.HOLDING_PATTERN) pm.getCurrentPhase().begin();
    }

    /** Brought back high above its master: a dragon needs sky, not a spot on the ground. */
    static boolean teleportNear(EnderDragon d, Player owner) {
        d.snapTo(owner.getX(), owner.getY() + 20, owner.getZ(), d.getYRot(), d.getXRot());
        d.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
        return true;
    }
}
