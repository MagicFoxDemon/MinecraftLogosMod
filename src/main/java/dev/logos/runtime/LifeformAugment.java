package dev.logos.runtime;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * A small alchemical device (炼金术道具) fitted into a made familiar — a mana lifeform's core or an
 * alchemical colossus's device slots. Implemented by the item. None exist yet; the slots and these
 * hooks are here so they can be added as plain items, for example a slow mana regenerator
 * ({@link #tickAugment}), a mana ward that spends mana instead of health, or a familiar's totem
 * that spends mana to cheat death ({@link #absorbDamage}). The body is also a {@link ManaVessel}.
 */
public interface LifeformAugment {
    /** Every tick, on the server, while the body is awake. */
    default void tickAugment(Mob body, ItemStack stack) {}

    /** Before damage lands: returns what is left of {@code amount} after this device took its share. */
    default float absorbDamage(Mob body, ItemStack stack, DamageSource source, float amount) {
        return amount;
    }
}
