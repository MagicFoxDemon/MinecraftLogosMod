package dev.logos.item;

import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.logos.LogosMod;
import dev.logos.runtime.Lifeforms;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 魔力电池: the latent mana of its materials sealed into a casing, burnt like any mana fuel (its mana is
 * its {@code logos:mana_fuel} entry). It is fuel, not a store: nothing charges it again. Used up, it
 * leaves its casing one use more worn ({@link #shellOf}); a casing on its last use is used up with it.
 */
public class ManaBatteryItem extends Item {
    private final Supplier<? extends Item> casing;

    public ManaBatteryItem(Properties properties, Supplier<? extends Item> casing) {
        super(properties);
        this.casing = casing;
    }

    /** The casing the battery is sealed in, and given back in when it is used up. */
    public Item casing() {
        return casing.get();
    }

    /** The empty casing a spent battery leaves, or empty if it is no battery or its casing is worn through. */
    public static ItemStack shellOf(ItemStack battery) {
        if (!(battery.getItem() instanceof ManaBatteryItem b)) return ItemStack.EMPTY;
        ItemStack shell = new ItemStack(b.casing());
        int wear = battery.getOrDefault(LogosMod.CASING_WEAR.get(), 0) + 1;
        if (wear >= shell.getMaxDamage()) return ItemStack.EMPTY;
        shell.setDamageValue(wear);
        return shell;
    }

    /**
     * Uses up one mana fuel the player holds, as feeding a lifeform does: nothing in creative; a battery
     * puts its empty casing in the player's inventory (or at their feet when it is full).
     */
    public static void useUp(Player player, ItemStack held) {
        if (player.getAbilities().instabuild) return;
        ItemStack shell = shellOf(held);
        held.shrink(1);
        if (!shell.isEmpty()) player.getInventory().placeItemBackInInventory(shell);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        Lifeforms.ManaFuel fuel = stack.typeHolder().getData(LogosMod.MANA_FUEL);
        if (fuel != null) builder.accept(Component.translatable("logos.mana_battery.mana", Math.round(fuel.mana())).withStyle(ChatFormatting.AQUA));
        int max = new ItemStack(casing()).getMaxDamage();
        int left = max - stack.getOrDefault(LogosMod.CASING_WEAR.get(), 0);
        builder.accept(Component.translatable("logos.mana_battery.casing", left, max).withStyle(ChatFormatting.GRAY));
    }
}
