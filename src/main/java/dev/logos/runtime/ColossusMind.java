package dev.logos.runtime;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus.Act;
import dev.logos.runtime.AlchemicalColossus.Hold;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
 * Everything it does with its whole body is a {@link Sequence} of animations timed on the server
 * ({@link Act}); while one runs it does nothing else. In a frenzy it turns on whatever is near.
 */
public final class ColossusMind extends Goal {
    static final int THINK_EVERY = 10;
    private static final double HYSTERESIS = 0.1;
    static final double WALK = 0.6, RUN = 1.0;
    /** Reach of its hands, measured from its edge to the target's. */
    static final double MELEE = 2.6;
    /** What it will not pick up, only strike: the ender dragon by default; packs may add more. */
    public static final TagKey<EntityType<?>> HEAVY = TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "colossus_heavy"));

    private final AlchemicalColossus c;
    private final List<Behavior> behaviors = new ArrayList<>();
    private @Nullable Behavior current;
    private @Nullable Sequence sequence;
    private final Map<Behavior, Double> lastScores = new LinkedHashMap<>();

    private @Nullable Player master;
    private @Nullable LivingEntity foe;
    private List<Mob> threats = List.of();
    private @Nullable Mob shooter;
    private long cannonReadyAt;
    private boolean wantsFlower;
    /** No second flower until then: a few minutes after the last one was offered. */
    private long flowerReadyAt;
    private boolean standing;
    private int repath;

    ColossusMind(AlchemicalColossus c) {
        this.c = c;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        behaviors.add(new Errand());
        behaviors.add(new Guard());
        behaviors.add(new Engage());
        behaviors.add(new ShieldMaster());
        behaviors.add(new Standby());
        behaviors.add(new OfferFlower());
        behaviors.add(new Rampage());
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
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        abortAll();
    }

    @Override
    public void tick() {
        long now = c.level().getGameTime();
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
        if (current == null || now % THINK_EVERY == 0) {
            sense();
            think();
        }
        if (current != null) current.tick(now);
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

    public String doingZh() {
        if (sequence != null) return sequence.zh;
        if (c.driver() != null) return "听从驾驶";
        return current == null ? "待命" : current.zh;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder("正在" + doingZh() + "｜");
        lastScores.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(e.getKey().zh).append(' ').append(String.format("%.2f", e.getValue())).append("  "));
        return sb.toString().strip();
    }

    // ---- sensing --------------------------------------------------------------------------------

    private void sense() {
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

    private void think() {
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
        if (best != current) {
            if (current != null) current.stop();
            current = best;
            if (current != null) current.start();
        }
    }

    // ---- moving -----------------------------------------------------------------------------------

    private void walkTo(Vec3 p, boolean run) {
        c.setRunning(run);
        if (--repath <= 0 || !c.getNavigation().isInProgress()) {
            repath = 10;
            c.getNavigation().moveTo(p.x, p.y, p.z, run ? RUN : WALK);
        }
    }

    private void halt() {
        c.getNavigation().stop();
        c.setRunning(false);
    }

    private void face(Entity e) {
        c.getLookControl().setLookAt(e, 20, 20);
        turnTo(e.position());
    }

    /** Turns its body toward {@code p}, a little at a time: it is heavy. */
    private void turnTo(Vec3 p) {
        Vec3 d = p.subtract(c.position());
        if (d.horizontalDistanceSqr() < 1e-4) return;
        float want = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float next = Mth.approachDegrees(c.getYRot(), want, 12f);
        c.setYRot(next);
        c.yBodyRot = next;
        c.yHeadRot = Mth.approachDegrees(c.yHeadRot, want, 20f);
    }

    /** Edge-to-edge distance, the way reach is measured. */
    private double gap(Entity e) {
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

    private boolean cannonReady() {
        return c.level().getGameTime() >= cannonReadyAt;
    }

    /** One step of fighting {@code f}: in reach, its hands; out of reach, the cannon if it can see; else closing in. */
    private void fight(LivingEntity f) {
        face(f);
        if (gap(f) <= MELEE) {
            halt();
            if (heavy(f)) runSeq(new Punch(f));
            else runSeq(new Grab(f));
            return;
        }
        if (cannonReady() && ColossusCannon.canSee(c, f)) {
            halt();
            runSeq(new CannonShot(f, null));
            return;
        }
        walkTo(f.position(), true);
    }

    private void runSeq(Sequence s) {
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
        if (sequence instanceof Flower f) f.taken(p);
    }

    /** {@code p} wants to ride: its master on the left shoulder, anyone else on the right. */
    boolean requestBoarding(Player p) {
        if (sequence != null || c.isBerserk() || c.isDormant() || c.distanceTo(p) > 5 || p.isPassenger()) return false;
        boolean isMaster = p.getUUID().equals(Familiars.masterOf(c));
        Hold seat = isMaster && c.held(Hold.SEAT_LEFT) == null ? Hold.SEAT_LEFT : c.held(Hold.SEAT_RIGHT) == null ? Hold.SEAT_RIGHT : null;
        if (seat == null) return false;
        runSeq(new Board(p, seat));
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
        runSeq(new PutDown(p, seat));
    }

    /** Whether it is lifting {@code p} down from a shoulder. */
    boolean puttingDown(Player p) {
        return sequence instanceof PutDown down && down.p == p;
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
        if (t != null && gap(t) <= MELEE + 0.5) runSeq(heavy(t) ? new Punch(t) : new Grab(t));
        else runSeq(new Punch(null));
    }

    /** Drive mode, right button down: raise the palm and gather the charge. */
    void driverChargeStart(Player rider) {
        if (sequence == null) runSeq(new CannonShot(null, rider));
    }

    /** Drive mode, right button up: loose the beam (if charged), for longer the longer it was held past full. */
    void driverChargeRelease(Player rider) {
        if (sequence instanceof CannonShot s) s.released();
    }

    // ---- where riders and the held are ----------------------------------------------------------------

    /**
     * Where {@code p} sits or hangs. On the client, the model's own bones (reported by the renderer)
     * give the exact spot; on the server — and before the model has been drawn — a close estimate
     * from the act and its progress.
     */
    static Vec3 holdPoint(AlchemicalColossus c, Hold h, Entity p) {
        if (h == Hold.SEAT_LEFT || h == Hold.SEAT_RIGHT) {
            Vec3 bone = c.drawnBone(h == Hold.SEAT_LEFT ? "attachment_seat_left" : "attachment_seat_right");
            if (bone != null) return bone.subtract(0, 0.6, 0); // a sitting rider's hips are about 0.6 above its feet
            return c.local(h == Hold.SEAT_LEFT ? -0.8 : 0.8, 2.55, -0.05);
        }
        Vec3 l = c.drawnBone("left_hand"), r = c.drawnBone("right_hand");
        Vec3 centre = p.position().add(0, p.getBbHeight() / 2, 0);
        Vec3 hand = l == null ? r : r == null ? l : l.distanceToSqr(centre) < r.distanceToSqr(centre) ? l : r;
        if (hand != null) return hand.subtract(0, p.getBbHeight() * 0.5, 0);
        double t = c.actTicks();
        Vec3 hold = c.local(0.45, 3.0, 1.3);
        Vec3 spot = switch (c.act()) {
            case GRAB_LIFT -> c.local(0.5, 1.2, 1.6).lerp(hold, Mth.clamp(t / 22, 0, 1));
            case GRAB_SLAM -> hold.lerp(c.local(0.45, 0.3, 2.0), Mth.clamp((t - 6) / 12, 0, 1));
            case PICKUP_LEFT, PICKUP_RIGHT -> c.local(0.5, 0.6, 1.4).lerp(c.local(c.act() == Act.PICKUP_LEFT ? -0.8 : 0.8, 2.55, -0.05), Mth.clamp((t - 33) / 46, 0, 1));
            case PUTDOWN_LEFT, PUTDOWN_RIGHT -> c.local(c.act() == Act.PUTDOWN_LEFT ? -0.8 : 0.8, 2.55, -0.05).lerp(c.local(0.5, 0.4, 1.5), Mth.clamp((t - 28) / 52, 0, 1));
            default -> hold;
        };
        return spot.subtract(0, p.getBbHeight() * 0.5, 0);
    }

    // ---- behaviours -------------------------------------------------------------------------------

    private abstract static class Behavior {
        final String zh;

        Behavior(String zh) {
            this.zh = zh;
        }

        abstract double score();

        void start() {}

        abstract void tick(long now);

        void stop() {}
    }

    /** gather: off to where it was sent. */
    private final class Errand extends Behavior {
        Errand() {
            super("前往");
        }

        @Override
        double score() {
            return !c.isBerserk() && Familiars.onErrand(c) ? 3 : 0;
        }

        @Override
        void tick(long now) {
            Vec3 p = Familiars.orderPoint(c);
            if (p != null) walkTo(p, c.distanceToSqr(p) > 20 * 20);
        }
    }

    /** At its master's side — or its post — standing watch, turning toward anything that moves nearby. */
    private final class Guard extends Behavior {
        Guard() {
            super("守望");
        }

        @Override
        double score() {
            return c.isBerserk() ? 0 : 0.2;
        }

        @Override
        void tick(long now) {
            Vec3 post = Familiars.onErrand(c) ? null : Familiars.orderPoint(c);
            Player m = master;
            Vec3 spot;
            if (post != null) spot = post;
            else if (m != null && m.getVehicle() == c) spot = null; // carrying its master: wherever it stands is beside them
            else if (m != null) {
                float yaw = m.getYRot() * Mth.DEG_TO_RAD;
                Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
                Vec3 r = new Vec3(-f.z, 0, f.x);
                spot = m.position().add(r.scale(2.4)).add(f.scale(-1.2));
            } else {
                halt();
                return;
            }
            double d = spot == null ? 0 : c.position().distanceTo(spot);
            if (d > 3.2) walkTo(spot, d > 12);
            else halt();
            // It watches whatever monster is about; else it looks where its master looks.
            Mob watch = c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(14), e -> e.isAlive() && e instanceof Enemy)
                    .stream().min((a, b) -> Double.compare(a.distanceToSqr(c), b.distanceToSqr(c))).orElse(null);
            if (watch != null) c.getLookControl().setLookAt(watch, 15, 15);
            else if (m != null) {
                Vec3 g = m.getEyePosition().add(m.getLookAngle().scale(10));
                c.getLookControl().setLookAt(g.x, g.y, g.z, 10, 10);
            }
            if (!c.getNavigation().isInProgress() && m != null && c.getRandom().nextInt(2400) == 0) maybeOfferFlower(m, 1);
        }
    }

    /** Its master's fight, an order, its hunt, or what goes for its master. */
    private final class Engage extends Behavior {
        Engage() {
            super("交战");
        }

        @Override
        double score() {
            return !c.isBerserk() && foe != null ? 0.8 : 0;
        }

        @Override
        void tick(long now) {
            if (foe != null) fight(foe);
        }
    }

    /** Something shoots at its master: step into the line of fire, facing it, and answer with the cannon. */
    private final class ShieldMaster extends Behavior {
        ShieldMaster() {
            super("挡箭");
        }

        @Override
        double score() {
            return !c.isBerserk() && master != null && shooter != null ? 0.95 : 0;
        }

        @Override
        void tick(long now) {
            Player m = master;
            Mob s = shooter;
            if (m == null || s == null) return;
            Vec3 dir = s.position().subtract(m.position()).multiply(1, 0, 1);
            if (dir.lengthSqr() < 1e-4) return;
            Vec3 spot = m.position().add(dir.normalize().scale(2.2));
            if (c.position().distanceTo(spot) > 1.2) {
                walkTo(spot, true);
                face(s);
            } else {
                halt();
                face(s);
                if (cannonReady() && ColossusCannon.canSee(c, s)) {
                    Familiars.order(c, s);
                    runSeq(new CannonShot(s, null));
                }
            }
        }
    }

    /** Its master asleep or away: it kneels down and powers low until they return. */
    private final class Standby extends Behavior {
        Standby() {
            super("待机");
        }

        @Override
        double score() {
            if (c.isBerserk() || foe != null) return 0;
            if (master == null && Familiars.masterOf(c) != null) return 0.9;
            return master != null && master.isSleeping() ? 0.9 : 0;
        }

        @Override
        void start() {
            halt();
            standing = false;
            c.play(Act.POWER_DOWN);
        }

        @Override
        void tick(long now) {
            halt();
        }

        @Override
        void stop() {
            if (c.act() == Act.DORMANT || c.act() == Act.POWER_DOWN) c.play(Act.POWER_UP);
        }
    }

    /** It wants to give its master a flower: walk over and kneel. */
    private final class OfferFlower extends Behavior {
        OfferFlower() {
            super("献花");
        }

        @Override
        double score() {
            return wantsFlower && !c.isBerserk() && master != null && master.getVehicle() != c && foe == null && threats.isEmpty() && master.distanceTo(c) < 24 ? 0.6 : 0;
        }

        @Override
        void tick(long now) {
            Player m = master;
            if (m == null) return;
            if (gap(m) > 1.6) {
                walkTo(m.position(), false);
                return;
            }
            halt();
            face(m);
            wantsFlower = false;
            // Five to eight minutes before the next.
            flowerReadyAt = c.level().getGameTime() + 6000 + c.getRandom().nextInt(3600);
            runSeq(new Flower(m));
        }
    }

    /** In a frenzy: after whatever is near, master included. */
    private final class Rampage extends Behavior {
        Rampage() {
            super("暴走");
        }

        @Override
        double score() {
            return c.isBerserk() ? 1 : 0;
        }

        @Override
        void tick(long now) {
            if (foe != null) fight(foe);
            else halt();
        }
    }

    // ---- sequences --------------------------------------------------------------------------------

    /** Something it does with its whole body, timed act by act. */
    abstract class Sequence {
        final String zh;
        int t;

        Sequence(String zh) {
            this.zh = zh;
        }

        void begin() {}

        /** One tick; false when done. */
        abstract boolean tick();

        void abort() {}
    }

    /** A sweeping blow: everything in front of it is struck hard and thrown back. */
    private final class Punch extends Sequence {
        private final @Nullable LivingEntity at;

        Punch(@Nullable LivingEntity at) {
            super("挥拳");
            this.at = at;
        }

        @Override
        void begin() {
            c.play(Act.PUNCH);
        }

        @Override
        boolean tick() {
            if (at != null && t < 14) face(at);
            if (t == 15) strike();
            return ++t < Act.PUNCH.ticks;
        }

        private void strike() {
            if (!(c.level() instanceof ServerLevel level)) return;
            Vec3 centre = c.local(0.3, 1.2, 2.2);
            UUID master = Familiars.masterOf(c);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(3), LivingEntity::isAlive)) {
                if (e == c || e.getVehicle() == c) continue;
                if (!c.isBerserk() && master != null && (Familiars.friendly(master, e) || e instanceof Player && !(e == foe))) continue;
                e.invulnerableTime = 0;
                c.deal(level, e, DamageTypes.MOB_ATTACK, 50);
                Vec3 away = e.position().subtract(c.position()).multiply(1, 0, 1);
                if (away.lengthSqr() > 1e-4) {
                    away = away.normalize();
                    e.knockback(2.2, -away.x, -away.z);
                    e.setDeltaMovement(e.getDeltaMovement().add(0, 0.35, 0));
                    e.hurtMarked = true;
                }
            }
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, centre.x, centre.y, centre.z, 3, 1.2, 0.3, 1.2, 0);
            level.sendParticles(ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z, 1, 0, 0, 0, 0);
            level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.6f, 0.55f);
            level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.4f, 0.6f);
        }
    }

    /**
     * Picks a foe up, looks at it, then finishes it (at or under the finishing line, never a boss) or slams it
     * into the ground. In a frenzy, if what it finished was its own maker, remorse takes it a moment.
     */
    private final class Grab extends Sequence {
        private final LivingEntity victim;
        private Act phase = Act.GRAB_REACH;
        private boolean caught, wasNoAi;
        private int phaseAt;
        private @Nullable Vec3 diedAt;

        Grab(LivingEntity victim) {
            super("抓取");
            this.victim = victim;
        }

        private void phase(Act a) {
            phase = a;
            phaseAt = t;
            c.play(a);
        }

        @Override
        void begin() {
            phase(Act.GRAB_REACH);
        }

        @Override
        boolean tick() {
            int p = t - phaseAt;
            switch (phase) {
                case GRAB_REACH -> {
                    face(victim);
                    if (p == 18) {
                        if (victim.isAlive() && gap(victim) <= MELEE + 1 && !victim.isPassenger() && victim.getPassengers().isEmpty()) {
                            caught = true;
                            if (victim instanceof Mob m) {
                                wasNoAi = m.isNoAi();
                                m.setNoAi(true);
                            }
                            c.setHold(Hold.GRIP, victim);
                            Colossi.holding(victim);
                        }
                    }
                    if (p >= Act.GRAB_REACH.ticks) phase(caught ? Act.GRAB_LIFT : Act.GRAB_MISS);
                }
                case GRAB_LIFT -> {
                    if (!victim.isAlive()) phase(Act.GRAB_MISS);
                    else if (p >= Act.GRAB_LIFT.ticks) phase(Act.GRAB_HOLD);
                }
                case GRAB_HOLD -> {
                    // A moment's look, then the verdict.
                    if (!victim.isAlive()) phase(Act.GRAB_MISS);
                    else if (p >= 20) phase(finishable(victim) ? Act.GRAB_EXECUTE : Act.GRAB_SLAM);
                }
                case GRAB_EXECUTE -> {
                    if (p == 8 && c.level() instanceof ServerLevel level && victim.isAlive()) {
                        victim.invulnerableTime = 0;
                        c.deal(level, victim, DamageTypes.MOB_ATTACK, Float.MAX_VALUE);
                        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.HOSTILE, 1.4f, 0.5f);
                    }
                    if (p == 17) letGo(null);
                    if (p >= Act.GRAB_EXECUTE.ticks) afterwards();
                }
                case GRAB_SLAM -> {
                    if (p == 18) slam();
                    if (p == 20) letGo(groundBefore());
                    if (p >= Act.GRAB_SLAM.ticks) afterwards();
                }
                case GRAB_MISS -> {
                    if (caught) letGo(null);
                    if (p >= Act.GRAB_MISS.ticks) return false;
                }
                case REMORSE -> {
                    // Frozen, looking down at where its maker fell.
                    if (diedAt != null) turnTo(diedAt);
                    halt();
                    if (p >= Act.REMORSE.ticks) return false;
                }
                default -> {
                    return false;
                }
            }
            t++;
            return true;
        }

        private Vec3 groundBefore() {
            Vec3 p = c.local(0.4, 0, 2.0);
            BlockPos b = BlockPos.containing(p);
            for (int i = 0; i < 4 && !c.level().getBlockState(b).getCollisionShape(c.level(), b).isEmpty(); i++) b = b.above();
            return new Vec3(p.x, b.getY(), p.z);
        }

        /** Into the ground: heavy fall damage (feather falling and the like soften it) and a spray of earth. */
        private void slam() {
            if (!(c.level() instanceof ServerLevel level)) return;
            Vec3 at = groundBefore();
            if (victim.isAlive()) {
                victim.invulnerableTime = 0;
                c.deal(level, victim, DamageTypes.FALL, 80);
            }
            BlockState ground = level.getBlockState(BlockPos.containing(at).below());
            if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.2, at.z, 90, 1.2, 0.2, 1.2, 0.35);
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 2, 0.5, 0.1, 0.5, 0);
            level.sendParticles(ParticleTypes.DUST_PLUME, at.x, at.y + 0.1, at.z, 30, 1.0, 0.1, 1.0, 0.05);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2f, 0.7f);
        }

        private void letGo(@Nullable Vec3 at) {
            if (!caught) return;
            caught = false;
            if (!victim.isAlive()) diedAt = victim.position();
            c.release(victim, at);
            if (victim instanceof Mob m) m.setNoAi(wasNoAi);
        }

        /** Hand back down — or, in a frenzy, remorse if it was its own maker that died. */
        private void afterwards() {
            UUID master = Familiars.masterOf(c);
            if (c.isBerserk() && victim instanceof Player p && !p.isAlive() && master != null && master.equals(p.getUUID())) {
                if (diedAt == null) diedAt = p.position();
                phase(Act.REMORSE);
            } else {
                phase(Act.GRAB_MISS);
            }
        }

        @Override
        void abort() {
            letGo(null);
        }
    }

    /** The palm cannon: raise, gather (about three seconds), fire a beam it holds on its target, recover. */
    private final class CannonShot extends Sequence {
        private final @Nullable LivingEntity target;
        private final @Nullable Player rider;
        private final ColossusCannon beam;
        private Act phase = Act.CANNON_PREPARE;
        private int phaseAt;
        private boolean releasedEarly, released;
        private int releasedAt = -1;

        CannonShot(@Nullable LivingEntity target, @Nullable Player rider) {
            super("掌心炮");
            this.target = target;
            this.rider = rider;
            this.beam = new ColossusCannon(c, target, rider);
        }

        void released() {
            released = true;
            releasedAt = t;
            // Let go before the charge is full, it lowers its palm; once full (and held), it fires.
            if (phase == Act.CANNON_PREPARE || phase == Act.CANNON_CHARGE && t - phaseAt < Act.CANNON_CHARGE.ticks) releasedEarly = true;
        }

        private void phase(Act a) {
            phase = a;
            phaseAt = t;
            c.play(a);
        }

        @Override
        void begin() {
            phase(Act.CANNON_PREPARE);
            c.level().playSound(null, c.getX(), c.getY() + 2, c.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.5f, 0.6f);
        }

        @Override
        boolean tick() {
            int p = t - phaseAt;
            if (target != null && phase != Act.CANNON_RECOVER) face(target);
            // Its arm follows what it shoots at until the beam is spent.
            c.aimAt(phase == Act.CANNON_RECOVER ? null : beam.aim());
            halt();
            switch (phase) {
                case CANNON_PREPARE, CANNON_CHARGE -> {
                    beam.gather(t);
                    if (releasedEarly) {
                        phase(Act.CANNON_RECOVER);
                    } else if (target != null && !target.isAlive()) {
                        phase(Act.CANNON_RECOVER);
                    } else if (phase == Act.CANNON_PREPARE && p >= Act.CANNON_PREPARE.ticks) {
                        phase(Act.CANNON_CHARGE);
                    } else if (phase == Act.CANNON_CHARGE && p >= Act.CANNON_CHARGE.ticks && (rider == null || released)) {
                        phase(Act.CANNON_FIRE);
                        beam.start();
                    } else if (phase == Act.CANNON_CHARGE && p >= Act.CANNON_CHARGE.ticks) {
                        beam.gather(t); // fully charged, held: it waits for the release
                    }
                }
                case CANNON_FIRE -> {
                    // A rider's beam lasts longer the longer they held it past full charge (one to five seconds).
                    int limit = rider == null ? ColossusCannon.MAX_TICKS
                            : Mth.clamp(20 + Math.max(0, (releasedAt < 0 ? t : releasedAt) - (Act.CANNON_PREPARE.ticks + Act.CANNON_CHARGE.ticks)) * 2, 20, ColossusCannon.MAX_TICKS);
                    // The act began last tick, so this is the beam's first tick when p is 1: count it from 0, so
                    // the first strike lands as the beam appears rather than half a second later.
                    if (!beam.fire(p - 1, limit)) {
                        beam.end();
                        phase(Act.CANNON_RECOVER);
                    }
                }
                case CANNON_RECOVER -> {
                    if (p >= Act.CANNON_RECOVER.ticks) {
                        cannonReadyAt = c.level().getGameTime() + 60;
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
            t++;
            return true;
        }

        @Override
        void abort() {
            beam.end();
            c.aimAt(null);
        }
    }

    /** Lifts a player onto a shoulder: reach down, take them in hand, set them on the seat. */
    private final class Board extends Sequence {
        private final Player p;
        private final Hold seat;

        Board(Player p, Hold seat) {
            super("扶上肩");
            this.p = p;
            this.seat = seat;
        }

        @Override
        void begin() {
            c.play(seat == Hold.SEAT_LEFT ? Act.PICKUP_LEFT : Act.PICKUP_RIGHT);
        }

        @Override
        boolean tick() {
            if (t < 30) face(p);
            halt();
            if (t == 33) {
                if (!p.isAlive() || c.distanceTo(p) > 6) return false;
                c.setHold(Hold.GRIP, p);
                Colossi.holding(p);
            }
            if (t == 79 && c.held(Hold.GRIP) == p) {
                c.setHold(seat, p);
                Colossi.stopHolding(p);
            }
            return ++t < Act.PICKUP_LEFT.ticks;
        }

        @Override
        void abort() {
            if (c.held(Hold.GRIP) == p) c.release(p, null);
        }
    }

    /** Lifts a rider off its shoulder and sets them gently on the ground before it. */
    private final class PutDown extends Sequence {
        private final Player p;
        private final Hold seat;

        PutDown(Player p, Hold seat) {
            super("放下");
            this.p = p;
            this.seat = seat;
        }

        @Override
        void begin() {
            c.play(seat == Hold.SEAT_LEFT ? Act.PUTDOWN_LEFT : Act.PUTDOWN_RIGHT);
        }

        @Override
        boolean tick() {
            halt();
            if (t == 28) {
                c.setHold(Hold.GRIP, p);
                Colossi.holding(p);
            }
            if (t == 80) c.release(p, c.local(0.5, 0, 1.6));
            return ++t < Act.PUTDOWN_LEFT.ticks;
        }

        @Override
        void abort() {
            if (p.getVehicle() == c) c.release(p, c.getDismountLocationForPassenger(p));
        }
    }

    /** Kneels and holds out a 巨像之心; taken, it rises; ignored a while, it takes it back. */
    private final class Flower extends Sequence {
        private final Player to;
        private boolean taken;

        Flower(Player to) {
            super("献花");
            this.to = to;
        }

        @Override
        void begin() {
            c.play(Act.FLOWER_OFFER);
        }

        void taken(Player p) {
            if (taken || c.act() != Act.FLOWER_HOLD) return;
            taken = true;
            if (!p.getInventory().add(new ItemStack(LogosMod.COLOSSUS_HEART.get()))) p.drop(new ItemStack(LogosMod.COLOSSUS_HEART.get()), false);
            if (c.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.HEART, c.getX(), c.getY() + 2.6, c.getZ(), 4, 0.5, 0.3, 0.5, 0);
                level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1f, 0.8f);
            }
            c.play(Act.FLOWER_RETRACT);
            t = 1000;
        }

        @Override
        boolean tick() {
            halt();
            face(to);
            if (t < 1000) {
                if (t == Act.FLOWER_OFFER.ticks) c.play(Act.FLOWER_HOLD);
                if (t >= Act.FLOWER_OFFER.ticks + 300) {
                    c.play(Act.FLOWER_RETRACT);
                    t = 1000;
                }
            } else if (t >= 1000 + Act.FLOWER_RETRACT.ticks) {
                return false;
            }
            t++;
            return true;
        }
    }
}
