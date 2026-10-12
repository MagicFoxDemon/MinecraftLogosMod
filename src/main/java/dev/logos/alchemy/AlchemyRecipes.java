package dev.logos.alchemy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.logos.LogosMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeMap;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * What each process can make, as far as this side knows: the server sends its alchemy recipes to
 * clients, so a card on the table screen can offer a route for each product before any is made.
 */
public final class AlchemyRecipes {
    private static volatile Map<Process, List<Item>> products = Map.of();

    private AlchemyRecipes() {}

    /** Takes in the recipes a client was sent. */
    public static void learn(RecipeMap recipes) {
        Map<Process, List<Item>> found = new EnumMap<>(Process.class);
        for (Process p : Process.values()) found.put(p, new ArrayList<>());
        for (var holder : recipes.byType(LogosMod.ALCHEMY_RECIPE.get())) {
            List<Item> list = found.get(holder.value().process());
            for (ItemStackTemplate result : holder.value().results()) {
                Item item = result.item().value();
                // Bottles given back are not routed, so they get no choice.
                if (!ProcessCard.isContainer(ItemResource.of(item)) && !list.contains(item)) list.add(item);
            }
        }
        products = found;
    }

    /** The products of {@code process}'s recipes, in recipe order, without the bottles they give back. */
    public static List<Item> products(Process process) {
        return products.getOrDefault(process, List.of());
    }
}
