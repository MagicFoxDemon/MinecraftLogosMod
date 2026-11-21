package dev.logos.item;

import dev.logos.LogosMod;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemInstance;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * 杖剑: a wand with a blade at its foot. Right-click charges and casts as a wand does, though slower and with less
 * than a wand of its tier; left-click cuts like a sword (sword damage and speed, sweeps, takes sword enchantments as
 * well as wand ones). In the main hand it favours mana wraps: the imbue rune, its upkeep and every rune the wrap
 * sets off cost {@link #WRAP_DISCOUNT} less, and their damage is {@link #WRAP_BONUS} more (through
 * {@link SpellModifiers#WRAP_COST} and {@link SpellModifiers#WRAP_POWER}, taken when the spell is cast).
 * It is held crown up; the client turns the blade forward on a swing ({@code client/BladeStaffStance}).
 */
public class BladeStaffItem extends WandItem {
    /** Swords' attack speed: 1.6 blows a second. */
    public static final double ATTACK_SPEED = 1.6;
    public static final double WRAP_DISCOUNT = 0.3, WRAP_BONUS = 0.3;

    private static final Identifier WRAP_COST_ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "blade_staff_wrap_cost");
    private static final Identifier WRAP_POWER_ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "blade_staff_wrap_power");

    /**
     * {@code attackDamage} is the blow's damage as the tooltip shows it (the player's own 1 included);
     * the rest are {@link WandItem}'s.
     */
    public BladeStaffItem(Properties properties, int slots, double flow, double surge, double maxMana, double regeneration, double attackDamage) {
        super(properties, slots, flow, surge, maxMana, regeneration, b -> b
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, attackDamage - 1,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, ATTACK_SPEED - 4,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(SpellModifiers.WRAP_COST, new AttributeModifier(WRAP_COST_ID, -WRAP_DISCOUNT,
                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.MAINHAND)
                .add(SpellModifiers.WRAP_POWER, new AttributeModifier(WRAP_POWER_ID, WRAP_BONUS,
                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.MAINHAND));
    }

    /** Sweeps like a sword; it is not in {@code #minecraft:swords}, which would also offer it the durability enchantments. */
    @Override
    public boolean canPerformAction(ItemInstance stack, ItemAbility ability) {
        return ability == ItemAbilities.SWORD_SWEEP || super.canPerformAction(stack, ability);
    }

    @Override
    protected Component usage() {
        return Component.translatable("logos.blade_staff.usage");
    }
}
