package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 术式余烬: a page burnt by Formula Immolation. The spell survives in it (it can sit on a wand or
 * in a codex, and the copy table can write it onto a new page), but it can no longer be cast.
 */
public class SpellEmberItem extends Item {
    public SpellEmberItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        Spell s = SpellPages.spell(stack);
        return s == null ? super.getName(stack) : Component.translatable(getDescriptionId()).append("《").append(SpellPages.name(stack, s)).append("》");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        Spell s = SpellPages.spell(stack);
        if (s != null) {
            builder.accept(Component.translatable("logos.page.summary", Spell.triggerName(s.trigger()), Node.fmt(s.mana()), Node.fmt(s.stability()))
                    .withStyle(ChatFormatting.GRAY));
        }
        builder.accept(Component.translatable("logos.ember.burnt").withStyle(ChatFormatting.DARK_RED));
        builder.accept(Component.translatable("logos.ember.copy_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
