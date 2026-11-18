package dev.logos.item;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.ManaLifeform;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A tiered vanilla egg: a full lifeform of its level, loyal to the player who raises it. */
public final class ManaLifeformSpawnEggItem extends BoundSpawnEggItem {
    private final int lifeLevel;

    public ManaLifeformSpawnEggItem(Properties props, int lifeLevel) {
        super(LogosMod.MANA_LIFEFORM, props);
        this.lifeLevel = lifeLevel;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Consumer<Component> builder = tooltip::add;
        builder.accept(Component.translatable("item.logos.mana_lifeform_spawn_egg.mana", Math.round(ManaLifeform.maxMana(lifeLevel)))
                .withStyle(ChatFormatting.AQUA));
    }

    @Override
    EntityType<?> boundType() {
        return LogosMod.MANA_LIFEFORM.get();
    }

    @Override
    boolean allowed() {
        return LogosConfig.maxLifeforms() > 0;
    }

    @Override
    void prepare(Entity entity, @Nullable ServerPlayer owner) {
        if (entity instanceof ManaLifeform lf) {
            lf.setLifeLevel(lifeLevel);
            lf.setMana(lf.maxMana());
            if (owner != null) Lifeforms.bindSummoned(lf, owner);
        }
    }
}
