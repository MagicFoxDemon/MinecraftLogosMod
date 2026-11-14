package dev.logos.item;

import java.util.function.Consumer;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;

import dev.logos.lang.Node;
import dev.logos.LogosMod;
import dev.logos.runtime.Charging;
import dev.logos.runtime.SpellModifiers;
import dev.logos.runtime.VoiceCasting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.Level;

/**
 * The Logos wand. Spell pages hang on it (how many depends on its tier); the page caps how much a
 * cast injects, the wand how fast it gathers it (see {@link Charging}).
 * <ul>
 * <li>Hold right-click: charge the selected spell (choose it on the spell wheel, default key R); let go once full to cast.</li>
 * <li>Sneak + right-click: open the page slots.</li>
 * </ul>
 * A codex in the off hand adds its pages to the wand's. Charging neither slows the player nor changes their view.
 */
public class WandItem extends Item implements HeldItemUIMenuType.HeldItemUI {
    private final int slots;

    /** {@code flow} and {@code surge} are what the wand adds, in the main hand, to the channel attributes. */
    public WandItem(Properties properties, int slots, double flow, double surge) {
        this(properties, slots, flow, surge, 0, 0);
    }

    /**
     * Besides flow and surge, {@code maxMana} and {@code regeneration} (mana a second) are what the wand adds
     * in the main hand to its bearer's mana cap and refill; a better wand holds and draws more.
     */
    public WandItem(Properties properties, int slots, double flow, double surge, double maxMana, double regeneration) {
        super(properties.component(DataComponents.USE_EFFECTS, new UseEffects(true, false, 1.0f))
                .attributes(attributes(flow, surge, maxMana, regeneration)));
        this.slots = slots;
    }

    private static ItemAttributeModifiers attributes(double flow, double surge, double maxMana, double regeneration) {
        var b = ItemAttributeModifiers.builder()
                .add(LogosMod.CHANNEL_FLOW, new AttributeModifier(FLOW_ID, flow, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(LogosMod.CHANNEL_SURGE, new AttributeModifier(SURGE_ID, surge, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        if (maxMana != 0) b.add(SpellModifiers.MAX_MANA, new AttributeModifier(MAX_MANA_ID, maxMana, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);
        if (regeneration != 0) b.add(SpellModifiers.MANA_REGENERATION, new AttributeModifier(REGENERATION_ID, regeneration,
                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        return b.build();
    }

    private static final Identifier FLOW_ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "wand_flow");
    private static final Identifier SURGE_ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "wand_surge");
    private static final Identifier MAX_MANA_ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "wand_max_mana");
    private static final Identifier REGENERATION_ID = Identifier.fromNamespaceAndPath(LogosMod.MODID, "wand_mana_regeneration");

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
        if (player instanceof ServerPlayer sp && !(VoiceCasting.armed(wand) ? VoiceCasting.begin(sp, wand, hand) : Charging.begin(sp, wand, hand))) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return 72000;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof ServerPlayer sp)) return;
        if (VoiceCasting.listening(sp)) VoiceCasting.tick(sp, stack);
        else Charging.tick(sp, stack);
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        return entity instanceof ServerPlayer sp && Charging.release(sp);
    }

    /** Let go too early, switched away, or anything else that stops the charge: the mana goes back out free. */
    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        if (!(entity instanceof ServerPlayer sp)) return;
        Charging.cancel(sp);
        VoiceCasting.cancel(sp);
    }

    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return PageSlotsUI.create(holder.player, new PageContainer(holder.itemStack, slots, true), Math.min(slots, 9),
                holder.itemStack.getHoverName(), Component.literal("挂上术式载页或杖饰 · 按术式轮盘键（默认 R）选择要施放的术式"));
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
        if (VoiceCasting.armed(stack)) {
            long voiced = pages.stream().filter(p -> p.has(LogosMod.VOICE.get())).count();
            builder.accept(Component.literal("当前：🗣 言出法随（" + voiced + " 个术式可念出）").withStyle(ChatFormatting.LIGHT_PURPLE));
        } else if (!pages.isEmpty()) {
            int sel = Math.floorMod(stack.getOrDefault(dev.logos.LogosMod.SELECTED.get(), 0), pages.size());
            var s = SpellPages.spell(pages.get(sel));
            if (s != null) builder.accept(Component.literal("当前：《" + s.name() + "》 注入 " + Node.fmt(s.mana())).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        builder.accept(Component.literal(usage() + " · 潜行右键挂载术式载页 · R 打开术式轮盘").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** How this wand casts, for its tooltip. */
    protected String usage() {
        return "按住右键蓄力，蓄满后松开施放";
    }
}
