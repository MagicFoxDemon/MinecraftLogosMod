package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.lang.Report;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.LevelReader;

/**
 * 术式载页. The empty page and the inscribed page are two items (so recipes can ask for an empty
 * one) sharing this class. Pages are written at the inscription table, or from a book & quill held
 * in the other hand.
 */
public class SpellPageItem extends Item {
    private final PageTier tier;
    private final boolean inscribed;

    public SpellPageItem(Properties properties, PageTier tier, boolean inscribed) {
        super(properties);
        this.tier = tier;
        this.inscribed = inscribed;
    }

    public PageTier tier() {
        return tier;
    }

    public boolean inscribed() {
        return inscribed;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        String src = Books.read(other);
        if (src == null) return InteractionResult.PASS;
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        Spell spell;
        try {
            spell = Casting.compile(src);
        } catch (LogosError e) {
            Casting.message(sp, e);
            return InteractionResult.FAIL;
        }
        ItemStack page = player.getItemInHand(hand);
        ItemStack written = SpellPages.write(page, src, null);
        page.shrink(1);
        if (page.isEmpty()) player.setItemInHand(hand, written);
        else if (!player.getInventory().add(written)) player.drop(written, false);
        Report.summary(spell, PlayerMagic.of(sp).stability, sp::sendSystemMessage);
        warnCapacity(sp, spell, tier);
        return InteractionResult.SUCCESS;
    }

    /** Writing more injection than the page holds is allowed; say what a cast will actually inject. */
    public static void warnCapacity(ServerPlayer player, Spell spell, PageTier tier) {
        if (spell.mana() <= tier.capacity()) return;
        player.sendSystemMessage(Component.literal("⚠ 这张" + Component.translatable(tier.blank().getDescriptionId()).getString()
                + "只能承载 " + tier.capacity() + " 魔力，术式要求注入 " + Node.fmt(spell.mana()) + "：施放时只会注入 " + tier.capacity()
                + (spell.immolate() ? "（持焚式法杖施放可放大上限，但载页会焚毁）" : "（术式关闭了焚式）")).withStyle(ChatFormatting.GOLD));
    }

    /** Lets sneak + right-click with a page in hand still reach the inscription table (to take its page). */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public Component getName(ItemStack stack) {
        Spell s = SpellPages.spell(stack);
        return s == null ? super.getName(stack) : Component.translatable(getDescriptionId()).append("《").append(SpellPages.name(stack, s)).append("》");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        Component grade = Component.literal("承载 " + tier.capacity() + " 魔力 · 熵化魔力回收 " + Math.round(tier.recovery() * 100) + "%")
                .withStyle(ChatFormatting.AQUA);
        if (!inscribed) {
            builder.accept(grade);
            builder.accept(Component.literal("在法式编纂台上铭刻，或另一只手拿写好的书右键").withStyle(ChatFormatting.GRAY));
            return;
        }
        Spell s = SpellPages.spell(stack);
        if (s == null) {
            builder.accept(Component.literal("术式已损坏").withStyle(ChatFormatting.RED));
            return;
        }
        builder.accept(Component.literal(Spell.triggerZh(s.trigger()) + " · 注入 " + Node.fmt(s.mana()) + " · 稳定度 " + Node.fmt(s.stability())
                + (s.affectsPlayers() ? " · 影响玩家" : "")).withStyle(ChatFormatting.GRAY));
        builder.accept(grade);
        if (s.mana() > tier.capacity()) {
            builder.accept(Component.literal("⚠ 超出载页承载：施放时只注入 " + tier.capacity()
                    + (s.immolate() ? "，焚式可放大（载页焚毁）" : "，术式不允许焚式")).withStyle(ChatFormatting.GOLD));
        }
        builder.accept(Component.literal("挂到法杖上（潜行右键法杖）或放进法式典籍").withStyle(ChatFormatting.DARK_GRAY));
    }

}
