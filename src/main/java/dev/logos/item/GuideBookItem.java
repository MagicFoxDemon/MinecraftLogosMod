package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 逻各斯手记: Logos's own guide, read in a screen of its own on the client ({@code client/GuideScreen}). Every player
 * is handed one the first time they join; another is a book and a lapis lazuli.
 */
public class GuideBookItem extends Item {
    /** Where a new player's guide goes when it is free. */
    private static final int GIFT_SLOT = 8;

    public GuideBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) dev.logos.client.ClientHooks.openGuide();
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.logos.guide_book.tooltip").withStyle(ChatFormatting.GRAY));
    }

    /**
     * Hands {@code player} the guide if they have never been handed one, and the server hands it out. Kept through
     * death, so each player is handed it once; one who joined while it was not handed out gets theirs later.
     */
    public static void welcome(ServerPlayer player) {
        if (!LogosConfig.giveGuideOnFirstJoin() || player.getData(LogosMod.GUIDE_GIVEN.get())) return;
        player.setData(LogosMod.GUIDE_GIVEN.get(), true);
        ItemStack book = new ItemStack(LogosMod.GUIDE_BOOK.get());
        // The hotbar's last slot: in sight, and not in the hand a new player starts with.
        var inventory = player.getInventory();
        int slot = inventory.getItem(GIFT_SLOT).isEmpty() ? GIFT_SLOT : inventory.getFreeSlot();
        if (slot >= 0) inventory.setItem(slot, book);
        else player.drop(book, false);
        player.sendSystemMessage(Component.translatable("logos.guide.welcome").withStyle(ChatFormatting.GOLD));
    }
}
