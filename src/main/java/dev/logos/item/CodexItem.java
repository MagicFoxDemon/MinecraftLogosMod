package dev.logos.item;

import java.util.function.Consumer;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;

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
 * 法式典籍: a chest-like book that holds only spell pages. Held in the off hand, its pages are
 * available to the wand exactly as if they were hung on it.
 */
public class CodexItem extends Item implements HeldItemUIMenuType.HeldItemUI {
    public static final int SIZE = 27;

    public CodexItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) HeldItemUIMenuType.openUI(sp, hand);
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return PageSlotsUI.create(holder.player, new PageContainer(holder.itemStack, SIZE), 9,
                Component.translatable("item.logos.spell_codex"), Component.literal("只能放入术式载页 · 放在副手时，法杖可以使用其中的术式"));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        long n = PageContainer.pages(stack).stream().filter(SpellPages::isInscribed).count();
        builder.accept(Component.literal("收录 " + n + " 篇术式").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.literal("右键打开 · 放在副手时，其中的术式可被法杖使用").withStyle(ChatFormatting.DARK_GRAY));
        builder.accept(Component.literal("被动术式（受伤/攻击/击杀时）拿在任一只手上就会生效").withStyle(ChatFormatting.DARK_GRAY));
    }
}
