package dev.logos.port;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/**
 * Stands in for 26.1's data-driven villager trades, as far as the mod uses them: a profession's trade set for each
 * level ({@code data/<ns>/trade_set/…}) lists trades by a tag ({@code tags/villager_trade/…}), each trade a file of
 * its own ({@code villager_trade/…}). The same files as on {@code main} are read; a villager of such a profession takes
 * every trade of its level's set whose {@code merchant_predicate} holds, up to the set's {@code amount}. Trade
 * functions: {@code minecraft:set_random_potion} and those registered with {@link #function}. Predicates:
 * {@code minecraft:random_chance}.
 */
public final class DataTrades {
    private static final Logger LOG = LogUtils.getLogger();
    /** By profession: the trade set of each level. */
    private static final Map<ResourceLocation, Int2ObjectMap<ResourceLocation>> PROFESSIONS = new HashMap<>();
    private static final Map<String, BiFunction<ItemStack, RandomSource, ItemStack>> FUNCTIONS = new HashMap<>();
    private static volatile Map<ResourceLocation, JsonObject> trades = Map.of();
    private static volatile Map<ResourceLocation, JsonObject> sets = Map.of();
    private static volatile Map<ResourceLocation, List<ResourceLocation>> tags = Map.of();

    private DataTrades() {}

    /** Gives {@code profession} the trade set {@code levels} names for each level. */
    public static void setsFor(ResourceLocation profession, Int2ObjectMap<ResourceLocation> levels) {
        PROFESSIONS.put(profession, levels);
    }

    /** Lets trades name {@code function} in {@code given_item_modifiers}: it turns the stack given into another, by chance. */
    public static void function(String function, BiFunction<ItemStack, RandomSource, ItemStack> apply) {
        FUNCTIONS.put(function, apply);
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> {
            e.addListener(new Reader("villager_trade", read -> trades = read));
            e.addListener(new Reader("trade_set", read -> sets = read));
            e.addListener(new Reader("tags/villager_trade", read -> {
                Map<ResourceLocation, List<ResourceLocation>> byTag = new HashMap<>();
                read.forEach((id, json) -> {
                    List<ResourceLocation> values = new ArrayList<>();
                    for (JsonElement v : GsonHelper.getAsJsonArray(json, "values")) values.add(ResourceLocation.parse(v.getAsString()));
                    byTag.put(id, values);
                });
                tags = byTag;
            }));
        });
    }

    /**
     * Adds the offers of {@code profession} at {@code level} to {@code offers}; false when the profession has no data
     * trades, and its villager trades as vanilla ones do.
     */
    public static boolean addOffers(VillagerProfession profession, int level, MerchantOffers offers, RandomSource random, HolderLookup.Provider registries) {
        Int2ObjectMap<ResourceLocation> levels = PROFESSIONS.get(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession));
        if (levels == null) return false;
        ResourceLocation setId = levels.get(level);
        JsonObject set = setId == null ? null : sets.get(setId);
        if (set == null) return true;
        String trades = GsonHelper.getAsString(set, "trades");
        List<ResourceLocation> ids = trades.startsWith("#")
                ? tags.getOrDefault(ResourceLocation.parse(trades.substring(1)), List.of())
                : List.of(ResourceLocation.parse(trades));
        int amount = GsonHelper.getAsInt(set, "amount", ids.size());
        List<ResourceLocation> order = new ArrayList<>(ids);
        net.minecraft.Util.shuffle(order, random);
        int added = 0;
        for (ResourceLocation id : order) {
            if (added >= amount) break;
            JsonObject trade = DataTrades.trades.get(id);
            if (trade == null) {
                LOG.warn("Unknown villager trade {} in {}", id, setId);
                continue;
            }
            MerchantOffer offer = offer(trade, random, registries);
            if (offer == null) continue;
            offers.add(offer);
            added++;
        }
        return true;
    }

    private static MerchantOffer offer(JsonObject trade, RandomSource random, HolderLookup.Provider registries) {
        if (trade.has("merchant_predicate") && !holds(trade.getAsJsonObject("merchant_predicate"), random)) return null;
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        JsonObject wants = trade.getAsJsonObject("wants");
        ItemCost cost = new ItemCost(BuiltInRegistries.ITEM.get(ResourceLocation.parse(GsonHelper.getAsString(wants, "id"))),
                GsonHelper.getAsInt(wants, "count", 1));
        ItemStack gives = ItemStack.CODEC.parse(ops, trade.get("gives")).resultOrPartial(LOG::warn).orElse(ItemStack.EMPTY);
        if (gives.isEmpty()) return null;
        if (trade.has("given_item_modifiers")) {
            for (JsonElement m : trade.getAsJsonArray("given_item_modifiers")) gives = modify(gives, m.getAsJsonObject(), random, registries);
        }
        return new MerchantOffer(cost, Optional.empty(), gives, GsonHelper.getAsInt(trade, "max_uses", 12),
                GsonHelper.getAsInt(trade, "xp", 1), GsonHelper.getAsFloat(trade, "reputation_discount", 0.05f));
    }

    private static boolean holds(JsonObject predicate, RandomSource random) {
        String condition = GsonHelper.getAsString(predicate, "condition");
        if (condition.equals("minecraft:random_chance")) return random.nextFloat() < GsonHelper.getAsFloat(predicate, "chance");
        LOG.warn("Unsupported trade predicate {}", condition);
        return true;
    }

    private static ItemStack modify(ItemStack stack, JsonObject modifier, RandomSource random, HolderLookup.Provider registries) {
        String function = GsonHelper.getAsString(modifier, "function");
        if (function.equals("minecraft:set_random_potion")) {
            var options = modifier.getAsJsonArray("options");
            var pick = ResourceLocation.parse(options.get(random.nextInt(options.size())).getAsString());
            registries.lookupOrThrow(net.minecraft.core.registries.Registries.POTION)
                    .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.POTION, pick))
                    .ifPresent(potion -> stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion)));
            return stack;
        }
        BiFunction<ItemStack, RandomSource, ItemStack> apply = FUNCTIONS.get(function);
        if (apply != null) return apply.apply(stack, random);
        LOG.warn("Unsupported trade function {}", function);
        return stack;
    }

    /** Reads every JSON file under a data directory. */
    private static final class Reader extends SimpleJsonResourceReloadListener {
        private final java.util.function.Consumer<Map<ResourceLocation, JsonObject>> into;

        Reader(String directory, java.util.function.Consumer<Map<ResourceLocation, JsonObject>> into) {
            super(new com.google.gson.Gson(), directory);
            this.into = into;
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> read, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, JsonObject> objects = new HashMap<>();
            read.forEach((id, json) -> {
                if (json.isJsonObject()) objects.put(id, json.getAsJsonObject());
            });
            into.accept(objects);
        }
    }
}
