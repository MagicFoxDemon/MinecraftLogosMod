package dev.logos.compat.tlm;

import org.jspecify.annotations.Nullable;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.item.StaffItem;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Charging;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.Targeting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * How a maid casts, as a player does with what she holds. With a wand she charges each cast from her pool at the
 * wand's flow and surge, the charge showing as a player's does, and lets go once it is full. With a staff she fills
 * the staff from her pool beforehand (as a player holding left-click does, at the staff's own flow and surge) and
 * casts at once out of what it holds; a staff holding too little casts nothing. {@link MaidMind} decides what to cast
 * at whom and when to fill.
 */
final class MaidCaster {
    /** Ticks between one cast and the next. */
    static final int COOLDOWN = 10;
    /** She casts at what is within this far and in sight. */
    static final double REACH = 16;
    /** Share of her pool (or her staff) a combo must leave, as a lifeform keeps. */
    static final double COMBO_RESERVE = 0.3;
    /** A fill writes what it has poured into the staff this often, and when it stops. */
    private static final int WRITE_EVERY = 10;

    private MaidSpells.@Nullable Page casting, combo;
    private @Nullable LivingEntity at;
    private double chargeTarget, chargeIn, flow, surge;
    private int chargeDone, chargeTicks, chargeTier, cooldown;
    private Element element = Element.ARCANE;
    // A staff fill under way: the very stack, ticks paid, and what those paid that the stack does not show yet.
    private @Nullable ItemStack filling;
    private int fillDone;
    private double fillPending;

    /** Holding a staff: she casts out of what it holds, and fills it beforehand. */
    static boolean staff(EntityMaid maid) {
        return maid.getMainHandItem().getItem() instanceof StaffItem;
    }

    /** The mana her staff holds, counting what a fill has poured and not written yet; 0 without a staff. */
    double stored(EntityMaid maid) {
        ItemStack held = maid.getMainHandItem();
        if (!(held.getItem() instanceof StaffItem)) return 0;
        return StaffItem.stored(held) + (filling == held ? fillPending : 0);
    }

    /** How full her staff is, 0 to 1; 1 without a staff. */
    double staffFill(EntityMaid maid) {
        return maid.getMainHandItem().getItem() instanceof StaffItem s ? Math.min(1, stored(maid) / s.capacity()) : 1;
    }

    /** What she can put into casts right now: her staff's store, or her pool's mana with a wand. */
    double spendable(EntityMaid maid, CreatureMana pool) {
        return staff(maid) ? stored(maid) : pool.mana();
    }

    /** Whether she can cast {@code page} now: a staff must hold all it injects; a wand casts with what her pool has, if any. */
    boolean affords(EntityMaid maid, CreatureMana pool, MaidSpells.Page page) {
        return staff(maid) ? stored(maid) + 1e-9 >= page.injection() : pool.mana() > 0;
    }

    /** Not charging and not cooling down: free to cast. */
    boolean ready() {
        return casting == null && cooldown <= 0;
    }

    boolean charging() {
        return casting != null;
    }

    /**
     * Casts {@code page} (and {@code with} after it, if what she holds can spare both) at {@code target}: at once from
     * a staff, or by starting a wand's charge. False if she cannot.
     */
    boolean cast(EntityMaid maid, CreatureMana pool, MaidSpells.Page page, MaidSpells.@Nullable Page with, LivingEntity target) {
        if (!ready() || !affords(maid, pool, page)) return false;
        double reserve = COMBO_RESERVE * (staff(maid) ? ((StaffItem) maid.getMainHandItem().getItem()).capacity() : pool.maxMana());
        if (with != null && spendable(maid, pool) - page.injection() - with.injection() < reserve) with = null;
        return staff(maid) ? castFromStaff(maid, page, with, target) : beginCharge(maid, pool, page, with, target);
    }

    private boolean castFromStaff(EntityMaid maid, MaidSpells.Page page, MaidSpells.@Nullable Page with, LivingEntity target) {
        ItemStack staff = maid.getMainHandItem();
        stopFill(maid);
        double total = page.injection() + (with == null ? 0 : with.injection());
        StaffItem.store(staff, Math.max(0, StaffItem.stored(staff) - total));
        if (target != maid) face(maid, target);
        Charging.discharge(maid, page.spell().element(), Charging.tier(total));
        maid.swing(InteractionHand.MAIN_HAND);
        Casting.castAs(maid, page.spell(), page.injection(), page.tier().recovery(), target, null, true);
        if (with != null) Casting.castAs(maid, with.spell(), with.injection(), with.tier().recovery(), target, null, true);
        cooldown = COOLDOWN;
        return true;
    }

    /** Starts charging {@code page} (and {@code with} after it): what they inject, or all her pool holds if less. */
    private boolean beginCharge(EntityMaid maid, CreatureMana pool, MaidSpells.Page page, MaidSpells.@Nullable Page with, LivingEntity target) {
        double total = Math.min(page.injection() + (with == null ? 0 : with.injection()), pool.mana());
        flow = MaidSpells.flow(maid);
        surge = MaidSpells.surge(maid, pool);
        if (total <= 0 || (flow <= 0 && surge <= 0)) return false;
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
        return true;
    }

    /** Each tick: the cooldown runs down and a wand's charge goes on. */
    void tick(ServerLevel level, EntityMaid maid, CreatureMana pool) {
        if (cooldown > 0) cooldown--;
        if (casting != null) charge(level, maid, pool);
    }

    /** One tick of a wand's charge: draws its share from her pool and lets go once all of it is in. */
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
        release(maid, pool, target);
    }

    /** Lets go: the charge goes into the page, and what is left of it into the combo page. */
    private void release(EntityMaid maid, CreatureMana pool, LivingEntity target) {
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
        maid.swing(MaidSpells.wandHand(maid));
        double first = Math.min(page.injection(), paid);
        Casting.castAs(maid, page.spell(), first, page.tier().recovery(), target, null, true);
        if (then != null && paid - first > 0) Casting.castAs(maid, then.spell(), paid - first, then.tier().recovery(), target, null, true);
    }

    /**
     * One tick of filling her staff from her pool, at the staff's flow and surge (faster the longer it goes on, as a
     * player's fill is). False once there is nothing more to do: no staff, a full one, or a pool too low for the next share.
     */
    boolean fill(EntityMaid maid, CreatureMana pool) {
        ItemStack held = maid.getMainHandItem();
        if (!(held.getItem() instanceof StaffItem item) || casting != null) {
            stopFill(maid);
            return false;
        }
        if (filling != held) {
            stopFill(maid);
            filling = held;
            fillDone = 0;
            maid.level().playSound(null, maid.getX(), maid.getY(), maid.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 0.5f, 0.8f);
        }
        double stored = stored(maid);
        if (stored >= item.capacity() - 1e-9) {
            stopFill(maid);
            return false;
        }
        double rate = new Charging.Rate(MaidSpells.flow(maid), MaidSpells.surge(maid, pool)).tick(fillDone);
        double share = Math.min(item.capacity() - stored, rate);
        if (share <= 0 || pool.mana() + 1e-9 < share) {
            stopFill(maid);
            return false;
        }
        fillPending += pool.take(share);
        fillDone++;
        stored += share;
        boolean full = stored >= item.capacity() - 1e-9;
        if (full || fillDone % WRITE_EVERY == 0) write(held);
        float along = (float) (stored / item.capacity());
        if (full) {
            maid.level().playSound(null, maid.getX(), maid.getEyeY(), maid.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0f, 1.6f);
        } else if (fillDone % 5 == 0) {
            maid.level().playSound(null, maid.getX(), maid.getEyeY(), maid.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.5f, 0.6f + along);
        }
        if (fillDone % 2 == 0 || full) Charging.show(maid, Element.ARCANE, Charging.tier(stored), along, full);
        maid.setSwingingArms(!full);
        if (full) stopFill(maid);
        return !full;
    }

    boolean filling() {
        return filling != null;
    }

    /** Stops filling: what was poured stays in the staff (or, if the staff left her hand meanwhile, goes back to her pool). */
    void stopFill(EntityMaid maid) {
        ItemStack staff = filling;
        if (staff == null) return;
        if (maid.getMainHandItem() == staff) {
            write(staff);
        } else if (fillPending > 0) {
            CreatureMana pool = MaidMana.of(maid);
            if (pool != null) pool.receiveMana(fillPending);
        }
        fillPending = 0;
        filling = null;
        if (casting == null) {
            Charging.hide(maid);
            maid.setSwingingArms(false);
        }
    }

    private void write(ItemStack staff) {
        if (fillPending <= 0) return;
        StaffItem.store(staff, StaffItem.stored(staff) + fillPending);
        fillPending = 0;
    }

    /** Ends what is under way: a wand's charge goes back to her pool, a fill stays in the staff. */
    void end(EntityMaid maid, CreatureMana pool) {
        stopFill(maid);
        if (chargeIn > 0) pool.receiveMana(chargeIn);
        if (casting != null) Charging.hide(maid);
        chargeIn = 0;
        casting = combo = null;
        at = null;
        maid.setSwingingArms(false);
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
