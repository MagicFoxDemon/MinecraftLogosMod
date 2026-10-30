package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.LogosMod;
import dev.logos.lang.Node;
import dev.logos.runtime.StaffCharging;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 长杖: a wand that holds mana of its own, up to its capacity. Hold left-click to fill it from your pool
 * (as fast as a wand of its flow and surge charges); right-click casts the selected spell at once, paid
 * out of the staff (see {@link StaffCharging}). Pages, the page slots and the spell wheel work as on any wand.
 */
public class StaffItem extends WandItem {
    private final double capacity;

    public StaffItem(Properties properties, int slots, double flow, double surge, double capacity) {
        super(properties, slots, flow, surge);
        this.capacity = capacity;
    }

    public double capacity() {
        return capacity;
    }

    /** The mana {@code staff} holds. */
    public static double stored(ItemStack staff) {
        return staff.getOrDefault(LogosMod.STORED_MANA.get(), 0.0);
    }

    public static void store(ItemStack staff, double mana) {
        staff.set(LogosMod.STORED_MANA.get(), mana);
    }

    /** Right-click casts once; the press stays in use until let go, so holding it does not cast again every few ticks. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) return super.use(level, player, hand);
        if (player instanceof ServerPlayer sp && !StaffCharging.cast(sp, player.getItemInHand(hand), hand)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    // Nothing charges while right-click is held: the cast has already gone off. A chant charm casts again.
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (entity instanceof ServerPlayer sp) StaffCharging.repeat(sp, stack, entity.getTicksUsingItem());
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        return false;
    }

    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {}

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return stored(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13 * Math.min(1, stored(stack) / capacity));
    }

    /** The mana HUD's aqua. */
    @Override
    public int getBarColor(ItemStack stack) {
        return 0x55FFFF;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.literal("储能 " + Node.fmt(stored(stack)) + "/" + Node.fmt(capacity)).withStyle(ChatFormatting.AQUA));
        super.appendHoverText(stack, context, display, builder, flag);
    }

    @Override
    protected String usage() {
        return "按住左键充能 · 右键用杖内魔力立即施放";
    }
}
