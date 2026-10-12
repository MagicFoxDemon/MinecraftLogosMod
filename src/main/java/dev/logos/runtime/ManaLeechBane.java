package dev.logos.runtime;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/** Bane of Arthropods expels a leech's stored mana after a successful weapon hit. */
public final class ManaLeechBane {
    private ManaLeechBane() {}

    /** Read the actual melee weapon; a spell or projectile does not inherit the caster's sword. */
    public static int level(DamageSource source) {
        if (!source.isDirect() || !(source.getDirectEntity() instanceof LivingEntity attacker)) return 0;
        if (!source.is(DamageTypeTags.IS_PLAYER_ATTACK) && !source.is(DamageTypes.MOB_ATTACK)
                && !source.is(DamageTypes.MOB_ATTACK_NO_AGGRO) && !source.is(DamageTypes.SPEAR)) return 0;
        ItemStack weapon = source.getWeaponItem();
        if (weapon == null || weapon.isEmpty()) return 0;
        var bane = attacker.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.BANE_OF_ARTHROPODS);
        return Math.max(0, weapon.getEnchantmentLevel(bane));
    }

    /** Level I expels 20%, level II 30%, and command-created higher levels stop at 100%. */
    public static double fraction(int level) {
        return level <= 0 ? 0 : Math.min(1, (level + 1.0) * 0.1);
    }

    /** Reduce the pool before death can release its remaining 80%; cumulative absorption is retained. */
    public static double disperse(ManaLeech leech, ServerLevel level, int enchantmentLevel) {
        double amount = leech.mana() * fraction(enchantmentLevel);
        if (!(amount > 0) || !Double.isFinite(amount)) return 0;
        leech.setStoredMana(Math.max(0, leech.mana() - amount));
        FreeManaEntity.releaseAt(level, leech.getBoundingBox().getCenter(), amount);
        return amount;
    }
}
