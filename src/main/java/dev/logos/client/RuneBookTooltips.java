package dev.logos.client;

import dev.logos.LogosMod;
import dev.logos.item.RuneBookItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** A 符文之书's tooltip says how much it still has to teach whoever looks at it; the player's learning is synced to them. */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class RuneBookTooltips {
    private RuneBookTooltips() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof RuneBookItem book) || event.getEntity() == null) return;
        int all = book.kind().pool().size(), left = book.kind().left(event.getEntity()).size();
        event.getToolTip().add(left == 0
                ? Component.translatable("logos.rune_book.all_learned").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("logos.rune_book.left", left, all).withStyle(ChatFormatting.DARK_AQUA));
    }
}
