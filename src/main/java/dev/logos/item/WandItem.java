package dev.logos.item;

import java.util.function.Consumer;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;

import dev.logos.lang.Node;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;

/**
 * The Logos wand. Spell pages hang on it (how many depends on its tier).
 * <ul>
 * <li>Right-click: cast the selected spell (choose it on the spell wheel, default key R).</li>
 * <li>Sneak + right-click: open the page slots.</li>
 * </ul>
 * A codex in the off hand adds its pages to the wand's.
 */
public class WandItem extends Item implements HeldItemUIMenuType.HeldItemUI {
    private final int slots;

    public WandItem(Properties properties, int slots) {
        super(properties);
        this.slots = slots;
    }

    /** Reuses the sword swing and recovery timer for the basic wand's melee handling. */
    public static Properties swordHandling(Properties properties) {
        return properties.component(DataComponents.SWING_ANIMATION, SwingAnimation.DEFAULT)
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_SPEED,
                                new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.4F, AttributeModifier.Operation.ADD_VALUE),
                                EquipmentSlotGroup.MAINHAND)
                        .build());
    }

    public int slots() {
        return slots;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack wand = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) HeldItemUIMenuType.openUI(sp, hand);
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        SpellBag.Entry entry = SpellBag.selected(sp, wand);
        if (entry == null) {
            PlayerMagic.alert(sp, Component.literal("法杖上没有术式：潜行右键挂上术式载页，或在副手拿法式典籍").withStyle(ChatFormatting.YELLOW));
            return InteractionResult.FAIL;
        }
        if (entry.spell().isPassive()) {
            PlayerMagic.alert(sp, Component.literal("《" + entry.spell().name() + "》是被动术式，会在"
                    + dev.logos.lang.Spell.triggerZh(entry.spell().trigger()) + "自动触发，无需施放").withStyle(ChatFormatting.YELLOW));
            return InteractionResult.FAIL;
        }
        if (Casting.cast(sp, entry, wand, null, null) != null) player.getCooldowns().addCooldown(wand, 6);
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return PageSlotsUI.create(holder.player, new PageContainer(holder.itemStack, slots), Math.min(slots, 9),
                holder.itemStack.getHoverName(), Component.literal("挂上术式载页 · 按术式轮盘键（默认 R）选择要施放的术式"));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return !PageContainer.pages(stack).isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        long n = PageContainer.pages(stack).stream().filter(SpellPages::isInscribed).count();
        builder.accept(Component.literal("术式槽 " + n + "/" + slots).withStyle(ChatFormatting.GRAY));
        var pages = PageContainer.pages(stack).stream().filter(SpellPages::isInscribed).toList();
        if (!pages.isEmpty()) {
            int sel = Math.floorMod(stack.getOrDefault(dev.logos.LogosMod.SELECTED.get(), 0), pages.size());
            var s = SpellPages.spell(pages.get(sel));
            if (s != null) builder.accept(Component.literal("当前：《" + s.name() + "》 注入 " + Node.fmt(s.mana())).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        builder.accept(Component.literal("右键施放 · 潜行右键挂载术式载页 · R 打开术式轮盘").withStyle(ChatFormatting.DARK_GRAY));
    }
}
