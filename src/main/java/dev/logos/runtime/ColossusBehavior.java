package dev.logos.runtime;

import dev.logos.runtime.AlchemicalColossus.Act;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What the colossus's mind chooses between. Each behaviour scores how pressing it is from what the
 * mind last sensed, and the chosen one acts through the mind: it walks, turns and fights with the
 * mind's own steps, and hands its body to a {@link ColossusSequence} for anything timed.
 * <p>
 * A new behaviour is one nested class here plus an entry in the list {@link ColossusMind}'s
 * constructor fills.
 */
abstract class ColossusBehavior extends UtilityMind.Behavior {
    final ColossusMind mind;
    final AlchemicalColossus c;

    ColossusBehavior(ColossusMind mind, String id) {
        super(id);
        this.mind = mind;
        this.c = mind.mob;
    }

    /** gather: off to where it was sent. */
    static final class Errand extends ColossusBehavior {
        Errand(ColossusMind mind) {
            super(mind, "errand");
        }

        @Override
        double score() {
            return !c.isBerserk() && Familiars.onErrand(c) ? 3 : 0;
        }

        @Override
        void tick(long now) {
            Vec3 p = Familiars.orderPoint(c);
            if (p != null) mind.walkTo(p, c.distanceToSqr(p) > 20 * 20);
        }
    }

    /** At its master's side — or its post — standing watch, turning toward anything that moves nearby. */
    static final class Guard extends ColossusBehavior {
        Guard(ColossusMind mind) {
            super(mind, "guard");
        }

        @Override
        double score() {
            return c.isBerserk() ? 0 : 0.2;
        }

        @Override
        void tick(long now) {
            Vec3 post = Familiars.onErrand(c) ? null : Familiars.orderPoint(c);
            Player m = mind.master;
            Vec3 spot;
            if (post != null) spot = post;
            else if (m != null && m.getVehicle() == c) spot = null; // carrying its master: wherever it stands is beside them
            else if (m != null) {
                float yaw = m.getYRot() * Mth.DEG_TO_RAD;
                Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
                Vec3 r = new Vec3(-f.z, 0, f.x);
                spot = m.position().add(r.scale(2.4)).add(f.scale(-1.2));
            } else {
                mind.halt();
                return;
            }
            double d = spot == null ? 0 : c.position().distanceTo(spot);
            if (d > 3.2) mind.walkTo(spot, d > 12);
            else mind.halt();
            // It watches whatever monster is about; else it looks where its master looks.
            Mob watch = c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(14), e -> e.isAlive() && e instanceof Enemy)
                    .stream().min((a, b) -> Double.compare(a.distanceToSqr(c), b.distanceToSqr(c))).orElse(null);
            if (watch != null) c.getLookControl().setLookAt(watch, 15, 15);
            else if (m != null) {
                Vec3 g = m.getEyePosition().add(m.getLookAngle().scale(10));
                c.getLookControl().setLookAt(g.x, g.y, g.z, 10, 10);
            }
            if (!c.getNavigation().isInProgress() && m != null && c.getRandom().nextInt(2400) == 0) mind.maybeOfferFlower(m, 1);
        }
    }

    /** Its master's fight, an order, its hunt, or what goes for its master. */
    static final class Engage extends ColossusBehavior {
        Engage(ColossusMind mind) {
            super(mind, "engage");
        }

        @Override
        double score() {
            return !c.isBerserk() && mind.foe != null ? 0.8 : 0;
        }

        @Override
        void tick(long now) {
            if (mind.foe != null) mind.fight(mind.foe);
        }
    }

    /** Something shoots at its master: step into the line of fire, facing it, and answer with the cannon. */
    static final class ShieldMaster extends ColossusBehavior {
        ShieldMaster(ColossusMind mind) {
            super(mind, "shield");
        }

        @Override
        double score() {
            return !c.isBerserk() && mind.master != null && mind.shooter != null ? 0.95 : 0;
        }

        @Override
        void tick(long now) {
            Player m = mind.master;
            Mob s = mind.shooter;
            if (m == null || s == null) return;
            Vec3 dir = s.position().subtract(m.position()).multiply(1, 0, 1);
            if (dir.lengthSqr() < 1e-4) return;
            Vec3 spot = m.position().add(dir.normalize().scale(2.2));
            if (c.position().distanceTo(spot) > 1.2) {
                mind.walkTo(spot, true);
                mind.face(s);
            } else {
                mind.halt();
                mind.face(s);
                if (mind.cannonReady() && ColossusCannon.canSee(c, s)) {
                    Familiars.order(c, s);
                    mind.runSeq(new ColossusSequence.CannonShot(mind, s, null));
                }
            }
        }
    }

    /** Its master asleep or away: it kneels down and powers low until they return. */
    static final class Standby extends ColossusBehavior {
        Standby(ColossusMind mind) {
            super(mind, "standby");
        }

        @Override
        double score() {
            if (c.isBerserk() || mind.foe != null || mind.assailant != null) return 0;
            if (mind.master == null && Familiars.masterOf(c) != null) return 0.9;
            return mind.master != null && mind.master.isSleeping() ? 0.9 : 0;
        }

        @Override
        void start() {
            mind.halt();
            mind.standing = false;
            c.play(Act.POWER_DOWN);
        }

        @Override
        void tick(long now) {
            mind.halt();
        }

        @Override
        void stop() {
            if (c.act() == Act.DORMANT || c.act() == Act.POWER_DOWN) c.play(Act.POWER_UP);
        }
    }

    /** It wants to give its master a flower: walk over and kneel. */
    static final class OfferFlower extends ColossusBehavior {
        OfferFlower(ColossusMind mind) {
            super(mind, "offer_flower");
        }

        @Override
        double score() {
            return mind.wantsFlower && !c.isBerserk() && mind.master != null && mind.master.getVehicle() != c && mind.foe == null && mind.assailant == null && mind.threats.isEmpty() && mind.master.distanceTo(c) < 24 ? 0.6 : 0;
        }

        @Override
        void tick(long now) {
            Player m = mind.master;
            if (m == null) return;
            if (mind.gap(m) > 1.6) {
                mind.walkTo(m.position(), false);
                return;
            }
            mind.halt();
            mind.face(m);
            mind.wantsFlower = false;
            // Five to eight minutes before the next.
            mind.flowerReadyAt = c.level().getGameTime() + 6000 + c.getRandom().nextInt(3600);
            mind.runSeq(new ColossusSequence.Flower(mind, m));
        }
    }

    /** In a frenzy: after whatever is near, master included. */
    static final class Rampage extends ColossusBehavior {
        Rampage(ColossusMind mind) {
            super(mind, "rampage");
        }

        @Override
        double score() {
            return c.isBerserk() ? 1 : 0;
        }

        @Override
        void tick(long now) {
            if (mind.foe != null) mind.fight(mind.foe);
            else mind.halt();
        }
    }

    /**
     * Self-defence, the least of its duties: what struck it — a zombified piglin it angered, say, that
     * has no quarrel with its master — is fought once no order, no threat to its master, no errand is
     * more pressing.
     */
    static final class SelfDefense extends ColossusBehavior {
        SelfDefense(ColossusMind mind) {
            super(mind, "self_defense");
        }

        @Override
        double score() {
            return !c.isBerserk() && mind.assailant != null ? 0.7 : 0;
        }

        @Override
        void tick(long now) {
            if (mind.assailant != null) mind.fight(mind.assailant);
        }
    }
}
