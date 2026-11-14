package dev.logos.item;

import java.util.function.Consumer;

import dev.logos.lang.Node;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 魔力药剂: drunk, it raises the drinker's permanent mana cap ({@link PlayerMagic#baseMaxMana}) for good.
 * There is one per survival page grade, in two lines kept apart: the brewed ones from the alchemy table
 * and the ancient ones found only in chests. Each item counts on its own, at most {@link #LIMIT} bottles a
 * player, so neither way of getting them stands in for the other: a player who wants the whole cap does both.
 */
public final class ManaElixirItem extends Item {
    /** Bottles of one item a player may drink. */
    public static final int LIMIT = 3;

    /** The survival grades: what a bottle adds and the color of its liquid. */
    public enum Grade {
        BASIC(PageTier.BASIC, 10, 0xFF63CCF2, Rarity.COMMON),
        CRYSTAL(PageTier.CRYSTAL, 20, 0xFFB57EDC, Rarity.COMMON),
        ECHO(PageTier.ECHO, 40, 0xFF1F8A8F, Rarity.UNCOMMON),
        TRANSMUTED(PageTier.TRANSMUTED, 80, 0xFFD4A84A, Rarity.UNCOMMON),
        CHALK(PageTier.CHALK, 160, 0xFFEDE8DA, Rarity.RARE),
        FIRMAMENT(PageTier.FIRMAMENT, 320, 0xFF4F7BE8, Rarity.RARE),
        SAGE(PageTier.SAGE, 640, 0xFFD6403A, Rarity.EPIC);

        public final PageTier tier;
        /** Permanent cap one bottle adds. */
        public final double amount;
        /** ARGB of the liquid (the item model's constant tint). */
        public final int color;
        public final Rarity rarity;

        Grade(PageTier tier, double amount, int color, Rarity rarity) {
            this.tier = tier;
            this.amount = amount;
            this.color = color;
            this.rarity = rarity;
        }

        /** Registry name: {@code mana_elixir} and {@code ancient_mana_elixir} for the basic grade, the tier's id before it otherwise. */
        public String id(boolean ancient) {
            String name = this == BASIC ? "mana_elixir" : tier.id + "_mana_elixir";
            return ancient ? "ancient_" + name : name;
        }
    }

    private final Grade grade;
    private final boolean ancient;

    public ManaElixirItem(Properties properties, Grade grade, boolean ancient) {
        super(properties);
        this.grade = grade;
        this.ancient = ancient;
    }

    /** Drunk like a potion, its bottle given back; the ancient ones shimmer. */
    public static Properties properties(Properties p, Grade grade, boolean ancient) {
        p = p.stacksTo(16).rarity(grade.rarity).usingConvertsTo(Items.GLASS_BOTTLE)
                .component(DataComponents.CONSUMABLE, Consumables.defaultDrink().build());
        return ancient ? p.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) : p;
    }

    public Grade grade() {
        return grade;
    }

    public boolean ancient() {
        return ancient;
    }

    /** How many of this item {@code p} has drunk. */
    public int drunk(Player p) {
        return PlayerMagic.of(p).elixirs.getOrDefault(key(), 0);
    }

    private String key() {
        return BuiltInRegistries.ITEM.getKey(this).toString();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp && drunk(sp) >= LIMIT) {
            full(sp);
            return InteractionResult.FAIL;
        }
        return super.use(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer p) {
            int n = drunk(p);
            if (n >= LIMIT) {
                full(p);
                return stack;
            }
            PlayerMagic m = PlayerMagic.of(p);
            double before = PlayerMagic.maxMana(p);
            m.baseMaxMana += grade.amount;
            m.elixirs.put(key(), n + 1);
            level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.6f, 1.4f);
            PlayerMagic.alert(p, Component.translatable(getDescriptionId()).append(Component.literal(" " + (n + 1) + "/" + LIMIT
                    + " · 魔力上限 " + Node.fmt(before) + " → " + Node.fmt(PlayerMagic.maxMana(p)))).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return super.finishUsingItem(stack, level, entity);
    }

    private void full(ServerPlayer p) {
        PlayerMagic.alert(p, Component.literal("这种药剂你已饮满 " + LIMIT + " 瓶，再喝也不会增加魔力上限").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.literal("饮下后，永久增加 " + Node.fmt(grade.amount) + " 点魔力上限").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.literal("每人最多饮用 " + LIMIT + " 瓶").withStyle(ChatFormatting.DARK_GRAY));
        builder.accept(Component.literal(ancient ? "古代遗物，只在遗迹的箱子里找到；与炼金配制的药剂分开计数"
                : "炼金台配制；与遗迹里找到的古代药剂分开计数").withStyle(ChatFormatting.DARK_GRAY));
    }
}
