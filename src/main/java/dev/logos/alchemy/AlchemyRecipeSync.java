package dev.logos.alchemy;

import dev.logos.LogosMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/** Sends alchemy recipes to clients with the rest of the datapack, so recipe viewers (JEI) can list them. */
@EventBusSubscriber(modid = LogosMod.MODID)
final class AlchemyRecipeSync {
    private AlchemyRecipeSync() {}

    @SubscribeEvent
    static void onSync(OnDatapackSyncEvent event) {
        event.sendRecipes(LogosMod.ALCHEMY_RECIPE.get());
    }
}
