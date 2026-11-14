package dev.logos.item;

import dev.logos.LogosMod;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/** Sends crafting and smithing recipes to clients with the rest of the datapack, so 逻各斯手记 can draw how its items are made. */
@EventBusSubscriber(modid = LogosMod.MODID)
final class GuideRecipeSync {
    private GuideRecipeSync() {}

    @SubscribeEvent
    static void onSync(OnDatapackSyncEvent event) {
        event.sendRecipes(RecipeType.CRAFTING, RecipeType.SMITHING);
    }
}
