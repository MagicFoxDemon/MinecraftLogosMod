package dev.logos.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.alchemy.BuffPotions;
import dev.logos.item.RandomFamilyBook;
import dev.logos.item.RuneBookItem;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.state.BlockState;

/** 术士: the copy table makes one, and what a warlock offers at each level. */
final class WarlockGameTests {
    private WarlockGameTests() {}

    /** Every state of the copy table is the warlock's job site, one a villager without a job may take. */
    static void jobSite(GameTestHelper h) {
        VillagerProfession warlock = LogosMod.WARLOCK.get();
        for (BlockState state : LogosMod.COPY_TABLE.get().getStateDefinition().getPossibleStates()) {
            Holder<PoiType> poi = PoiTypes.forState(state).orElse(null);
            h.assertTrue(poi != null && poi.is(LogosMod.WARLOCK_POI.getKey()), "no warlock job site at " + state);
            h.assertTrue(poi.is(PoiTypeTags.ACQUIRABLE_JOB_SITE), "a jobless villager would not take the copy table");
            h.assertTrue(warlock.acquirableJobSite().test(poi) && warlock.heldJobSite().test(poi), "the warlock does not hold the copy table");
        }
        h.succeed();
    }

    /** What a new warlock of {@code level} offers: the items it sells, and the items it buys. */
    private static List<MerchantOffer> offers(GameTestHelper h, int level) {
        Villager v = new Villager(EntityType.VILLAGER, h.getLevel());
        var profession = h.getLevel().registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).getOrThrow(LogosMod.WARLOCK.getKey());
        v.setVillagerData(v.getVillagerData().withProfession(profession).withLevel(level));
        return new ArrayList<>(v.getOffers());
    }

    private static Set<Item> sold(List<MerchantOffer> offers) {
        Set<Item> out = new HashSet<>();
        for (MerchantOffer o : offers) if (o.getCostA().is(Items.EMERALD)) out.add(o.getResult().getItem());
        return out;
    }

    private static Set<Item> bought(List<MerchantOffer> offers) {
        Set<Item> out = new HashSet<>();
        for (MerchantOffer o : offers) if (o.getResult().is(Items.EMERALD)) out.add(o.getCostA().getItem());
        return out;
    }

    /**
     * Each level sells its own wand and page for sure and buys at least one herb or material; potions are Logos's
     * own. From journeyman on a family book may be offered, never 形之书 nor an element's book.
     */
    static void tradesByLevel(GameTestHelper h) {
        List<Item> wands = List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath(LogosMod.MODID, "oak_wand")), LogosMod.WAND.get(), LogosMod.ECHO_WAND.get(), LogosMod.ADEPT_WAND.get(), LogosMod.CHALK_WAND.get());
        Set<Item> families = new HashSet<>();
        for (RuneBookItem.Kind k : RandomFamilyBook.FAMILIES) families.add(LogosMod.RUNE_BOOKS.get(k).get());
        Set<Item> booksSeen = new HashSet<>();
        for (int level = 1; level <= 5; level++) {
            for (int n = 0; n < 30; n++) {
                List<MerchantOffer> offers = offers(h, level);
                Set<Item> sold = sold(offers);
                h.assertTrue(sold.contains(wands.get(level - 1)), "a level " + level + " warlock does not sell " + wands.get(level - 1) + ": " + sold);
                h.assertTrue(!bought(offers).isEmpty(), "a level " + level + " warlock buys nothing");
                for (MerchantOffer o : offers) {
                    ItemStack result = o.getResult();
                    if (result.getItem() instanceof RuneBookItem) {
                        h.assertTrue(level >= 3 && families.contains(result.getItem()), "a level " + level + " warlock offered " + result);
                        booksSeen.add(result.getItem());
                    }
                    if (result.is(Items.POTION)) h.assertTrue(BuffPotions.isLogosPotion(result), "a warlock sold a potion that is not Logos's: " + result);
                }
            }
        }
        h.assertTrue(booksSeen.size() >= 2, "family books offered by 90 warlocks from journeyman up: " + booksSeen);
        h.succeed();
    }
}
