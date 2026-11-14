package dev.logos.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.item.RuneBookItem;
import dev.logos.item.RuneBookItem.Kind;
import dev.logos.lang.Element;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.RuneKnowledge;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** 符文之书: what each book teaches when read, what it leaves alone, and which chests hold it. */
final class RuneBookGameTests {
    private RuneBookGameTests() {}

    private static ServerPlayer reader(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static ItemStack book(Kind kind, int count) {
        return new ItemStack(LogosMod.RUNE_BOOKS.get(kind).get(), count);
    }

    private static InteractionResult read(GameTestHelper h, ServerPlayer p, Kind kind, int count) {
        p.setItemInHand(InteractionHand.MAIN_HAND, book(kind, count));
        return p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
    }

    private static List<String> learnedOf(ServerPlayer p, Kind kind) {
        return kind.pool().stream().filter(k -> RuneKnowledge.learned(p, k)).toList();
    }

    /** A 变之书 teaches one change rune and is used up; once there is none left to learn, the book is kept. */
    static void teachesOneOfItsKind(GameTestHelper h) {
        ServerPlayer p = reader(h);
        int before = learnedOf(p, Kind.CHANGE).size();
        read(h, p, Kind.CHANGE, 2);
        h.assertTrue(learnedOf(p, Kind.CHANGE).size() == before + 1, "a book of change taught " + (learnedOf(p, Kind.CHANGE).size() - before));
        h.assertTrue(p.getMainHandItem().getCount() == 1, "reading did not use the book up: " + p.getMainHandItem());
        h.assertTrue(RuneKnowledge.unlearned(p).size() == Unlocks.all().size() - Unlocks.STARTING.size() - 1, "it taught something besides a change rune");
        for (String key : Kind.LAW.pool()) RuneKnowledge.learn(p, key);
        InteractionResult kept = read(h, p, Kind.LAW, 1);
        h.assertTrue(kept == InteractionResult.FAIL && p.getMainHandItem().getCount() == 1, "a book with nothing left was used up: " + kept);
        h.succeed();
    }

    /** An element's own book teaches that element, and only once. */
    static void elementBook(GameTestHelper h) {
        ServerPlayer p = reader(h);
        read(h, p, Kind.FIRE, 1);
        h.assertTrue(RuneKnowledge.learned(p, Unlocks.element(Element.FIRE)), "the book of fire did not teach fire");
        h.assertTrue(RuneKnowledge.unlearned(p).size() == Unlocks.all().size() - Unlocks.STARTING.size() - 1, "the book of fire taught more than fire");
        h.assertTrue(read(h, p, Kind.FIRE, 1) == InteractionResult.FAIL && p.getMainHandItem().getCount() == 1, "a second book of fire was used up");
        h.succeed();
    }

    /** The books drawing from everything never draw life; 启悟之书 teaches all the rest at once. */
    static void neverLife(GameTestHelper h) {
        ServerPlayer p = reader(h);
        String life = Unlocks.element(Element.LIFE), freeze = Unlocks.rune("freeze");
        for (String key : Unlocks.all()) if (!key.equals(life) && !key.equals(freeze)) RuneKnowledge.learn(p, key);
        read(h, p, Kind.PRINCIPLE, 1);
        h.assertTrue(RuneKnowledge.learned(p, freeze) && !RuneKnowledge.learned(p, life), "the book of all principles taught life, or not freeze");
        h.assertTrue(read(h, p, Kind.PRINCIPLE, 1) == InteractionResult.FAIL, "with only life left, the book of all principles was read");
        h.assertTrue(read(h, p, Kind.ELEMENT, 1) == InteractionResult.FAIL, "with only life left, the book of elements was read");

        ServerPlayer fresh = reader(h);
        read(h, fresh, Kind.ENLIGHTENMENT, 1);
        h.assertTrue(RuneKnowledge.unlearned(fresh).equals(List.of(life)), "enlightenment left " + RuneKnowledge.unlearned(fresh));
        h.succeed();
    }

    private static Set<Item> roll(GameTestHelper h, String table, ContextKeySet params, int times) {
        LootTable loot = h.getLevel().getServer().reloadableRegistries().getLootTable(
                ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/" + table)));
        LootParams context = new LootParams.Builder(h.getLevel()).withParameter(LootContextParams.ORIGIN, h.absoluteVec(new Vec3(1, 1, 1))).create(params);
        Set<Item> found = new HashSet<>();
        for (int i = 0; i < times; i++) loot.getRandomItems(context, s -> found.add(s.getItem()));
        return found;
    }

    private static boolean has(Set<Item> found, Kind kind) {
        return found.contains(LogosMod.RUNE_BOOKS.get(kind).get());
    }

    /**
     * Each element has its places: fire the Nether, frost the snowy villages and igloos, lightning the ominous vaults,
     * the void the end cities (not in a ship here), the arcane the libraries and ancient cities. The family books lie in
     * ordinary chests, where no element's book, 形之书 or 万理之书 does.
     */
    static void chestLoot(GameTestHelper h) {
        Set<Item> bastion = roll(h, "bastion_treasure", LootContextParamSets.CHEST, 200);
        h.assertTrue(has(bastion, Kind.FIRE), "no book of fire in 200 bastion treasure chests");
        Set<Item> igloo = roll(h, "igloo_chest", LootContextParamSets.CHEST, 200);
        h.assertTrue(has(igloo, Kind.FROST) && !has(igloo, Kind.FIRE), "igloo chests: " + igloo.size() + " kinds, frost " + has(igloo, Kind.FROST));
        Set<Item> ominous = roll(h, "trial_chambers/reward_ominous", LootContextParamSets.VAULT, 200);
        h.assertTrue(has(ominous, Kind.LIGHTNING), "no book of lightning in 200 ominous vaults");
        Set<Item> city = roll(h, "end_city_treasure", LootContextParamSets.CHEST, 200);
        h.assertTrue(has(city, Kind.VOID), "no book of the void in 200 end city chests");
        Set<Item> ancient = roll(h, "ancient_city", LootContextParamSets.CHEST, 200);
        h.assertTrue(has(ancient, Kind.ARCANA) && has(ancient, Kind.CHANGE), "ancient city chests lack the book of arcana or of change");
        Set<Item> dungeon = roll(h, "simple_dungeon", LootContextParamSets.CHEST, 400);
        h.assertTrue(has(dungeon, Kind.CHANGE) && has(dungeon, Kind.TARGETING), "no family books in 400 dungeon chests");
        for (Kind k : List.of(Kind.FIRE, Kind.FROST, Kind.LIGHTNING, Kind.VOID, Kind.ARCANA, Kind.FORM, Kind.PRINCIPLE, Kind.ELEMENT, Kind.ENLIGHTENMENT)) {
            h.assertTrue(!has(dungeon, k), "a dungeon chest held " + k.id);
        }
        h.assertTrue(!has(bastion, Kind.ENLIGHTENMENT) && !has(ancient, Kind.ENLIGHTENMENT) && !has(city, Kind.ELEMENT),
                "enlightenment or the book of elements turned up in a chest");
        h.succeed();
    }
}
