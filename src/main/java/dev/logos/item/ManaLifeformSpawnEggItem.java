package dev.logos.item;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

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
import net.minecraft.world.item.component.TooltipDisplay;

/** A tiered vanilla egg: a full lifeform of its level, loyal to the player who raises it. */
public final class ManaLifeformSpawnEggItem extends BoundSpawnEggItem {
    private final int lifeLevel;

    public ManaLifeformSpawnEggItem(Properties props, int lifeLevel) {
        super(props);
        this.lifeLevel = lifeLevel;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
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
