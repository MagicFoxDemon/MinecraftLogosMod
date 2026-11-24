package dev.logos.item;

import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import dev.logos.LogosMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 魔匣: the latent mana of alchemy products distilled into a casket of living metal. Unlike a crystal it
 * gives only what is lacking: the first casket to give something is opened ({@code logos:stored_mana}
 * holds what is left, and it no longer stacks), and gives the rest next time. Spent, it leaves its empty
 * casket ({@link #empty}), which is filled again on the alchemy table. Full caskets carry no data and
 * stack to 64; their mana is the item's, not a {@code logos:mana_fuel} entry, so nothing burns one whole.
 */
public class ManaCasketItem extends Item {
    private static final int BAR_COLOUR = 0x9FD3EE;

    private final double mana;
    private final Supplier<? extends Item> empty;

    public ManaCasketItem(Properties properties, double mana, Supplier<? extends Item> empty) {
        super(properties);
        this.mana = mana;
        this.empty = empty;
    }

    /** What a full casket of this kind holds. */
    public double fullMana() {
        return mana;
    }

    /** The empty casket this one leaves when spent, and is filled from. */
    public Item empty() {
        return empty.get();
    }

    /** The mana in {@code stack}: a full casket's, an opened one's rest; 0 for anything else. */
    public static double manaIn(ItemStack stack) {
        if (!(stack.getItem() instanceof ManaCasketItem casket)) return 0;
        return stack.getOrDefault(LogosMod.STORED_MANA.get(), casket.mana);
    }

    public static boolean isOpened(ItemStack stack) {
        return stack.getItem() instanceof ManaCasketItem && stack.has(LogosMod.STORED_MANA.get());
    }

    /**
     * One casket of {@code stack}'s kind after {@code taken} of its mana is given: opened with the rest,
     * or its empty casket once nothing is left.
     */
    public static ItemStack afterGiving(ItemStack stack, double taken) {
        ManaCasketItem casket = (ManaCasketItem) stack.getItem();
        double left = manaIn(stack) - taken;
        if (left <= 1e-6) return new ItemStack(casket.empty());
        ItemStack opened = new ItemStack(casket);
        opened.set(LogosMod.STORED_MANA.get(), left);
        opened.set(DataComponents.MAX_STACK_SIZE, 1);
        return opened;
    }

    /**
     * A player gives mana from the casket in their hand: {@code receive} is offered what the casket has
     * and answers what it took. An opened casket of the same kind in the inventory gives first, so a
     * player carries at most one opened casket of each kind; the rest of a new one goes into the
     * inventory (or at their feet), its empty casket likewise. Nothing is used up in creative.
     *
     * @return the mana given
     */
    public static double feed(Player player, ItemStack held, DoubleUnaryOperator receive) {
        ItemStack from = isOpened(held) ? held : openedLike(player, held);
        if (from == null) from = held;
        double taken = receive.applyAsDouble(manaIn(from));
        if (taken <= 0 || player.getAbilities().instabuild) return taken;
        ItemStack after = afterGiving(from, taken);
        if (from.getCount() == 1 && after.is(from.getItem())) {
            // Opened, or opened further, where it lies.
            from.applyComponents(after.getComponentsPatch());
        } else {
            from.shrink(1);
            player.getInventory().placeItemBackInInventory(after);
        }
        return taken;
    }

    /** An opened casket of {@code held}'s kind elsewhere on the player, or null. */
    private static ItemStack openedLike(Player player, ItemStack held) {
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (s != held && s.is(held.getItem()) && isOpened(s)) return s;
        }
        ItemStack off = player.getOffhandItem();
        return off != held && off.is(held.getItem()) && isOpened(off) ? off : null;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return isOpened(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Mth.clamp(Math.round(13f * (float) (manaIn(stack) / mana)), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOUR;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        Component line = isOpened(stack)
                ? Component.translatable("logos.mana_casket.opened", Math.round(manaIn(stack)), Math.round(mana))
                : Component.translatable("logos.mana_casket.mana", Math.round(mana));
        builder.accept(line.copy().withStyle(ChatFormatting.AQUA));
    }
}
