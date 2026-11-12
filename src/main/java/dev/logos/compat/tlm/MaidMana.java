package dev.logos.compat.tlm;

import org.jspecify.annotations.Nullable;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.CreatureMana;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * A tamed maid's mana: her owner's cap, plus {@link LogosConfig#maidManaPerFavorLevel} of it for each favorability
 * level, refilling by her own mana regeneration (the same attribute a player's pool refills by). While her owner is
 * away she keeps the cap she last had. It is a {@link CreatureMana} on her, so it travels with her into a photo or a
 * soul slab and back.
 */
final class MaidMana {
    /** How often the cap is worked out again and the pool refilled, in ticks. */
    static final int REFRESH = 20;

    private MaidMana() {}

    /** Her pool, or null for a wild maid: only one who serves someone holds mana. A new pool starts full. */
    static @Nullable CreatureMana of(EntityMaid maid) {
        if (maid.getOwnerReference() == null) return null;
        if (!maid.hasData(LogosMod.CREATURE_MANA)) {
            CreatureMana pool = new CreatureMana();
            pool.setMax(cap(maid, 0));
            pool.setMana(pool.maxMana());
            maid.setData(LogosMod.CREATURE_MANA, pool);
            return pool;
        }
        return maid.getData(LogosMod.CREATURE_MANA);
    }

    /** For {@link dev.logos.runtime.ManaVessels}: a tamed maid's pool. */
    static @Nullable ManaVessel vesselOf(Entity e) {
        return e instanceof EntityMaid maid && !maid.level().isClientSide() ? of(maid) : null;
    }

    /** Her owner, wherever they are on the server; null if they are offline. */
    static @Nullable ServerPlayer owner(EntityMaid maid) {
        var ref = maid.getOwnerReference();
        if (ref == null || !(maid.level() instanceof ServerLevel level)) return null;
        if (maid.getOwner() instanceof ServerPlayer near) return near;
        return level.getServer().getPlayerList().getPlayer(ref.getUUID());
    }

    /** Her owner's cap with her favorability's share on top; {@code fallback} while her owner is away. */
    static double cap(EntityMaid maid, double fallback) {
        ServerPlayer owner = owner(maid);
        if (owner == null) return fallback;
        int level = maid.getFavorabilityManager().getLevel();
        return PlayerMagic.of(owner).maxMana * (1 + LogosConfig.maidManaPerFavorLevel() * level);
    }

    /** Every {@link #REFRESH} ticks on the server: her cap follows her owner and favorability, and her pool refills. */
    static void tick(EntityMaid maid) {
        if (maid.level().isClientSide() || (maid.tickCount + maid.getId()) % REFRESH != 0) return;
        CreatureMana pool = of(maid);
        if (pool == null) return;
        pool.setMax(cap(maid, pool.maxMana()));
        var regen = maid.getAttributes().hasAttribute(SpellModifiers.MANA_REGENERATION) ? maid.getAttributeValue(SpellModifiers.MANA_REGENERATION) : 0;
        pool.receiveMana(regen * REFRESH / 20.0);
    }
}
