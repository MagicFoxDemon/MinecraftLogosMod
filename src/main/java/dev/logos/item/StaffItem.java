package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.LogosMod;
import dev.logos.lang.Node;
import dev.logos.runtime.StaffCharging;
import dev.logos.runtime.VoiceCasting;
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
        this(properties, slots, flow, surge, capacity, 0, 0);
    }

    /** With what it adds in the main hand to the mana cap and its refill, as {@link WandItem}'s. */
    public StaffItem(Properties properties, int slots, double flow, double surge, double capacity, double maxMana, double regeneration) {
        super(properties, slots, flow, surge, maxMana, regeneration);
        this.capacity = capacity;
    }

    public double capacity() {
        return capacity;
    }

    /** The mana written on {@code staff}. While it fills, {@link StaffCharging#held} also counts what is on its way to it. */
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
        ItemStack staff = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp && !(VoiceCasting.armed(staff) ? VoiceCasting.begin(sp, staff, hand) : StaffCharging.cast(sp, staff, hand))) return InteractionResult.FAIL;
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
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        if (entity instanceof ServerPlayer sp) VoiceCasting.cancel(sp);
    }

    /** Thrown mid-fill: what has gone in is written first, so it leaves with the staff (throwing splits off a copy of it). */
    @Override
    public boolean onDroppedByPlayer(ItemStack item, Player player) {
        if (player instanceof ServerPlayer sp) StaffCharging.stop(sp);
        return super.onDroppedByPlayer(item, player);
    }

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
        builder.accept(Component.translatable("logos.staff.stored", Node.fmt(stored(stack)), Node.fmt(capacity)).withStyle(ChatFormatting.AQUA));
        super.appendHoverText(stack, context, display, builder, flag);
    }

    @Override
    protected Component usage() {
        return Component.translatable("logos.staff.usage");
    }
}
