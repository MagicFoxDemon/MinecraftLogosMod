package dev.logos.compat.tlm;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.lang.Element;
import dev.logos.lang.Intent;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Charging;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.Targeting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The Spellcasting mode's mind, as a mana lifeform's: she heals her owner (or herself) with HEAL pages when hurt, wards
 * them with WARD pages when something is hunting them, and strikes her foe with her attack pages, the cheapest each time
 * and the dearest with it if her pool can spare both, AREA pages first when the foe has company. Each cast charges from
 * her pool at the wand's flow and surge, the charge showing as a player's does, and lets go once full.
 */
final class CastSpells extends Behavior<EntityMaid> {
    /** Ticks between one cast and the next. */
    static final int COOLDOWN = 10;
    /** She casts at what is within this far and in sight. */
    static final double REACH = 16;
    /** Share of her pool a combo must leave, as a lifeform keeps. */
    static final double COMBO_RESERVE = 0.3;

    private MaidSpells.@Nullable Page casting, combo;
    private @Nullable LivingEntity at;
    private double chargeTarget, chargeIn, flow, surge;
    private int chargeDone, chargeTicks, chargeTier, cooldown;
    private Element element = Element.ARCANE;
    private long supportReadyAt, wardReadyAt;

    CastSpells() {
        super(Map.of(), 72000);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return MaidSpells.holdsWand(maid) && MaidMana.of(maid) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long now) {
        return checkExtraStartConditions(level, maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long now) {
        if (cooldown > 0) cooldown--;
        CreatureMana pool = MaidMana.of(maid);
        if (pool == null) return;
        if (casting != null) {
            charge(level, maid, pool);
            return;
        }
        if (cooldown > 0) return;
        List<MaidSpells.Page> pages = MaidSpells.active(maid);
        if (pages.isEmpty() || pool.mana() <= 0) return;
        Player owner = maid.getOwner() instanceof Player p && p.level() == level && p.isAlive() ? p : null;

        List<MaidSpells.Page> heals = MaidSpells.with(pages, Intent.HEAL);
        if (!heals.isEmpty() && now >= supportReadyAt) {
            if (owner != null && owner.getHealth() < owner.getMaxHealth() * 0.75 && maid.distanceTo(owner) < 12) {
                supportReadyAt = now + 60;
                begin(maid, pool, heals.getFirst(), null, owner);
                return;
            }
            if (maid.getHealth() < maid.getMaxHealth() * 0.5) {
                supportReadyAt = now + 60;
                begin(maid, pool, heals.getFirst(), null, maid);
                return;
            }
        }
        List<MaidSpells.Page> wards = MaidSpells.with(pages, Intent.WARD);
        if (owner != null && !wards.isEmpty() && now >= wardReadyAt && maid.distanceTo(owner) < 12 && hunted(level, owner)) {
            wardReadyAt = now + 100;
            begin(maid, pool, wards.getFirst(), null, owner);
            return;
        }
        LivingEntity foe = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (foe == null || !foe.isAlive() || maid.distanceTo(foe) > REACH || !maid.hasLineOfSight(foe)) return;
        List<MaidSpells.Page> attacks = MaidSpells.attacks(pages);
        if (attacks.isEmpty()) return;
        // A crowd around the foe: an area page, if she has one, takes them together.
        List<MaidSpells.Page> area = MaidSpells.with(attacks, Intent.AREA);
        List<MaidSpells.Page> single = MaidSpells.with(attacks, Intent.STRIKE, Intent.CONTROL, Intent.UTILITY);
        List<MaidSpells.Page> pick = !area.isEmpty() && (single.isEmpty() || crowd(level, maid, foe) >= 3) ? area : single.isEmpty() ? attacks : single;
        MaidSpells.Page basic = pick.getFirst();
        MaidSpells.Page strong = pick.size() > 1 ? pick.getLast() : null;
        if (strong != null && pool.mana() - basic.injection() - strong.injection() < COMBO_RESERVE * pool.maxMana()) strong = null;
        begin(maid, pool, basic, strong, foe);
    }

    /** Whether something near her owner, not one of their own familiars, has them as its target. */
    private static boolean hunted(ServerLevel level, Player owner) {
        return !level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(16),
                e -> e.isAlive() && e.getTarget() == owner && !owner.getUUID().equals(Familiars.masterOf(e))).isEmpty();
    }

    /** How many creatures she would fight stand within 4 blocks of {@code foe}, the foe included. */
    private static int crowd(ServerLevel level, EntityMaid maid, LivingEntity foe) {
        return level.getEntitiesOfClass(LivingEntity.class, foe.getBoundingBox().inflate(4), e -> e.isAlive() && (e == foe || maid.canAttack(e))).size();
    }

    /** Starts charging {@code page} (and {@code with} after it) at {@code target}: what they inject, or all her pool holds if less. */
    private void begin(EntityMaid maid, CreatureMana pool, MaidSpells.Page page, MaidSpells.@Nullable Page with, LivingEntity target) {
        double total = Math.min(page.injection() + (with == null ? 0 : with.injection()), pool.mana());
        flow = MaidSpells.flow(maid);
        surge = MaidSpells.surge(maid, pool);
        if (total <= 0 || (flow <= 0 && surge <= 0)) return;
        casting = page;
        combo = with;
        at = target;
        chargeTarget = total;
        chargeIn = 0;
        chargeDone = 0;
        chargeTicks = Charging.ticks(total, flow, surge);
        element = page.spell().element();
        chargeTier = Charging.tier(total);
        maid.setSwingingArms(true);
        Charging.show(maid, element, chargeTier, 0, false);
        maid.level().playSound(null, maid.getX(), maid.getY(), maid.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 0.6f, 1.2f);
    }

    /** One tick of the charge: draws its share from her pool and lets go once all of it is in. */
    private void charge(ServerLevel level, EntityMaid maid, CreatureMana pool) {
        LivingEntity target = at;
        if (target == null || !target.isAlive() || target.level() != level || (target != maid && maid.distanceTo(target) > REACH + 8)) {
            // Its target gone before she let go: the charge goes back into her pool.
            end(maid, pool);
            cooldown = COOLDOWN;
            return;
        }
        if (target != maid) maid.getLookControl().setLookAt(target, 30, 30);
        if (chargeIn < chargeTarget - 1e-9) {
            double share = Charging.injectedAfter(chargeDone + 1, chargeTarget, flow, surge, chargeTicks) - chargeIn;
            if (pool.mana() + 1e-9 < share) {
                chargeTarget = chargeIn; // her pool ran low meanwhile: cast with what is in
            } else {
                chargeIn += pool.take(share);
                chargeDone++;
            }
        }
        if (chargeIn < chargeTarget - 1e-9) {
            if (chargeDone % 2 == 0) Charging.show(maid, element, chargeTier, (float) (chargeIn / chargeTarget), false);
            return;
        }
        release(level, maid, pool, target);
    }

    /** Lets go: the charge goes into the page, and what is left of it into the combo page. */
    private void release(ServerLevel level, EntityMaid maid, CreatureMana pool, LivingEntity target) {
        MaidSpells.Page page = casting, then = combo;
        double paid = chargeIn;
        chargeIn = 0;
        casting = combo = null;
        at = null;
        maid.setSwingingArms(false);
        cooldown = COOLDOWN;
        if (page == null || paid <= 0) {
            Charging.hide(maid);
            if (paid > 0) pool.receiveMana(paid);
            return;
        }
        if (target != maid) face(maid, target);
        Charging.discharge(maid, element, chargeTier);
        maid.swing(InteractionHand.MAIN_HAND);
        double first = Math.min(page.injection(), paid);
        Casting.castAs(maid, page.spell(), first, page.tier().recovery(), target, null, true);
        if (then != null && paid - first > 0) Casting.castAs(maid, then.spell(), paid - first, then.tier().recovery(), target, null, true);
    }

    /** Ends a cast cut short: what it had charged goes back to her pool. */
    private void end(EntityMaid maid, CreatureMana pool) {
        if (chargeIn > 0) pool.receiveMana(chargeIn);
        if (casting != null) Charging.hide(maid);
        chargeIn = 0;
        casting = combo = null;
        at = null;
        maid.setSwingingArms(false);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long now) {
        CreatureMana pool = MaidMana.of(maid);
        if (pool != null) end(maid, pool);
    }

    /** Turns body, head and gaze straight at {@code target}, so a spell cast now points there. */
    private static void face(EntityMaid maid, LivingEntity target) {
        Vec3 d = Targeting.center(target).subtract(maid.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        maid.setYRot(yaw);
        maid.setXRot(pitch);
        maid.yHeadRot = maid.yBodyRot = yaw;
    }
}
