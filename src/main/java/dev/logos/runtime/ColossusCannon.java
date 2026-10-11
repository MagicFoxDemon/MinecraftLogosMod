package dev.logos.runtime;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Element;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The colossus's palm cannon. It gathers for about three seconds (sparks drawn in to the palm), then
 * holds a beam from its palm to its target for up to five seconds, standing still. The beam stops at
 * the first full block or construct in its way; it ends when the target dies, after five seconds, or
 * once it has been unable to reach the target for a second and a half.
 * <p>
 * Every half second it pays and strikes, growing hotter each second:
 * <pre>
 *  second  mana/s  damage per half second
 *    1      20      5 physical + 10 fire     (no knockback)
 *    2      40     10 physical + 20 fire     (no knockback)
 *    3      60     15 physical + 30 fire     (no knockback)
 *    4     150     50 void
 *    5     150     50 void
 * </pre>
 * The physical part lands first and the target's invulnerability is reset before each part, so
 * both land whole: Minecraft would otherwise let a second hit within the invulnerable window count
 * only for what it exceeds the first by. In a frenzy the mana is ten times as much.
 */
public final class ColossusCannon {
    public static final int MAX_TICKS = 100, GIVE_UP_BLOCKED = 30;
    private static final double[] MANA_PER_SECOND = {20, 40, 60, 150, 150};

    private final AlchemicalColossus c;
    private final @Nullable LivingEntity target;
    private final @Nullable Player rider;
    private int blocked;
    private boolean firing;

    ColossusCannon(AlchemicalColossus c, @Nullable LivingEntity target, @Nullable Player rider) {
        this.c = c;
        this.target = target;
        this.rider = rider;
    }

    /** Its right palm, raised and pointing ahead (the client draws the beam from the same spot). */
    static Vec3 muzzle(AlchemicalColossus c) {
        return c.local(0.45, 2.75, 2.4);
    }

    /** Whether the palm has a clear line to {@code e}. */
    static boolean canSee(AlchemicalColossus c, Entity e) {
        Vec3 from = muzzle(c), to = Targeting.center(e);
        return c.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c)).getType() == HitResult.Type.MISS;
    }

    /** The creature its rider is looking at within {@code range}, if any. */
    static @Nullable LivingEntity lookTarget(AlchemicalColossus c, Player rider, double range) {
        Vec3 eye = rider.getEyePosition(), end = eye.add(rider.getLookAngle().scale(range));
        BlockHitResult wall = c.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, rider));
        if (wall.getType() != HitResult.Type.MISS) end = wall.getLocation();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(c.level(), rider, eye, end, new AABB(eye, end).inflate(1),
                e -> e instanceof LivingEntity && e.isAlive() && e != c && e.getVehicle() != c, 0);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    /** While gathering: sparks and glimmers drawn in toward the palm from all around. */
    void gather(int t) {
        if (!(c.level() instanceof ServerLevel level) || t % 2 != 0) return;
        Vec3 m = muzzle(c);
        for (int i = 0; i < 6; i++) {
            Vec3 dir = new Vec3(c.getRandom().nextGaussian(), c.getRandom().nextGaussian(), c.getRandom().nextGaussian()).normalize();
            double r = 1.5 + c.getRandom().nextDouble() * 1.5;
            Vec3 from = m.add(dir.scale(r));
            Vec3 v = m.subtract(from).scale(0.09);
            // count 0: the offsets are the particle's velocity, so it flies into the palm.
            level.sendParticles(i % 3 == 0 ? ParticleTypes.ELECTRIC_SPARK : c.isBerserk() ? ParticleTypes.FLAME : ParticleTypes.END_ROD,
                    from.x, from.y, from.z, 0, v.x, v.y, v.z, 1);
        }
        if (t % 20 == 0) level.playSound(null, m.x, m.y, m.z, SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.8f + t * 0.01f);
    }

    void start() {
        firing = true;
        c.level().playSound(null, c.getX(), c.getY() + 2, c.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.6f, 0.8f);
    }

    /** Where the beam points: its target, or where its rider looks. */
    private Vec3 aim() {
        if (target != null) return Targeting.center(target);
        if (rider != null) return rider.getEyePosition().add(rider.getLookAngle().scale(48));
        return muzzle(c).add(c.getLookAngle().scale(32));
    }

    /** Tick {@code p} of firing (of at most {@code limit}); false when the beam should end. */
    boolean fire(int p, int limit) {
        if (!(c.level() instanceof ServerLevel level) || p >= limit) return false;
        if (target != null && !target.isAlive()) return false;
        Vec3 from = muzzle(c), to = aim();
        // Stopped by the first full block, then by any construct before that.
        BlockHitResult wall = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c));
        Vec3 end = wall.getType() == HitResult.Type.MISS ? to : wall.getLocation();
        Vec3 shield = Constructs.stopSpell(level, null, from, end, 0.05);
        if (shield != null) end = shield;
        LivingEntity hit = struck(level, from, end);
        if (target != null) {
            if (hit != target) blocked++;
            else blocked = 0;
            if (blocked >= GIVE_UP_BLOCKED) return false;
        }
        int second = Math.min(4, p / 20);
        Element tone = second < 3 ? Element.FIRE : Element.VOID;
        Vfx.beam(level, c.getId() * 31 + 7, from, end, tone, 0.3 + 0.1 * second, -1, 0);
        if (p % 10 == 0) {
            if (!c.spend(MANA_PER_SECOND[second] / 2)) return false;
            if (hit != null) strike(level, hit, second);
            level.playSound(null, end.x, end.y, end.z, second < 3 ? SoundEvents.BLAZE_SHOOT : SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1f, 0.7f);
        }
        return true;
    }

    /** What the beam strikes along {@code from → end}: its target if it reaches it, else (driven) the first creature in the way. */
    private @Nullable LivingEntity struck(ServerLevel level, Vec3 from, Vec3 end) {
        AABB span = new AABB(from, end).inflate(0.5);
        if (target != null) return target.getBoundingBox().inflate(0.3).clip(from, end).isPresent() ? target : null;
        UUID master = Familiars.masterOf(c);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, c, from, end, span, e -> e instanceof LivingEntity && e.isAlive()
                && e != c && e.getVehicle() != c && (c.isBerserk() || master == null || !Familiars.friendly(master, e)), 0.3f);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    /** Half a second's worth: physical then fire for the first three seconds (no knockback), void after. */
    private void strike(ServerLevel level, LivingEntity e, int second) {
        if (second < 3) {
            double physical = 5 * (second + 1), fire = 10 * (second + 1);
            Colossi.noKnockback(e, () -> {
                e.invulnerableTime = 0;
                e.hurtServer(level, c.damageSources().mobAttack(c), (float) physical);
                e.invulnerableTime = 0;
                e.hurtServer(level, c.damageSources().source(DamageTypes.FIREBALL, c), (float) fire);
            });
        } else {
            e.invulnerableTime = 0;
            e.hurtServer(level, c.damageSources().indirectMagic(c, c), 50);
        }
        Vec3 at = Targeting.center(e);
        level.sendParticles(second < 3 ? ParticleTypes.FLAME : ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.05);
    }

    void end() {
        if (!firing || !(c.level() instanceof ServerLevel level)) return;
        firing = false;
        Vfx.beamEnd(level, c.getId() * 31 + 7, muzzle(c), Element.FIRE);
    }
}
