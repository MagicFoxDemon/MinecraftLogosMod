package dev.logos.runtime;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus.Act;
import dev.logos.runtime.AlchemicalColossus.Hold;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * The colossus's mind: the same kind of utility AI as the lifeform's, but slower and steadier. It
 * stands at its master's side and watches; steps into the line of fire of whatever shoots at them;
 * fights with its palm cannon at range and its hands up close — almost anything is picked up, looked
 * at, and either finished or slammed into the ground (a boss is never finished, only slammed), and
 * what is too big to hold (the ender dragon) gets a sweeping blow; powers down
 * while its master sleeps or is away; and now and then kneels to offer them a flower.
 * <p>
 * What it chooses between is in {@link ColossusBehavior}. Everything it does with its whole body is
 * a {@link ColossusSequence} of animations timed on the server ({@link Act}); while one runs it does
 * nothing else. In a frenzy it turns on whatever is near.
 */
public final class ColossusMind extends UtilityMind<AlchemicalColossus> {
    static final double WALK = 0.6, RUN = 1.0;
    /** Reach of its hands, measured from its edge to the target's. */
    static final double MELEE = 2.6;
    /**
     * Choosing between a sweeping blow and a grab: a blow is worth one for each lesser foe it would
     * catch (a strong one barely feels it); a grab is worth this much for a lesser foe and more for a
     * strong one. So it swings into a crowd of three lesser foes, and takes a strong foe in hand unless
     * a crowd of four is about it.
     */
    static final double SWEEP_STRONG = 0.3, GRAB_LESSER = 2.5, GRAB_STRONG = 3.5;
    /** What it treads on as it walks: hostile things under this tall and with under this much health. */
    static final double STOMP_HEIGHT = 1, STOMP_HEALTH = 25;
    /** What it will not pick up, only strike: the ender dragon by default; packs may add more. */
    public static final TagKey<EntityType<?>> HEAVY = TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "colossus_heavy"));

    private final AlchemicalColossus c;
    private @Nullable ColossusSequence sequence;

    /** What it last sensed, and what it has in mind: its behaviours and sequences read and set these. */
    @Nullable Player master;
    @Nullable LivingEntity foe;
    List<Mob> threats = List.of();
    @Nullable Mob shooter;
    /** What has struck it and is still about: it answers that once nothing more pressing is asked of it. */
    @Nullable LivingEntity assailant;
    private int assailantAt;
    long cannonReadyAt;
    boolean wantsFlower;
    /** No second flower until then: a few minutes after the last one was offered. */
    long flowerReadyAt;
    boolean standing;
    private int repath;
    /** Where its feet were last tick, to tell whether it is walking (driven, its rider's client moves it). */
    private @Nullable Vec3 lastFeet;

    ColossusMind(AlchemicalColossus c) {
        super(c, 0);
        this.c = c;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        behaviors.add(new ColossusBehavior.Errand(this));
        behaviors.add(new ColossusBehavior.Guard(this));
        behaviors.add(new ColossusBehavior.Engage(this));
        behaviors.add(new ColossusBehavior.ShieldMaster(this));
        behaviors.add(new ColossusBehavior.Standby(this));
        behaviors.add(new ColossusBehavior.OfferFlower(this));
        behaviors.add(new ColossusBehavior.Rampage(this));
        behaviors.add(new ColossusBehavior.SelfDefense(this));
    }

    // ---- goal ---------------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        return !c.isDormant() && c.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        abortAll();
    }

    @Override
    public void tick() {
        if (sequence != null) {
            if (!sequence.tick()) sequence = null;
            return;
        }
        // Driven, it does only what its rider asks; the rider's keys move it.
        if (c.driver() != null) {
            c.setRunning(c.driver().isSprinting());
            if (current != null) {
                current.stop();
                current = null;
            }
            return;
        }
        thinkAndAct();
    }

    /** Drops whatever it is doing and lets go of anything in its hand (riders stay seated). */
    void abortAll() {
        if (sequence != null) sequence.abort();
        sequence = null;
        if (current != null) current.stop();
        current = null;
        Entity gripped = c.held(Hold.GRIP);
        if (gripped != null) c.release(gripped, null);
        c.getNavigation().stop();
        c.setRunning(false);
    }

    /** Whether a sequence is running: it holds the body, act after act. */
    boolean busy() {
        return sequence != null;
    }

    /** Whether its body is free for a flinch: nothing playing. */
    boolean idleBody() {
        return sequence == null && c.act() == Act.NONE;
    }

    /** How hard it is working, for its upkeep: 1 standing, 2 walking, 4 running, a quarter in standby. */
    double activityFactor() {
        if (c.act() == Act.DORMANT) return 0.25;
        if (!c.getNavigation().isInProgress() && c.driver() == null) return 1;
        return c.isRunning() ? 4 : 2;
    }

    @Override
    public String doing() {
        if (sequence != null) return sequence.id;
        if (c.driver() != null) return "driven";
        return current == null ? "awaiting" : current.id;
    }

    // ---- sensing --------------------------------------------------------------------------------

    @Override
    protected void sense() {
        UUID id = Familiars.masterOf(c);
        master = id == null ? null : c.level().getPlayerByUUID(id);
        if (c.isBerserk()) {
            foe = prey();
            threats = List.of();
            shooter = null;
            return;
        }
        LivingEntity t = Familiars.orderedTarget(c);
        foe = t != null && t.isAlive() && t.level() == c.level() ? t : null;
        Player m = master;
        threats = m == null ? List.of() : c.level().getEntitiesOfClass(Mob.class, m.getBoundingBox().inflate(20),
                e -> e.isAlive() && e.getTarget() == m && !Familiars.friendly(m.getUUID(), e));
        shooter = null;
        double best = Double.MAX_VALUE;
        for (Mob e : threats) {
            if (!(e instanceof RangedAttackMob) && !(e instanceof CrossbowAttackMob)) continue;
            double d = e.distanceToSqr(m);
            if (d < best) {
                best = d;
                shooter = e;
            }
        }
        // Whatever is going for its master is its foe too.
        if (foe == null && !threats.isEmpty()) {
            Mob nearest = threats.stream().min((a, b) -> Double.compare(a.distanceToSqr(c), b.distanceToSqr(c))).orElse(null);
            if (nearest != null && Familiars.order(c, nearest)) foe = nearest;
        }
        rememberAssailant();
    }

    /**
     * Keeps track of what struck it — a creature, never a player, and not its own side — for half a
     * minute after its last blow, or until it dies or wanders off. (Vanilla forgets after five seconds.)
     */
    private void rememberAssailant() {
        LivingEntity by = c.getLastHurtByMob();
        UUID id = Familiars.masterOf(c);
        if (by != null && by.isAlive() && !(by instanceof Player) && (id == null || !Familiars.friendly(id, by))) {
            assailant = by;
            assailantAt = c.getLastHurtByMobTimestamp();
        }
        LivingEntity a = assailant;
        if (a != null && (!a.isAlive() || a.level() != c.level() || a.distanceTo(c) > 32 || c.tickCount - assailantAt > 600)) assailant = null;
    }

    /** In a frenzy: the nearest living thing that is a player or a monster, or whatever last struck it. */
    private @Nullable LivingEntity prey() {
        LivingEntity hurtBy = c.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy.distanceTo(c) < 32 && !(hurtBy instanceof Player p && (p.isCreative() || p.isSpectator()))) return hurtBy;
        LivingEntity best = null;
        double bestD = 24 * 24;
        for (LivingEntity e : c.level().getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(24), LivingEntity::isAlive)) {
            if (e == c || e.isPassengerOfSameVehicle(c) || e.getVehicle() == c) continue;
            boolean prey = e instanceof Player p ? !p.isCreative() && !p.isSpectator() : e instanceof Enemy;
            if (!prey) continue;
            double d = e.distanceToSqr(c);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ---- moving -----------------------------------------------------------------------------------

    void walkTo(Vec3 p, boolean run) {
        c.setRunning(run);
        if (--repath <= 0 || !c.getNavigation().isInProgress()) {
            repath = 10;
            c.getNavigation().moveTo(p.x, p.y, p.z, run ? RUN : WALK);
        }
    }

    void halt() {
        c.getNavigation().stop();
        c.setRunning(false);
    }

    void face(Entity e) {
        c.getLookControl().setLookAt(e, 20, 20);
        turnTo(e.position());
    }

    /**
     * Turns so its right arm — the one that grabs and strikes — lines up on {@code e}, rather than its
     * middle: it faces a point as far to the other side of {@code e} as its shoulder is from its middle.
     */
    void faceWithArm(Entity e) {
        c.getLookControl().setLookAt(e, 20, 20);
        Vec3 d = e.position().subtract(c.position()).multiply(1, 0, 1);
        if (d.lengthSqr() < 1e-6) return;
        Vec3 f = d.normalize(), r = new Vec3(-f.z, 0, f.x);
        turnTo(e.position().subtract(r.scale(ColossusArms.SHOULDER.x)));
    }

    /** Turns its body toward {@code p}, a little at a time: it is heavy. */
    void turnTo(Vec3 p) {
        Vec3 d = p.subtract(c.position());
        if (d.horizontalDistanceSqr() < 1e-4) return;
        float want = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float next = Mth.approachDegrees(c.getYRot(), want, 12f);
        c.setYRot(next);
        c.yBodyRot = next;
        c.yHeadRot = Mth.approachDegrees(c.yHeadRot, want, 20f);
    }

    /** Edge-to-edge distance, the way reach is measured. */
    double gap(Entity e) {
        return Math.max(0, c.distanceTo(e) - (c.getBbWidth() + e.getBbWidth()) / 2);
    }

    // ---- combat decisions -------------------------------------------------------------------------

    /** Too big to pick up (the {@code #logos:colossus_heavy} tag: the ender dragon): it gets a blow instead. */
    public static boolean heavy(LivingEntity e) {
        return e.getType().builtInRegistryHolder().is(HEAVY);
    }

    /** Below the finishing line: at most 35% health, or health and armour together under 60. A boss is never finished, only slammed. */
    public static boolean finishable(LivingEntity e) {
        if (e.getType().builtInRegistryHolder().is(Tags.EntityTypes.BOSSES)) return false;
        return e.getHealth() <= e.getMaxHealth() * 0.35 || e.getHealth() + e.getArmorValue() < 60;
    }

    boolean cannonReady() {
        return c.level().getGameTime() >= cannonReadyAt;
    }

    /** One step of fighting {@code f}: in reach, its hands; out of reach, the cannon if it can see; else closing in. */
    void fight(LivingEntity f) {
        face(f);
        if (gap(f) <= MELEE) {
            // In reach, it acts at once. Its arm (under two blocks long) reaches toward the foe as far as
            // it goes (ColossusArmIk), but whether the grab or the blow lands does not hang on it.
            halt();
            runSeq(melee(f));
            return;
        }
        if (cannonReady() && ColossusCannon.canSee(c, f)) {
            halt();
            runSeq(new ColossusSequence.CannonShot(this, f, null));
            return;
        }
        walkTo(f.position(), true);
    }

    /** Its move against {@code f}, in reach: a sweeping blow into a crowd of lesser foes (or at what it cannot lift), else a grab. */
    private ColossusSequence melee(LivingEntity f) {
        if (heavy(f)) return new ColossusSequence.Punch(this, f);
        Vec3 toward = f.position().subtract(c.position()).multiply(1, 0, 1);
        toward = toward.lengthSqr() < 1e-4 ? Vec3.ZERO : toward.normalize();
        double sweep = 0;
        for (LivingEntity e : inSweep(c.position().add(toward.scale(2.2)).add(0, 1.2, 0))) {
            if (e == f || hostile(e)) sweep += strong(e) ? SWEEP_STRONG : 1;
        }
        return sweep > (strong(f) ? GRAB_STRONG : GRAB_LESSER) ? new ColossusSequence.Punch(this, f) : new ColossusSequence.Grab(this, f);
    }

    /** A strong foe: a boss, or health and armour together at or past the finishing line's 60. */
    public static boolean strong(LivingEntity e) {
        return e.getType().builtInRegistryHolder().is(Tags.EntityTypes.BOSSES) || e.getMaxHealth() + e.getArmorValue() >= 60;
    }

    /**
     * Walking — on its own or driven — it treads on the small and hostile at its feet: anything inside
     * its own bounds, about level with its feet, under a block tall and under 25 health (a small slime,
     * a spider, a cave spider, an endermite) is crushed.
     */
    void stomp() {
        Vec3 at = c.position(), was = lastFeet;
        lastFeet = at;
        if (was == null || at.subtract(was).horizontalDistanceSqr() < 0.02 * 0.02 || !(c.level() instanceof ServerLevel level)) return;
        UUID id = Familiars.masterOf(c);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox(), e -> e.isAlive() && e != c && e.getVehicle() != c
                && e.getBbHeight() < STOMP_HEIGHT && e.getHealth() < STOMP_HEALTH && Math.abs(e.getY() - c.getY()) <= 0.5 && hostile(e)
                && (c.isBerserk() || id == null || !Familiars.friendly(id, e)))) {
            e.invulnerableTime = 0;
            c.deal(level, e, DamageTypes.MOB_ATTACK, Float.MAX_VALUE);
            BlockState ground = level.getBlockState(e.blockPosition().below());
            if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), e.getX(), e.getY() + 0.1, e.getZ(), 16, 0.3, 0.05, 0.3, 0.15);
            level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.MACE_SMASH_GROUND, SoundSource.HOSTILE, 0.7f, 1.3f);
        }
    }

    /** Something fighting: a monster, or whatever is after it or its master (in a frenzy, anything it would strike). */
    private boolean hostile(LivingEntity e) {
        if (c.isBerserk() || e instanceof Enemy) return true;
        return e instanceof Mob m && m.getTarget() != null && (m.getTarget() == c || master != null && m.getTarget() == master);
    }

    /** Whom a sweeping blow centred on {@code centre} would strike: everything there but itself, what it carries, and — sane — its own side. */
    List<LivingEntity> inSweep(Vec3 centre) {
        UUID id = Familiars.masterOf(c);
        return c.level().getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(3), e -> e.isAlive() && e != c && e.getVehicle() != c
                && (c.isBerserk() || id == null || !Familiars.friendly(id, e) && !(e instanceof Player && e != foe)));
    }

    void runSeq(ColossusSequence s) {
        if (current != null) current.stop();
        current = null;
        sequence = s;
        s.begin();
    }

    // ---- requests from outside ----------------------------------------------------------------------

    /** Fed (or idle): perhaps it decides to offer its master a flower — not again within minutes of the last. */
    public void maybeOfferFlower(Player by, double chance) {
        if (!c.isBerserk() && c.level().getGameTime() >= flowerReadyAt && c.getRandom().nextDouble() < chance) wantsFlower = true;
    }

    public boolean wantsFlower() {
        return wantsFlower;
    }

    /** Someone took the flower it holds out. */
    void takeFlower(Player p) {
        if (sequence instanceof ColossusSequence.Flower f) f.taken(p);
    }

    /** {@code p} wants to ride: its master on the left shoulder, anyone else on the right. */
    boolean requestBoarding(Player p) {
        if (sequence != null || c.isBerserk() || c.isDormant() || c.distanceTo(p) > 5 || p.isPassenger()) return false;
        boolean isMaster = p.getUUID().equals(Familiars.masterOf(c));
        Hold seat = isMaster && c.held(Hold.SEAT_LEFT) == null ? Hold.SEAT_LEFT : c.held(Hold.SEAT_RIGHT) == null ? Hold.SEAT_RIGHT : null;
        if (seat == null) return false;
        runSeq(new ColossusSequence.Board(this, p, seat));
        return true;
    }

    /** A seated rider wants down: it lifts them off and sets them on the ground (at once if it is busy). */
    void requestPutDown(Player p) {
        Hold seat = c.holdOf(p);
        if (seat != Hold.SEAT_LEFT && seat != Hold.SEAT_RIGHT) return;
        if (sequence != null || c.isBerserk() || c.isDormant()) {
            c.release(p, c.getDismountLocationForPassenger(p));
            return;
        }
        runSeq(new ColossusSequence.PutDown(this, p, seat));
    }

    /** Whether it is lifting {@code p} down from a shoulder. */
    boolean puttingDown(Player p) {
        return sequence instanceof ColossusSequence.PutDown down && down.p == p;
    }

    /** A seated rider who asked twice jumps straight off: it stops lifting them down and lets them go at once. */
    void jumpOff(Player p) {
        if (puttingDown(p)) {
            sequence = null;
            c.play(Act.NONE);
        }
        if (p.getVehicle() == c) c.release(p, null);
    }

    /** Drive mode, left click: a blow (or a grab) at what its rider looks at. */
    void driverStrike(Player rider) {
        if (sequence != null) return;
        LivingEntity t = ColossusCannon.lookTarget(c, rider, 6);
        if (t != null && gap(t) <= MELEE + 0.5) runSeq(heavy(t) ? new ColossusSequence.Punch(this, t) : new ColossusSequence.Grab(this, t));
        else runSeq(new ColossusSequence.Punch(this, null));
    }

    /** Drive mode, right button down: raise the palm and gather the charge. */
    void driverChargeStart(Player rider) {
        if (sequence == null) runSeq(new ColossusSequence.CannonShot(this, null, rider));
    }

    /** Drive mode, right button up: loose the beam (if charged), for longer the longer it was held past full. */
    void driverChargeRelease(Player rider) {
        if (sequence instanceof ColossusSequence.CannonShot s) s.released();
    }
}
