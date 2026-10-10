package dev.logos.runtime;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

/**
 * A small alchemical device fitted into a mana lifeform's core (炼金术道具): implemented by the
 * item. A lifeform has one device slot from level 2 and a second at level 5. None exist yet; the
 * slots and these hooks are here so they can be added as plain items, for example a slow mana
 * regenerator ({@link #tickAugment}) or a mana ward that spends mana instead of health
 * ({@link #absorbDamage}).
 */
public interface LifeformAugment {
    /** Every tick, on the server, while the lifeform is awake. */
    default void tickAugment(ManaLifeform lf, ItemStack stack) {}

    /** Before damage lands: returns what is left of {@code amount} after this device took its share. */
    default float absorbDamage(ManaLifeform lf, ItemStack stack, DamageSource source, float amount) {
        return amount;
    }
}
