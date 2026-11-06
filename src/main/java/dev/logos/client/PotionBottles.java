package dev.logos.client;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.alchemy.BuffPotions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Logos potions draw the bottles of {@code assets/logos/items/potion.json}; every other {@code minecraft:potion} draws
 * whatever the game's definition says, a resource pack's or another mod's. Wrapped around the baked definition rather
 * than replacing its file, and decided by the stack's contents, so however a stack came to exist it looks right.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class PotionBottles implements ItemModel {
    private static final Identifier POTION = Identifier.withDefaultNamespace("potion");
    private static final Identifier LOGOS_POTION = Identifier.fromNamespaceAndPath(LogosMod.MODID, "potion");

    private final ItemModel others;
    private final ItemModel logos;

    private PotionBottles(ItemModel others, ItemModel logos) {
        this.others = others;
        this.logos = logos;
    }

    @SubscribeEvent
    static void wrap(ModelEvent.ModifyBakingResult event) {
        Map<Identifier, ItemModel> models = event.getBakingResult().itemStackModels();
        ItemModel others = models.get(POTION);
        ItemModel logos = models.get(LOGOS_POTION);
        if (others != null && logos != null) models.put(POTION, new PotionBottles(others, logos));
    }

    @Override
    public void update(ItemStackRenderState output, ItemStack item, ItemModelResolver resolver, ItemDisplayContext displayContext,
            @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        (BuffPotions.isLogosPotion(item) ? logos : others).update(output, item, resolver, displayContext, level, owner, seed);
    }
}
