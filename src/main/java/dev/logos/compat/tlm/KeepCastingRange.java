package dev.logos.compat.tlm;

import java.util.List;
import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.player.Player;

/**
 * Keeps a maid in the Spellcasting mode at her attacks' reach from her foe: closer while it is out of reach or out of
 * sight, stepping back when it comes too near, standing to cast in between. Like the maid's own ranged modes she does
 * not chase past her work radius from her owner.
 */
final class KeepCastingRange extends net.minecraft.world.entity.ai.behavior.Behavior<EntityMaid> {
    private boolean backing;

    KeepCastingRange() {
        super(Map.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED), 1200);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return !MaidSpells.attacks(MaidSpells.active(maid)).isEmpty();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long now) {
        return maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).filter(LivingEntity::isAlive).isPresent()
                && checkExtraStartConditions(level, maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long now) {
        LivingEntity foe = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (foe == null) return;
        maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(foe, true));
        List<MaidSpells.Page> attacks = MaidSpells.attacks(MaidSpells.active(maid));
        double range = MaidSpells.attackRange(attacks);
        double d = maid.distanceTo(foe);
        boolean leashed = !maid.hasHome() && maid.getOwner() instanceof Player owner && foe.distanceTo(owner) >= maid.getHomeRadius();
        if ((d > range || !maid.hasLineOfSight(foe)) && !leashed) {
            stopBacking(maid);
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(foe, 0.6f, (int) Math.max(1, range * 0.6)));
        } else if (d < range * 0.35) {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            maid.getMoveControl().strafe(-0.5f, 0);
            backing = true;
        } else {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            stopBacking(maid);
        }
    }

    private void stopBacking(EntityMaid maid) {
        if (!backing) return;
        backing = false;
        maid.getMoveControl().strafe(0, 0);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long now) {
        stopBacking(maid);
    }
}
