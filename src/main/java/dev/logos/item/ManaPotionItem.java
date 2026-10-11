package dev.logos.item;

import java.math.BigInteger;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import dev.logos.LogosMod;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** A drinkable mana reserve strengthened by one tainted shard per brewing round. */
public class ManaPotionItem extends PotionItem {
    public static final BigInteger MANA_PER_SHARD = BigInteger.valueOf(40);
    public static final Codec<BigInteger> SHARD_COUNT_CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            BigInteger count = new BigInteger(value);
            return count.signum() > 0 ? DataResult.success(count) : DataResult.error(() -> "Mana potion shard count must be positive");
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "Invalid mana potion shard count");
        }
    }, BigInteger::toString);

    public ManaPotionItem(Properties properties) {
        super(properties.component(DataComponents.CONSUMABLE, Consumables.DEFAULT_DRINK).usingConvertsTo(Items.GLASS_BOTTLE));
    }

    @Override
    public ItemStack getDefaultInstance() {
        // PotionItem's default would add water contents, including its water-on-block behaviour.
        return new ItemStack(this);
    }

    /** Completed brewing rounds; a plain creative-tab potion starts at one shard. */
    public static BigInteger shards(ItemStack stack) {
        return stack.getOrDefault(LogosMod.MANA_POTION_SHARDS.get(), BigInteger.ONE);
    }

    /** Exact recovery amount, without a fixed-width integer overflow or gameplay ceiling. */
    public static BigInteger mana(ItemStack stack) {
        return shards(stack).multiply(MANA_PER_SHARD);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity user) {
        if (!level.isClientSide() && user instanceof Player player) {
            PlayerMagic magic = PlayerMagic.of(player);
            magic.mana = Math.min(magic.maxMana, magic.mana + mana(stack).doubleValue());
        }
        return super.finishUsingItem(stack, level, user);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.logos.mana_potion.restore", mana(stack).toString()).withStyle(ChatFormatting.AQUA));
        builder.accept(Component.translatable("item.logos.mana_potion.brewing").withStyle(ChatFormatting.GRAY));
    }
}
