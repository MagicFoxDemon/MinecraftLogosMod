package dev.logos.compat.tlm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.lang.Intent;
import dev.logos.lang.Reach;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.UtilityMind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A maid's mind in the 施法 mode, a utility AI as a mana lifeform's ({@link UtilityMind}): every half second she
 * senses how things stand, scores what she could do and does the best of it. She fights her foe at her attacks'
 * reach, a control page first for one about to hurt her or her owner (a creeper swelling, say); heals her owner, or herself;
 * wards her owner when something hunts them; lends them strength with an empowering page while they fight; slips
 * behind her owner when badly hurt;
 * goes to free mana nearby when all is quiet; and with a staff, fills it while she can, and in a fight whenever it
 * runs too low to cast. {@link MaidCaster} does the casting. With nothing worth doing she leaves her moves to the
 * maid mod (following her owner, staying home). The maid mod's brain runs her, so {@link CastSpells} drives this mind
 * from a brain behaviour rather than as a goal.
 */
public final class MaidMind extends UtilityMind<EntityMaid> {
    static final int COMMIT = 20;
    /** A group heal or ward reaches only those near her: she casts one for her owner from at most this far. */
    static final double GROUP_REACH = 4;
    /** She goes to free mana within this far. */
    static final double FORAGE_RANGE = 12;
    /** The mind each maid has, for tests. */
    private static final Map<EntityMaid, MaidMind> MINDS = new WeakHashMap<>();

    private final MaidCaster caster;

    // Senses, refreshed each think.
    private @Nullable CreatureMana pool;
    private List<MaidSpells.Page> pages = List.of();
    private @Nullable Player owner;
    private double ownerHealth = 1;
    private List<Mob> threats = List.of();
    private @Nullable LivingEntity foe;
    private @Nullable FreeManaEntity orb;
    private long supportReadyAt, wardReadyAt, mendReadyAt, empowerReadyAt;
    /** Whether a behaviour of hers set where she walks, so she clears it when it stops. */
    private boolean steering, backing;

    MaidMind(EntityMaid maid, MaidCaster caster) {
        super(maid, COMMIT);
        this.caster = caster;
        behaviors.add(new Engage());
        behaviors.add(new Support());
        behaviors.add(new Mend());
        behaviors.add(new Protect());
        behaviors.add(new Empower());
        behaviors.add(new Retreat());
        behaviors.add(new Forage());
        behaviors.add(new Fill());
        MINDS.put(maid, this);
    }

    /** What {@code maid} is doing and how she scored her choices last, or null if she has not thought in the 施法 mode (tests). */
    public static @Nullable String describe(EntityMaid maid) {
        MaidMind mind = MINDS.get(maid);
        return mind == null ? null : mind.doing() + " " + mind.scores();
    }

    /** Never used as a goal: {@link CastSpells} runs it. */
    @Override
    public boolean canUse() {
        return false;
    }

    /** One tick: thinks when it is time to, and carries on with what she chose. */
    void step() {
        thinkAndAct();
    }

    /** She stops: the 施法 mode ended or her wand left her hand. */
    void halt() {
        stopCurrent();
        stopSteering();
    }

    @Override
    public String doing() {
        return current == null ? "idle" : current.id;
    }

    /** The scores of the last think, by behaviour id (tests). */
    Map<String, Double> scores() {
        Map<String, Double> out = new LinkedHashMap<>();
        lastScores.forEach((b, s) -> out.put(b.id, s));
        return out;
    }

    // ---- sensing ----------------------------------------------------------------------------------

    @Override
    protected void sense() {
        pool = MaidMana.of(mob);
        pages = MaidSpells.active(mob);
        Player o = mob.getOwner() instanceof Player p && p.isAlive() && p.level() == mob.level() ? p : null;
        owner = o;
        ownerHealth = o == null ? 1 : o.getHealth() / o.getMaxHealth();
        threats = o == null ? List.of() : mob.level().getEntitiesOfClass(Mob.class, o.getBoundingBox().inflate(16),
                e -> e.isAlive() && e.getTarget() == o && !o.getUUID().equals(Familiars.sideOf(e)));
        LivingEntity t = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        foe = t != null && t.isAlive() && t.level() == mob.level() && mob.distanceTo(t) <= MaidCaster.REACH + 8 ? t : null;
        orb = null;
        if (pool != null && pool.mana() < pool.maxMana() * 0.98) {
            double best = FORAGE_RANGE * FORAGE_RANGE;
            for (FreeManaEntity e : mob.level().getEntitiesOfClass(FreeManaEntity.class, mob.getBoundingBox().inflate(FORAGE_RANGE), Entity::isAlive)) {
                double d = e.distanceToSqr(mob);
                if (d < best && mayGo(e.position())) {
                    best = d;
                    orb = e;
                }
            }
        }
    }

    private double manaFraction() {
        CreatureMana p = pool;
        return p == null || p.maxMana() <= 0 ? 0 : p.mana() / p.maxMana();
    }

    // ---- moving -----------------------------------------------------------------------------------

    /** Riding, sitting or told to wait, she stays where she is. */
    private boolean canMove() {
        return !mob.isPassenger() && !mob.isOrderedToSit() && !mob.isMaidInSittingPose();
    }

    /** With a home she keeps within it. */
    private boolean mayGo(Vec3 spot) {
        return !mob.hasRestriction() || mob.isWithinRestriction(BlockPos.containing(spot));
    }

    private void walkTo(Vec3 spot, float speed, int close) {
        if (!canMove() || !mayGo(spot)) return;
        stopBacking();
        mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, speed, close));
        steering = true;
    }

    private void lookAt(Entity e) {
        mob.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(e, true));
    }

    private void stopSteering() {
        if (steering) mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        steering = false;
        stopBacking();
    }

    private void stopBacking() {
        if (!backing) return;
        backing = false;
        mob.getMoveControl().strafe(0, 0);
    }

    /** Keeps about her attacks' reach from {@code f}: closer when out of reach or sight, backing off when it is too close. */
    private void keepRange(LivingEntity f) {
        lookAt(f);
        if (!canMove()) return;
        double range = MaidSpells.attackRange(MaidSpells.attacks(pages));
        double d = mob.distanceTo(f);
        // Without a home she stays near her owner, and does not chase a foe beyond her follow range.
        boolean leashed = !mob.hasRestriction() && owner != null && f.distanceTo(owner) >= mob.getRestrictRadius();
        if ((d > range || !mob.hasLineOfSight(f)) && !leashed) {
            walkTo(f.position(), 0.6f, (int) Math.max(1, range * 0.6));
        } else if (d < range * 0.35) {
            if (steering) mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getMoveControl().strafe(-0.5f, 0);
            backing = true;
        } else {
            if (steering) mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            stopBacking();
        }
    }

    /** Behind her owner, away from what threatens them (or from where they look). */
    private void slipBehind(Player o) {
        Entity danger = foe != null ? foe : threats.isEmpty() ? null : threats.getFirst();
        Vec3 away = danger == null ? o.getLookAngle().scale(-1) : o.position().subtract(danger.position());
        away = away.multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) away = new Vec3(0, 0, 1);
        walkTo(o.position().add(away.normalize().scale(2)), 0.7f, 1);
    }

    // ---- choosing pages ---------------------------------------------------------------------------

    /** Of {@code from}, those she can cast right now (a staff must hold enough for them). */
    private List<MaidSpells.Page> affordable(List<MaidSpells.Page> from) {
        CreatureMana p = pool;
        return p == null ? List.of() : from.stream().filter(page -> caster.affords(mob, p, page)).toList();
    }

    private List<MaidSpells.Page> reaching(Intent intent, Reach r) {
        return MaidSpells.reaching(affordable(MaidSpells.with(pages, intent)), r);
    }

    /** Whether she holds a page filed under {@code intent} that can reach her owner: one aimed at another, or a group one. */
    private boolean canReachOwner(Intent intent) {
        return !reaching(intent, Reach.ONE).isEmpty() || !reaching(intent, Reach.GROUP).isEmpty();
    }

    /**
     * The page to heal or ward her owner {@code o} with from where she is, or null: one aimed at a single creature
     * first, else a group one if she is close enough for it to take her owner in. Hurt herself too, a group heal that
     * takes in both of them comes first. A page that reaches only its caster is never cast at her owner.
     */
    private @Nullable MaidSpells.Page forOwner(Intent intent, Player o) {
        double d = mob.distanceTo(o);
        boolean near = d < GROUP_REACH;
        List<MaidSpells.Page> group = reaching(intent, Reach.GROUP);
        if (intent == Intent.HEAL && near && mob.getHealth() < mob.getMaxHealth() * 0.75) {
            for (MaidSpells.Page p : group) if (p.spell().profile().reaches(Reach.SELF)) return p;
        }
        List<MaidSpells.Page> one = reaching(intent, Reach.ONE);
        if (!one.isEmpty() && d < 12) return one.getFirst();
        return !group.isEmpty() && near ? group.getFirst() : null;
    }

    /** Her heal page for herself: one that heals only its caster first, else a group heal that takes her in. */
    private @Nullable MaidSpells.Page forHerself() {
        List<MaidSpells.Page> own = reaching(Intent.HEAL, Reach.SELF);
        for (MaidSpells.Page p : own) if (!p.spell().profile().reaches(Reach.GROUP)) return p;
        return own.isEmpty() ? null : own.getFirst();
    }

    /** About to hurt her owner or her: a creeper swelling near them, or something hunting one of them at arm's length. */
    private boolean dangerous(LivingEntity f) {
        Player o = owner;
        if (f instanceof Creeper c && c.getSwelling(1) > 0 && (c.distanceTo(mob) < 5 || (o != null && c.distanceTo(o) < 5))) return true;
        if (!(f instanceof Mob m)) return false;
        LivingEntity prey = m.getTarget();
        return (prey == mob || (o != null && prey == o)) && f.distanceTo(prey) < 4;
    }

    /** How many creatures she would fight stand within 4 blocks of {@code f}, {@code f} included. */
    private int crowd(LivingEntity f) {
        return mob.level().getEntitiesOfClass(LivingEntity.class, f.getBoundingBox().inflate(4), e -> e.isAlive() && (e == f || mob.canAttack(e))).size();
    }

    /**
     * Strikes {@code f} with what suits it: a control page first if it is about to hurt her or her owner, an area page when it
     * has company, else her single-target pages; the cheapest each time, and the dearest with it if she can spare both.
     */
    private void attack(LivingEntity f) {
        CreatureMana p = pool;
        if (p == null || !caster.ready() || mob.distanceTo(f) > MaidCaster.REACH || !mob.hasLineOfSight(f)) return;
        List<MaidSpells.Page> attacks = affordable(MaidSpells.attacks(pages));
        if (attacks.isEmpty()) return;
        List<MaidSpells.Page> control = MaidSpells.with(attacks, Intent.CONTROL);
        List<MaidSpells.Page> area = MaidSpells.with(attacks, Intent.AREA);
        List<MaidSpells.Page> single = MaidSpells.with(attacks, Intent.STRIKE, Intent.CONTROL, Intent.UTILITY);
        List<MaidSpells.Page> pick;
        if (!control.isEmpty() && dangerous(f)) pick = control;
        else if (!area.isEmpty() && (single.isEmpty() || crowd(f) >= 3)) pick = area;
        else pick = single.isEmpty() ? attacks : single;
        MaidSpells.Page basic = pick.getFirst();
        MaidSpells.Page strong = pick.size() > 1 ? pick.getLast() : null;
        caster.cast(mob, p, basic, strong, f);
    }

    /** Whether she has an attack she can cast right now. */
    private boolean armed() {
        return !affordable(MaidSpells.attacks(pages)).isEmpty();
    }

    // ---- behaviours -------------------------------------------------------------------------------

    /** A base for her behaviours: whatever set where she walks is cleared when it stops. */
    private abstract class Act extends Behavior {
        Act(String id) {
            super(id);
        }

        @Override
        protected void stop() {
            stopSteering();
        }
    }

    /** Her foe: keep at her attacks' reach and strike it. */
    private final class Engage extends Act {
        Engage() {
            super("engage");
        }

        @Override
        protected double score() {
            if (foe == null || MaidSpells.attacks(pages).isEmpty()) return 0;
            // A staff too low to cast leaves the fight to filling it (see Fill); a wand low on mana holds back.
            if (!armed()) return 0.1;
            return !MaidCaster.staff(mob) && manaFraction() < 0.15 ? 0.15 : 0.6;
        }

        @Override
        protected void tick(long now) {
            LivingEntity f = foe;
            if (f == null) return;
            keepRange(f);
            attack(f);
        }
    }

    /** Her owner is hurt and she holds a heal that can reach them: close in and heal them. */
    private final class Support extends Act {
        Support() {
            super("support");
        }

        @Override
        protected double score() {
            if (owner == null || ownerHealth >= 0.75 || mob.level().getGameTime() < supportReadyAt || !canReachOwner(Intent.HEAL)) return 0;
            return (1 - ownerHealth) * 1.3;
        }

        @Override
        protected void tick(long now) {
            Player o = owner;
            CreatureMana p = pool;
            if (o == null || p == null) return;
            lookAt(o);
            if (mob.distanceTo(o) > 3) walkTo(o.position(), 0.7f, 2);
            if (!caster.ready()) return;
            MaidSpells.Page heal = forOwner(Intent.HEAL, o);
            if (heal != null && caster.cast(mob, p, heal, null, o)) supportReadyAt = now + 60;
        }
    }

    /** Badly hurt with a heal that reaches herself: heal herself, keeping behind her owner meanwhile. */
    private final class Mend extends Act {
        Mend() {
            super("mend");
        }

        @Override
        protected double score() {
            double hp = mob.getHealth() / mob.getMaxHealth();
            if (hp >= 0.5 || mob.level().getGameTime() < mendReadyAt || forHerself() == null) return 0;
            return 0.55 + (0.5 - hp) * 1.6;
        }

        @Override
        protected void tick(long now) {
            if (owner != null) slipBehind(owner);
            CreatureMana p = pool;
            MaidSpells.Page heal = forHerself();
            if (p != null && heal != null && caster.ready() && caster.cast(mob, p, heal, null, mob)) mendReadyAt = now + 60;
        }
    }

    /**
     * Something hunts her owner and she holds a ward that can reach them: ward them, then carry on as before. She does
     * not stand in the way herself (she is no sturdier than her owner); a group ward takes her close enough to cover them.
     */
    private final class Protect extends Act {
        Protect() {
            super("protect");
        }

        @Override
        protected double score() {
            if (owner == null || threats.isEmpty() || mob.level().getGameTime() < wardReadyAt || !canReachOwner(Intent.WARD)) return 0;
            return ownerHealth < 0.6 ? 0.9 : 0.7;
        }

        @Override
        protected void tick(long now) {
            Player o = owner;
            CreatureMana p = pool;
            if (o == null || p == null) return;
            lookAt(o);
            MaidSpells.Page ward = forOwner(Intent.WARD, o);
            // Only a group ward would cover her owner, and she is too far for it: close in first.
            if (ward == null) {
                walkTo(o.position(), 0.8f, 1);
                return;
            }
            if (caster.ready() && caster.cast(mob, p, ward, null, o)) wardReadyAt = now + 100;
        }
    }

    /**
     * Her owner is fighting and she holds an empowering page that can reach them: lend them strength, and not again
     * until what she lent has run out. A group page takes her close enough to cover them.
     */
    private final class Empower extends Act {
        Empower() {
            super("empower");
        }

        @Override
        protected double score() {
            if (owner == null || mob.level().getGameTime() < empowerReadyAt || !canReachOwner(Intent.EMPOWER) || !Familiars.fighting(owner)) return 0;
            return 0.65;
        }

        @Override
        protected void tick(long now) {
            Player o = owner;
            CreatureMana p = pool;
            if (o == null || p == null) return;
            lookAt(o);
            MaidSpells.Page page = forOwner(Intent.EMPOWER, o);
            if (page == null) {
                walkTo(o.position(), 0.8f, 1);
                return;
            }
            if (caster.ready() && caster.cast(mob, p, page, null, o)) {
                empowerReadyAt = now + Math.max(100, Math.round(page.spell().profile().lasting() * 20));
            }
        }
    }

    /** Badly hurt in a fight: slip behind her owner. */
    private final class Retreat extends Act {
        Retreat() {
            super("retreat");
        }

        @Override
        protected double score() {
            double hp = mob.getHealth() / mob.getMaxHealth();
            if (owner == null || hp >= 0.35 || (foe == null && threats.isEmpty())) return 0;
            return 0.6 + (0.35 - hp) * 2;
        }

        @Override
        protected void tick(long now) {
            if (owner != null) slipBehind(owner);
        }
    }

    /** Free mana nearby and nothing to fight: go to it, and it flows into her pool. */
    private final class Forage extends Act {
        Forage() {
            super("forage");
        }

        @Override
        protected double score() {
            if (orb == null || foe != null || !threats.isEmpty() || !canMove()) return 0;
            return 0.35 + (1 - manaFraction()) * 0.4;
        }

        @Override
        protected void tick(long now) {
            FreeManaEntity o = orb;
            if (o == null || !o.isAlive()) return;
            lookAt(o);
            walkTo(o.position(), 0.7f, 0);
        }
    }

    /**
     * Holding a staff that is not full: fill it from her pool, as a player holds left-click. When all is quiet she
     * tops it up; in a fight she does it only when it holds too little for any of her attacks, keeping her distance.
     */
    private final class Fill extends Act {
        Fill() {
            super("fill");
        }

        @Override
        protected double score() {
            CreatureMana p = pool;
            if (!MaidCaster.staff(mob) || p == null || p.mana() < 1 || caster.staffFill(mob) >= 1) return 0;
            if (foe != null || !threats.isEmpty()) return armed() ? 0 : 0.8;
            return 0.3 + (1 - caster.staffFill(mob)) * 0.4;
        }

        @Override
        protected void tick(long now) {
            CreatureMana p = pool;
            if (p == null) return;
            if (foe != null) keepRange(foe);
            caster.fill(mob, p);
        }

        @Override
        protected void stop() {
            caster.stopFill(mob);
            super.stop();
        }
    }
}
