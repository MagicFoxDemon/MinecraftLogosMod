package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.LogosMod;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.lang.Report;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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

    /**
     * The item this page becomes once a spell is written on it (a written page stays itself). Look-alike
     * pages from other mods override this so writing keeps their look; grade, capacity and use stay the same.
     */
    public Item writtenForm() {
        return inscribed ? this : tier.inscribed();
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
        // Copying a spell out of a book is writing it: only learned words, besides what this page said already.
        if (!dev.logos.runtime.RuneKnowledge.mayWrite(sp, spell, page.get(LogosMod.SPELL_SOURCE.get()))) return InteractionResult.FAIL;
        ItemStack written = SpellPages.write(page, src, null);
        page.shrink(1);
        if (page.isEmpty()) player.setItemInHand(hand, written);
        else if (!player.getInventory().add(written)) player.drop(written, false);
        Report.summary(spell, PlayerMagic.of(sp).stability, sp::sendSystemMessage);
        warnCapacity(sp, spell, tier, written);
        return InteractionResult.SUCCESS;
    }

    /** Writing more injection than the page holds is allowed; say what a cast will actually inject. */
    public static void warnCapacity(ServerPlayer player, Spell spell, PageTier tier) {
        warnCapacity(player, spell, tier, new ItemStack(tier.blank()));
    }

    /** As above, naming {@code page}'s own item, so a look-alike page is called by its name. */
    public static void warnCapacity(ServerPlayer player, Spell spell, PageTier tier, ItemStack page) {
        if (spell.mana() <= tier.capacity()) return;
        player.sendSystemMessage(Component.translatable(spell.immolate() ? "logos.page.over_capacity.immolate" : "logos.page.over_capacity.no_immolate",
                Component.translatable(page.getItem().getDescriptionId()), tier.capacity(), Node.fmt(spell.mana())).withStyle(ChatFormatting.GOLD));
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
        Component grade = Component.translatable("logos.page.grade", tier.capacity(), Math.round(tier.recovery() * 100))
                .withStyle(ChatFormatting.AQUA);
        if (!inscribed) {
            builder.accept(grade);
            builder.accept(Component.translatable("logos.page.blank_hint").withStyle(ChatFormatting.GRAY));
            return;
        }
        Spell s = SpellPages.spell(stack);
        if (s == null) {
            builder.accept(Component.translatable("logos.page.broken").withStyle(ChatFormatting.RED));
            return;
        }
        MutableComponent line = Component.translatable("logos.page.summary", Spell.triggerName(s.trigger()), Node.fmt(s.mana()), Node.fmt(s.stability()));
        if (s.affectsPlayers()) line.append(Component.translatable("logos.page.affects_players"));
        builder.accept(line.withStyle(ChatFormatting.GRAY));
        builder.accept(grade);
        if (s.mana() > tier.capacity()) {
            builder.accept(Component.translatable(s.immolate() ? "logos.page.over_capacity_short.immolate" : "logos.page.over_capacity_short.no_immolate",
                    tier.capacity()).withStyle(ChatFormatting.GOLD));
        }
        String voice = stack.get(LogosMod.VOICE.get());
        if (voice != null) builder.accept(Component.translatable("logos.page.voice", voice).withStyle(ChatFormatting.LIGHT_PURPLE));
        builder.accept(Component.translatable("logos.page.use_hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** A page written with 言出法随 shimmers, so it stands out on a wand and in a chest. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(LogosMod.VOICE.get()) || super.isFoil(stack);
    }

}
