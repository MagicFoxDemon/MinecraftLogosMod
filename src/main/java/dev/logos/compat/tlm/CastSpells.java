package dev.logos.compat.tlm;

import java.util.Map;

import javax.annotation.Nullable;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.runtime.CreatureMana;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;

/**
 * The 施法 mode's brain behaviour: each tick it runs her {@link MaidMind}, which chooses what to do, and her
 * {@link MaidCaster}, which charges and casts. It runs while she holds a wand or staff and has a mana pool.
 */
final class CastSpells extends Behavior<EntityMaid> {
    /** She casts at what is within this far and in sight. */
    static final double REACH = MaidCaster.REACH;

    private final MaidCaster caster = new MaidCaster();
    private @Nullable MaidMind mind;

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
        CreatureMana pool = MaidMana.of(maid);
        if (pool == null) return;
        if (mind == null) mind = new MaidMind(maid, caster);
        mind.step();
        caster.tick(level, maid, pool);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long now) {
        if (mind != null) mind.halt();
        CreatureMana pool = MaidMana.of(maid);
        if (pool != null) caster.end(maid, pool);
    }
}
