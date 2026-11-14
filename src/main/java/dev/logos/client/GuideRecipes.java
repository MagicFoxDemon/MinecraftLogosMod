package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;

/** The crafting and smithing recipes the server sent (item/GuideRecipeSync), for 逻各斯手记 to draw. */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class GuideRecipes {
    private static RecipeMap recipes = RecipeMap.EMPTY;

    private GuideRecipes() {}

    @SubscribeEvent
    static void onReceived(RecipesReceivedEvent event) {
        recipes = event.getRecipeMap();
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        recipes = RecipeMap.EMPTY;
    }

    /** What the recipes are drawn with: the level's registries and fuels; null outside a world. */
    static @org.jspecify.annotations.Nullable ContextMap context() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : SlotDisplayContext.fromLevel(level);
    }

    /** How every crafting and smithing recipe that makes {@code item} is shown, in the order the server sent them. */
    static List<RecipeDisplay> making(Item item) {
        ContextMap context = context();
        List<RecipeDisplay> out = new ArrayList<>();
        if (context == null) return out;
        for (var holder : recipes.byType(RecipeType.CRAFTING)) keep(holder.value().display(), item, context, out);
        for (var holder : recipes.byType(RecipeType.SMITHING)) keep(holder.value().display(), item, context, out);
        return out;
    }

    private static void keep(List<RecipeDisplay> displays, Item item, ContextMap context, List<RecipeDisplay> into) {
        for (RecipeDisplay display : displays) {
            if (display.result().resolveForFirstStack(context).is(item)) into.add(display);
        }
    }
}
