package dev.logos.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 杖饰: hung in one of a wand's page slots (not a codex's) in place of a page, it changes how the wand
 * casts. Each charm is one item; what it does is read where the wand casts, through {@link #on}.
 */
public class WandCharmItem extends Item {
    private final Component effect;

    /** {@code effect}: what it does, for the tooltip. */
    public WandCharmItem(Properties properties, Component effect) {
        super(properties);
        this.effect = effect;
    }

    /** Whether {@code charm} hangs in one of {@code wand}'s slots. */
    public static boolean on(ItemStack wand, Item charm) {
        return PageContainer.pages(wand).stream().anyMatch(s -> s.is(charm));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(effect.copy().withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("logos.charm.hang_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
