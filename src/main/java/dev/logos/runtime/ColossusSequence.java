package dev.logos.runtime;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus.Act;
import dev.logos.runtime.AlchemicalColossus.Hold;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Something the colossus does with its whole body, timed act by act on the server ({@link Act}).
 * {@link ColossusMind} runs one at a time and does nothing else while it lasts; the sequences
 * themselves are the nested classes here.
 */
abstract class ColossusSequence {
    final ColossusMind mind;
    final AlchemicalColossus c;
    final String id;
    int t;

    ColossusSequence(ColossusMind mind, String id) {
        this.mind = mind;
        this.c = mind.mob;
        this.id = id;
    }

    void begin() {}

    /** One tick; false when done. */
    abstract boolean tick();

    void abort() {}

    /** A sweeping blow: everything in front of it is struck hard and thrown back. */
    static final class Punch extends ColossusSequence {
        private final @Nullable LivingEntity at;

        Punch(ColossusMind mind, @Nullable LivingEntity at) {
            super(mind, "punch");
            this.at = at;
        }

        @Override
        void begin() {
            c.play(Act.PUNCH);
            // Its fist is guided onto the foe as it drives home (the client poses it: ColossusArmIk).
            c.reachFor(at);
        }

        @Override
        boolean tick() {
            if (at != null && t < 14) mind.faceWithArm(at);
            // The blow lands as the fist does: the animation's thrust is fullest at about 0.93 s.
            if (t == 18) strike();
            boolean more = ++t < Act.PUNCH.ticks;
            if (!more) c.reachFor(null);
            return more;
        }

        @Override
        void abort() {
            c.reachFor(null);
        }

        private void strike() {
            if (!(c.level() instanceof ServerLevel level)) return;
            Vec3 centre = c.local(0.3, 1.2, 2.2);
            for (LivingEntity e : mind.inSweep(centre)) {
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
    static final class Grab extends ColossusSequence {
        private final LivingEntity victim;
        private Act phase = Act.GRAB_REACH;
        private boolean caught, wasNoAi;
        private int phaseAt;
        private @Nullable Vec3 diedAt;

        Grab(ColossusMind mind, LivingEntity victim) {
            super(mind, "grab");
            this.victim = victim;
        }

        private void phase(Act a) {
            phase = a;
            phaseAt = t;
            c.play(a);
            // Reaching, its arm is guided onto the victim (the client poses it: ColossusArmIk).
            c.reachFor(a == Act.GRAB_REACH ? victim : null);
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
                    mind.faceWithArm(victim);
                    if (p == 18) {
                        c.reachFor(null); // from here its hand carries the victim
                        if (victim.isAlive() && mind.gap(victim) <= ColossusMind.MELEE + 1) {
                            // Whatever it rides — even the colossus itself — it is torn off, and whatever rides it shaken off.
                            if (victim.getVehicle() == c) c.release(victim, null);
                            else if (victim.isPassenger()) victim.stopRiding();
                            victim.ejectPassengers();
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
                    else if (p >= 20) phase(ColossusMind.finishable(victim) ? Act.GRAB_EXECUTE : Act.GRAB_SLAM);
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
                    if (diedAt != null) mind.turnTo(diedAt);
                    mind.halt();
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
            c.reachFor(null);
            letGo(null);
        }
    }

    /** The palm cannon: raise, gather (about three seconds), fire a beam it holds on its target, recover. */
    static final class CannonShot extends ColossusSequence {
        private final @Nullable LivingEntity target;
        private final @Nullable Player rider;
        private final ColossusCannon beam;
        private Act phase = Act.CANNON_PREPARE;
        private int phaseAt;
        private boolean releasedEarly, released;
        private int releasedAt = -1;

        CannonShot(ColossusMind mind, @Nullable LivingEntity target, @Nullable Player rider) {
            super(mind, "cannon");
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
            if (target != null && phase != Act.CANNON_RECOVER) mind.face(target);
            // Its arm follows what it shoots at until the beam is spent.
            c.aimAt(phase == Act.CANNON_RECOVER ? null : beam.aim());
            mind.halt();
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
                        mind.cannonReadyAt = c.level().getGameTime() + 60;
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
    static final class Board extends ColossusSequence {
        private final Player p;
        private final Hold seat;

        Board(ColossusMind mind, Player p, Hold seat) {
            super(mind, "board");
            this.p = p;
            this.seat = seat;
        }

        @Override
        void begin() {
            c.play(seat == Hold.SEAT_LEFT ? Act.PICKUP_LEFT : Act.PICKUP_RIGHT);
        }

        @Override
        boolean tick() {
            if (t < 30) mind.face(p);
            mind.halt();
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
    static final class PutDown extends ColossusSequence {
        final Player p;
        private final Hold seat;

        PutDown(ColossusMind mind, Player p, Hold seat) {
            super(mind, "put_down");
            this.p = p;
            this.seat = seat;
        }

        @Override
        void begin() {
            c.play(seat == Hold.SEAT_LEFT ? Act.PUTDOWN_LEFT : Act.PUTDOWN_RIGHT);
        }

        @Override
        boolean tick() {
            mind.halt();
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
    static final class Flower extends ColossusSequence {
        private final Player to;
        private boolean taken;

        Flower(ColossusMind mind, Player to) {
            super(mind, "offer_flower");
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
            mind.halt();
            mind.face(to);
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
