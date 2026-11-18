package dev.logos.port;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * An item drunk like a potion (its animation and sound), for what 26.1 gives a drinking {@code consumable} component.
 * What it does when drunk, and the bottle it leaves, are its food properties.
 */
public class DrinkItem extends Item {
    public DrinkItem(Properties properties) {
        super(properties);
    }

    /** The bottle is left for any drinker, as 26.1's consumables leave it; 1.21.1's food leaves it for a player only. */
    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        FoodProperties food = stack.getFoodProperties(entity);
        ItemStack left = super.finishUsingItem(stack, level, entity);
        if (entity instanceof Player || food == null || food.usingConvertsTo().isEmpty()) return left;
        ItemStack bottle = food.usingConvertsTo().get().copy();
        if (left.isEmpty()) return bottle;
        if (!level.isClientSide()) entity.spawnAtLocation(bottle);
        return left;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public SoundEvent getDrinkingSound() {
        return SoundEvents.GENERIC_DRINK;
    }

    @Override
    public SoundEvent getEatingSound() {
        return SoundEvents.GENERIC_DRINK;
    }
}
