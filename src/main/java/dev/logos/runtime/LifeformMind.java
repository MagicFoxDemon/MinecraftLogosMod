package dev.logos.runtime;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Intent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A mana lifeform's mind: a small utility AI. Every half second it senses its surroundings, scores
 * each behaviour it knows (0 = pointless, about 1 = pressing) and does the best one; the one it is
 * already doing gets a little extra, so it does not flicker between two.
 * <p>
 * Its master steers it with the usual familiar nodes — {@code sic}, {@code gather}, {@code stance}
 * (with {@code :hunt} and {@code :role}) — and everything else is its own: it keeps at its master's
 * shoulder, fights at its attack's reach, heals and shields its master with pages filed under HEAL
 * and WARD, steps between its master and what is hunting them, drinks free mana, shies away when
 * hurt, pokes about when its master stands still and settles down when they sleep. Its temperament
 * (bravery, curiosity, loyalty) and role scale the scores, so no two behave quite alike.
 * <p>
 * It moves by hovering: straight at a point in the air when the way is clear, by path when not, and
 * never lower than a block over the ground.
 */
public final class LifeformMind extends Goal {
    static final int THINK_EVERY = 10, COMMIT = 20;
    private static final double HYSTERESIS = 0.1;

    /** Little visible signs of how it is: no words, just mana. */
    public enum Emote { HAPPY, ALERT, HUNGRY, CURIOUS }

    private final ManaLifeform lf;
    private final List<Behavior> behaviors = new ArrayList<>();
    private @Nullable Behavior current;
    private long committedUntil;
    private final Map<Behavior, Double> lastScores = new LinkedHashMap<>();

    // Senses, refreshed each think.
    private @Nullable Player master;
    private double masterHealth = 1;
    private List<Mob> threats = List.of();
    private @Nullable LivingEntity foe;
    private boolean ordered;
    private @Nullable FreeManaEntity orb;
    private @Nullable Vec3 masterLast;
    private int masterStill;

    // Casting: the cast animation gathers, lets go at its release tick, then settles.
    private int castTimer, castCooldown, castSlot = -1, comboSlot = -1;
    private @Nullable Entity castAt;
    private long supportReadyAt, wardReadyAt, nextHunger;
    private int repath;

    LifeformMind(ManaLifeform lf) {
        this.lf = lf;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        behaviors.add(new Errand());
        behaviors.add(new Escort());
        behaviors.add(new Engage());
        behaviors.add(new Support());
        behaviors.add(new Protect());
        behaviors.add(new Forage());
        behaviors.add(new Retreat());
        behaviors.add(new Curious());
        behaviors.add(new Rest());
    }

    // ---- the goal ---------------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        return !lf.isDormant();
    }

    @Override
    public boolean canContinueToUse() {
        return !lf.isDormant();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        current = null;
    }

    @Override
    public void stop() {
        if (current != null) current.stop();
        current = null;
        endCast();
        lf.getNavigation().stop();
    }

    @Override
    public void tick() {
        long now = lf.level().getGameTime();
        if (current == null || now % THINK_EVERY == 0) {
            sense();
            think(now);
        }
        if (current != null) current.tick(now);
        tickCast();
        if (lf.manaFraction() < 0.15 && now >= nextHunger) {
            nextHunger = now + 80;
            emote(lf, Emote.HUNGRY);
        }
    }

    // ---- sensing and choosing ---------------------------------------------------------------------

    private void sense() {
        UUID id = Familiars.masterOf(lf);
        master = id == null ? null : lf.level().getPlayerByUUID(id);
        masterHealth = master == null ? 1 : master.getHealth() / master.getMaxHealth();
        LivingEntity t = Familiars.orderedTarget(lf);
        foe = t != null && t.isAlive() && t.level() == lf.level() ? t : null;
        ordered = foe != null && !Familiars.isHunting(lf);
        Player m = master;
        threats = m == null ? List.of() : lf.level().getEntitiesOfClass(Mob.class, m.getBoundingBox().inflate(16),
                e -> e.isAlive() && e.getTarget() == m && !Familiars.friendly(m.getUUID(), e));
        orb = null;
        double best = 12 * 12;
        for (FreeManaEntity o : lf.level().getEntitiesOfClass(FreeManaEntity.class, lf.getBoundingBox().inflate(12), Entity::isAlive)) {
            double d = o.distanceToSqr(lf);
            if (d < best) {
                best = d;
                orb = o;
            }
        }
        if (m != null) {
            masterStill = masterLast != null && masterLast.distanceToSqr(m.position()) < 0.01 ? masterStill + THINK_EVERY : 0;
            masterLast = m.position();
        }
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
        if (current != null && now < committedUntil && lastScores.getOrDefault(current, 0.0) > 0 && bestScore < lastScores.get(current) + 0.3) return;
        if (best != current) {
            if (current != null) current.stop();
            current = best;
            committedUntil = now + COMMIT;
            if (current != null) current.start();
        }
    }

    /** What it is doing, in words. */
    public String doingZh() {
        return current == null ? "发呆" : current.zh;
    }

    /** Its scores at the last think, best first, for {@code /logos mind}. */
    public String describe() {
        StringBuilder sb = new StringBuilder("正在" + doingZh() + "｜");
        lastScores.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(e.getKey().zh).append(' ').append(String.format("%.2f", e.getValue())).append("  "));
        return sb.toString().strip();
    }

    /** The scores of the last think, by behaviour name (tests). */
    public Map<String, Double> scores() {
        Map<String, Double> out = new LinkedHashMap<>();
        lastScores.forEach((b, s) -> out.put(b.zh, s));
        return out;
    }

    // ---- moving ---------------------------------------------------------------------------------

    /** Hovers towards {@code point}: straight there through clear air, along a path when blocked. */
    private void hover(Vec3 point, double speed) {
        Vec3 to = aloft(point);
        if (lf.position().distanceToSqr(to) < 0.15 * 0.15) {
            lf.getNavigation().stop();
            return;
        }
        Vec3 from = lf.position().add(0, lf.getBbHeight() / 2, 0);
        boolean clear = lf.level().clip(new ClipContext(from, to.add(0, lf.getBbHeight() / 2, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, lf))
                .getType() == HitResult.Type.MISS;
        double sp = speed * (lf.quick() ? 1.3 : 1);
        if (clear) {
            lf.getNavigation().stop();
            lf.getMoveControl().setWantedPosition(to.x, to.y, to.z, sp);
        } else if (--repath <= 0) {
            repath = 10;
            lf.getNavigation().moveTo(to.x, to.y, to.z, sp);
        }
    }

    /** {@code point}, lifted to at least a block over whatever is below it: a lifeform does not walk. */
    private Vec3 aloft(Vec3 point) {
        BlockPos p = BlockPos.containing(point);
        for (int down = 0; down <= 6; down++) {
            BlockPos b = p.below(down);
            if (!lf.level().getBlockState(b).getCollisionShape(lf.level(), b).isEmpty()) {
                double ground = b.getY() + lf.level().getBlockState(b).getCollisionShape(lf.level(), b).max(net.minecraft.core.Direction.Axis.Y);
                return new Vec3(point.x, Math.max(point.y, ground + 1.0), point.z);
            }
        }
        return point;
    }

    /** Behind its master's shoulder at head height, on its own side, bobbing gently. */
    private Vec3 shoulder(Player m) {
        float yaw = m.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        double side = (lf.getUUID().getLeastSignificantBits() & 1) == 0 ? 1 : -1;
        double reach = 0.9 + (1.3 - lf.loyalty()) * 1.5; // the more devoted, the closer
        double bob = Math.sin(lf.tickCount / 18.0) * 0.12;
        return m.position().add(right.scale(side * reach)).add(forward.scale(-0.6)).add(0, m.getBbHeight() * 0.95 + bob, 0);
    }

    private void lookAt(Entity e) {
        lf.getLookControl().setLookAt(e, 30, 30);
    }

    // ---- casting --------------------------------------------------------------------------------

    private boolean castReady() {
        return castTimer == 0 && castCooldown == 0;
    }

    private void beginCast(int slot, int combo, Entity at) {
        castSlot = slot;
        comboSlot = combo;
        castAt = at;
        castTimer = lf.castTicks();
        lf.setCasting(true);
        lf.level().playSound(null, lf.getX(), lf.getY(), lf.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 0.8f, 1.3f);
    }

    /** Its attack: the cheapest attack page, and the dearest with it if it can spare the mana. */
    private boolean beginAttack(Entity at) {
        List<Integer> slots = lf.attackSlots();
        if (slots.isEmpty()) return false;
        int basic = slots.getFirst();
        int strong = slots.size() > 1 ? slots.getLast() : -1;
        beginCast(basic, strong, at);
        return true;
    }

    private void tickCast() {
        if (castCooldown > 0) castCooldown--;
        if (castTimer <= 0) return;
        castTimer--;
        if (castTimer == lf.castTicks() - lf.releaseAt() && castAt != null && castAt.isAlive()) {
            lf.face(castAt);
            // The second page joins only if the pool still holds its reserve after both.
            boolean combo = comboSlot >= 0 && lf.comboAffordable(castSlot, comboSlot);
            lf.castSlot(castSlot, castAt);
            if (combo) lf.castSlot(comboSlot, castAt);
        }
        if (castTimer == 0) {
            endCast();
            castCooldown = lf.quick() ? 4 : 10;
        }
    }

    private void endCast() {
        castTimer = 0;
        castAt = null;
        if (lf.isCasting()) lf.setCasting(false);
    }

    // ---- emotes -----------------------------------------------------------------------------------

    static void emote(ManaLifeform lf, Emote e) {
        if (!(lf.level() instanceof ServerLevel level)) return;
        Vec3 c = lf.position().add(0, lf.getBbHeight() * 0.8, 0);
        ParticleOptions p = switch (e) {
            case HAPPY -> ParticleTypes.HAPPY_VILLAGER;
            case ALERT -> ParticleTypes.ELECTRIC_SPARK;
            case HUNGRY -> ParticleTypes.SMOKE;
            case CURIOUS -> ParticleTypes.NOTE;
        };
        level.sendParticles(p, c.x, c.y, c.z, e == Emote.CURIOUS ? 1 : 5, 0.15, 0.1, 0.15, 0.01);
        if (e == Emote.HUNGRY) level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_STEP, SoundSource.NEUTRAL, 0.5f, 0.6f);
        if (e == Emote.CURIOUS) level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_CLUSTER_STEP, SoundSource.NEUTRAL, 0.4f, 1.8f);
    }

    // ---- behaviours -----------------------------------------------------------------------------

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

    /** gather: off to where it was sent, nothing else matters. */
    private final class Errand extends Behavior {
        Errand() {
            super("前往");
        }

        @Override
        double score() {
            return Familiars.onErrand(lf) ? 3 : 0;
        }

        @Override
        void tick(long now) {
            Vec3 p = Familiars.orderPoint(lf);
            if (p != null) hover(p.add(0, 0.8, 0), 1.4);
        }
    }

    /** At its master's shoulder (or its post), looking where they look. */
    private final class Escort extends Behavior {
        Escort() {
            super("随行");
        }

        @Override
        double score() {
            return 0.2 * lf.loyalty();
        }

        @Override
        void tick(long now) {
            Vec3 post = Familiars.onErrand(lf) ? null : Familiars.orderPoint(lf);
            if (post != null) {
                hover(post.add(0, 1.2 + Math.sin(lf.tickCount / 18.0) * 0.12, 0), 1.0);
                return;
            }
            Player m = master;
            if (m == null) return; // its master is away: it waits where it is
            Vec3 at = shoulder(m);
            hover(at, lf.position().distanceTo(at) > 6 ? 1.6 : 1.0);
            Vec3 gaze = m.getEyePosition().add(m.getLookAngle().scale(8));
            lf.getLookControl().setLookAt(gaze.x, gaze.y, gaze.z);
        }
    }

    /** Its master's fight (or an order, or its hunt): keep at its attack's reach and cast. */
    private final class Engage extends Behavior {
        Engage() {
            super("交战");
        }

        @Override
        double score() {
            if (foe == null || lf.attackSlots().isEmpty()) return 0;
            double s = (ordered ? 0.9 : 0.6) * lf.bravery() * lf.role().engage;
            // Low on mana it saves itself unless told to fight.
            if (lf.manaFraction() < 0.15 && !ordered) s *= 0.25;
            return s;
        }

        @Override
        void start() {
            emote(lf, Emote.ALERT);
        }

        @Override
        void tick(long now) {
            LivingEntity f = foe;
            if (f == null) return;
            double range = lf.attackRange();
            Vec3 away = lf.position().subtract(f.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
            // Circle slowly around the foe at about three quarters of its reach, a little above its head.
            double swing = Math.sin(now / 40.0) * 0.5;
            Vec3 dir = away.normalize().yRot((float) swing);
            Vec3 spot = f.position().add(dir.scale(range * 0.75)).add(0, f.getBbHeight() + 0.8, 0);
            hover(spot, 1.2);
            lookAt(f);
            if (castReady() && lf.distanceTo(f) <= 16 && lf.hasLineOfSight(f)) beginAttack(f);
        }
    }

    /** Its master is hurt and it holds a HEAL page: close in and heal them. */
    private final class Support extends Behavior {
        Support() {
            super("治疗");
        }

        @Override
        double score() {
            if (master == null || masterHealth >= 0.75 || lf.activeWith(Intent.HEAL).isEmpty() || lf.level().getGameTime() < supportReadyAt) return 0;
            return (1 - masterHealth) * 1.3 * lf.role().support;
        }

        @Override
        void tick(long now) {
            Player m = master;
            if (m == null) return;
            hover(shoulder(m), 1.4);
            lookAt(m);
            List<Integer> heal = lf.activeWith(Intent.HEAL);
            if (!heal.isEmpty() && castReady() && lf.distanceTo(m) < 8) {
                beginCast(heal.getFirst(), -1, m);
                supportReadyAt = now + 60;
            }
        }
    }

    /** Something is hunting its master: get between them, ward its master, strike the hunter. */
    private final class Protect extends Behavior {
        Protect() {
            super("护主");
        }

        private @Nullable Mob nearest() {
            Mob best = null;
            for (Mob t : threats) if (master != null && (best == null || t.distanceToSqr(master) < best.distanceToSqr(master))) best = t;
            return best;
        }

        @Override
        double score() {
            if (master == null || threats.isEmpty()) return 0;
            double s = (masterHealth < 0.6 ? 0.85 : 0.45) * lf.role().protect * lf.bravery();
            if (lf.attackSlots().isEmpty() && lf.activeWith(Intent.WARD).isEmpty()) s *= 0.5; // it can only stand in the way
            return s;
        }

        @Override
        void tick(long now) {
            Player m = master;
            Mob t = nearest();
            if (m == null || t == null) return;
            Vec3 between = m.position().add(t.position().subtract(m.position()).multiply(1, 0, 1).normalize().scale(1.6)).add(0, m.getBbHeight() * 0.8, 0);
            hover(between, 1.6);
            lookAt(t);
            if (!castReady()) return;
            List<Integer> ward = lf.activeWith(Intent.WARD);
            if (!ward.isEmpty() && now >= wardReadyAt) {
                beginCast(ward.getFirst(), -1, m);
                wardReadyAt = now + 100;
            } else if (Familiars.order(lf, t)) {
                // It takes the hunter on as its foe, so (foe) in its attack page means the hunter.
                beginAttack(t);
            }
        }
    }

    /** Free mana nearby and nothing to fight: go and drink it. */
    private final class Forage extends Behavior {
        Forage() {
            super("觅魔");
        }

        @Override
        double score() {
            if (orb == null || foe != null || !threats.isEmpty() || lf.manaFraction() >= 0.98) return 0;
            return (0.35 + (1 - lf.manaFraction()) * 0.4) * (0.6 + 0.4 * lf.curiosity());
        }

        @Override
        void tick(long now) {
            FreeManaEntity o = orb;
            if (o == null || !o.isAlive()) return;
            hover(o.position(), 1.2);
            lookAt(o);
            if (lf.position().add(0, lf.getBbHeight() / 2, 0).distanceTo(o.position()) < 1.2 && o.drainInto(lf, 0.8) > 0) emote(lf, Emote.HAPPY);
        }
    }

    /** Badly hurt in a fight: slip behind its master. */
    private final class Retreat extends Behavior {
        Retreat() {
            super("退避");
        }

        @Override
        double score() {
            double hp = lf.getHealth() / lf.getMaxHealth();
            if (master == null || hp >= 0.35 || (foe == null && threats.isEmpty())) return 0;
            return (0.6 + (0.35 - hp) * 2) * (2 - lf.bravery());
        }

        @Override
        void tick(long now) {
            Player m = master;
            if (m == null) return;
            Entity danger = foe != null ? foe : threats.isEmpty() ? null : threats.getFirst();
            Vec3 away = danger == null ? m.getLookAngle().scale(-1) : m.position().subtract(danger.position());
            away = away.multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-4) away = new Vec3(0, 0, 1);
            hover(m.position().add(away.normalize().scale(2)).add(0, m.getBbHeight() * 0.9, 0), 1.6);
        }
    }

    /** All is quiet and its master stands still: it drifts about, looking at things. */
    private final class Curious extends Behavior {
        private @Nullable Vec3 spot;
        private @Nullable Entity interest;
        private long nextPick;

        Curious() {
            super("闲逛");
        }

        @Override
        double score() {
            if (master == null || foe != null || !threats.isEmpty() || masterStill < 100) return 0;
            return 0.3 * lf.curiosity();
        }

        @Override
        void start() {
            nextPick = 0;
        }

        @Override
        void tick(long now) {
            Player m = master;
            if (m == null) return;
            if (now >= nextPick || spot == null) {
                nextPick = now + 60 + lf.getRandom().nextInt(80);
                interest = null;
                // Something alive and harmless nearby, or else just a spot near its master.
                List<LivingEntity> near = lf.level().getEntitiesOfClass(LivingEntity.class, new AABB(m.blockPosition()).inflate(8),
                        e -> e.isAlive() && e != m && e != lf && !(e instanceof Enemy) && !(e instanceof ManaLifeform));
                if (!near.isEmpty() && lf.getRandom().nextFloat() < 0.6f) {
                    interest = near.get(lf.getRandom().nextInt(near.size()));
                    emote(lf, Emote.CURIOUS);
                } else {
                    double a = lf.getRandom().nextDouble() * Math.PI * 2, r = 2 + lf.getRandom().nextDouble() * 3;
                    spot = m.position().add(Math.cos(a) * r, 1 + lf.getRandom().nextDouble() * 1.5, Math.sin(a) * r);
                }
            }
            if (interest != null && interest.isAlive()) {
                spot = interest.position().add(0, interest.getBbHeight() + 0.7, 0);
                lookAt(interest);
            }
            if (spot != null) hover(spot, 0.7);
        }
    }

    /** Its master sleeps: it settles low at their side. */
    private final class Rest extends Behavior {
        Rest() {
            super("休憩");
        }

        @Override
        double score() {
            return master != null && master.isSleeping() ? 0.9 : 0;
        }

        @Override
        void tick(long now) {
            Player m = master;
            if (m == null) return;
            hover(m.position().add(0.8, 0.4 + Math.sin(lf.tickCount / 30.0) * 0.05, 0.8), 0.5);
        }
    }
}
