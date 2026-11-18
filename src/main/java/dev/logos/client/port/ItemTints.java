package dev.logos.client.port;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.LogosMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Stands in for 26.1's item model tints: the {@code tints} of each {@code assets/logos/items/<id>.json} (the same files
 * as on main), read when resources load, colour the item's layers by tint index. Only the two kinds Logos uses:
 * {@code minecraft:constant} and {@code minecraft:potion}. The models themselves come from the build
 * ({@code generateItemModels}).
 */
public final class ItemTints {
    private sealed interface Tint permits Constant, Potion {}

    private record Constant(int value) implements Tint {}

    private record Potion(int fallback) implements Tint {}

    private static Map<Item, Tint[]> tints = Map.of();

    private ItemTints() {}

    /** From the mod bus: one colour handler over every Logos item, looking up the tints of the resources now loaded. */
    public static void register(RegisterColorHandlersEvent.Item event) {
        Item[] items = BuiltInRegistries.ITEM.stream().filter(i -> BuiltInRegistries.ITEM.getKey(i).getNamespace().equals(LogosMod.MODID))
                .toArray(Item[]::new);
        event.register(ItemTints::color, items);
    }

    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            Map<Item, Tint[]> read = new HashMap<>();
            manager.listResources("items", path -> path.getNamespace().equals(LogosMod.MODID) && path.getPath().endsWith(".json"))
                    .forEach((path, resource) -> {
                        String name = path.getPath().substring("items/".length(), path.getPath().length() - ".json".length());
                        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(path.getNamespace(), name)).orElse(null);
                        if (item == null) return;
                        try (Reader reader = resource.openAsReader()) {
                            Tint[] t = parse(JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("model"));
                            if (t != null) read.put(item, t);
                        } catch (Exception e) {
                            LogosMod.LOGGER.warn("Could not read the tints of {}: {}", path, e.toString());
                        }
                    });
            tints = read;
        });
    }

    private static Tint[] parse(JsonObject model) {
        if (model == null || !model.has("tints")) return null;
        var list = model.getAsJsonArray("tints");
        Tint[] out = new Tint[list.size()];
        for (int i = 0; i < out.length; i++) {
            JsonObject t = list.get(i).getAsJsonObject();
            String type = t.get("type").getAsString();
            out[i] = switch (type) {
                case "minecraft:constant" -> new Constant(t.get("value").getAsInt());
                case "minecraft:potion" -> new Potion(intOr(t.get("default"), -13083194));
                default -> new Constant(-1);
            };
        }
        return out;
    }

    private static int intOr(JsonElement e, int fallback) {
        return e == null ? fallback : e.getAsInt();
    }

    private static int color(ItemStack stack, int index) {
        Tint[] t = tints.get(stack.getItem());
        if (t == null || index < 0 || index >= t.length) return -1;
        return switch (t[index]) {
            case Constant c -> c.value();
            case Potion p -> {
                PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                yield contents.hasEffects() || contents.customColor().isPresent() ? FastColor.ARGB32.opaque(contents.getColor()) : p.fallback();
            }
        };
    }
}
