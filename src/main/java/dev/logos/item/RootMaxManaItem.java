package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.lang.Node;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 根源权限 / 根源剥离: creative-only items that move the user's mana cap for good, by {@code delta}
 * (positive raises, negative lowers). The cap never drops below zero, and the pool is cut down to a
 * lowered cap. Spent on use outside creative mode.
 */
public final class RootMaxManaItem extends Item {
    public static final double AMOUNT = 64000;

    private final double delta;

    public RootMaxManaItem(Properties props, double delta) {
        super(props);
        this.delta = delta;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        PlayerMagic m = PlayerMagic.of(sp);
        double before = m.maxMana;
        m.maxMana = Math.max(0, m.maxMana + delta);
        m.mana = Math.min(m.mana, m.maxMana);
        ItemStack stack = player.getItemInHand(hand);
        stack.consume(1, player);
        level.playSound(null, player.blockPosition(), delta > 0 ? SoundEvents.BEACON_POWER_SELECT : SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 1f, 1f);
        PlayerMagic.alert(sp, Component.literal("魔力上限 " + Node.fmt(before) + " → " + Node.fmt(m.maxMana))
                .withStyle(delta > 0 ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY));
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.literal((delta > 0 ? "使用后，永久增加 " : "使用后，永久降低 ") + Node.fmt(Math.abs(delta)) + " 点魔力上限")
                .withStyle(ChatFormatting.GRAY));
    }
}
