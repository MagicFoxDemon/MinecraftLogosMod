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
        double before = PlayerMagic.maxMana(sp);
        m.baseMaxMana = Math.max(0, m.baseMaxMana + delta);
        double after = PlayerMagic.maxMana(sp);
        m.mana = Math.min(m.mana, after);
        ItemStack stack = player.getItemInHand(hand);
        stack.consume(1, player);
        level.playSound(null, player.blockPosition(), delta > 0 ? SoundEvents.BEACON_POWER_SELECT : SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 1f, 1f);
        PlayerMagic.alert(sp, Component.translatable("logos.mana.cap_changed", Node.fmt(before), Node.fmt(after))
                .withStyle(delta > 0 ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY));
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(delta > 0 ? "logos.root_mana.raises" : "logos.root_mana.lowers", Node.fmt(Math.abs(delta)))
                .withStyle(ChatFormatting.GRAY));
    }
}
