package dev.logos.client;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.alchemy.AlchemyRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/**
 * The alchemy recipes the server sent this client, for recipe viewers. Caught ahead of everyone
 * else, since JEI reloads its plugins while handling the same event.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ClientAlchemyRecipes {
    private static List<RecipeHolder<AlchemyRecipe>> recipes = List.of();

    private ClientAlchemyRecipes() {}

    public static List<RecipeHolder<AlchemyRecipe>> all() {
        return recipes;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onReceived(RecipesReceivedEvent event) {
        if (event.getRecipeTypes().contains(LogosMod.ALCHEMY_RECIPE.get())) recipes = List.copyOf(event.getRecipeMap().byType(LogosMod.ALCHEMY_RECIPE.get()));
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        recipes = List.of();
    }
}
