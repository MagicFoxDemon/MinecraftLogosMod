package dev.logos.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * 药粉: a potion settled into powder. It carries the potion's contents (its effects and colour),
 * so it is eaten for the same effects, tinted the potion's colour, and dissolves back into it.
 * Powders of the same potion stack.
 */
public class PotionPowderItem extends Item {
    public PotionPowderItem(Properties properties) {
        super(properties);
    }

    /** Named after its potion: 药粉（迅捷药水）. */
    @Override
    public Component getName(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()) return super.getName(stack);
        return Component.translatable("item.logos.potion_powder.of",
                Component.translatable(net.minecraft.world.item.alchemy.Potion.getName(contents.potion(), "item.minecraft.potion.effect.")));
    }

    /** Eaten: the potion's effects, as drinking it gives them. */
    @Override
    public ItemStack finishUsingItem(ItemStack stack, net.minecraft.world.level.Level level, net.minecraft.world.entity.LivingEntity entity) {
        if (!level.isClientSide) {
            stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).forEachEffect(effect -> {
                if (effect.getEffect().value().isInstantenous()) effect.getEffect().value().applyInstantenousEffect(entity, entity, entity, effect.getAmplifier(), 1.0);
                else entity.addEffect(effect);
            });
        }
        return super.finishUsingItem(stack, level, entity);
    }

    /** A powder holding {@code contents}, {@code count} of it. */
    public static ItemStack of(Item powder, PotionContents contents, int count) {
        ItemStack stack = new ItemStack(powder, count);
        stack.set(DataComponents.POTION_CONTENTS, contents);
        return stack;
    }
}
