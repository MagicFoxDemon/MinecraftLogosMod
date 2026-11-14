package dev.logos.gametest;

import java.util.HashSet;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.item.ManaElixirItem;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** 魔力药剂: drinking raises the permanent cap, three bottles an item; brewed and ancient ones count apart; where each comes from. */
final class ManaElixirGameTests {
    private ManaElixirGameTests() {}

    private static ServerPlayer player(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 200;
        m.trace = false;
        return p;
    }

    /** Finishes drinking one bottle of {@code item}, as the end of using it does; returns what is left in hand. */
    private static ItemStack drink(ServerPlayer p, Item item) {
        ItemStack stack = new ItemStack(item);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack.finishUsingItem(p.level(), p);
    }

    /**
     * Three crystalline elixirs add 3 × 20 for good and give their bottles back; a fourth is refused and kept.
     * The ancient crystalline elixir has three of its own, on top.
     */
    static void drink(GameTestHelper h) {
        ServerPlayer p = player(h);
        PlayerMagic m = PlayerMagic.of(p);
        ManaElixirItem brewed = LogosMod.MANA_ELIXIRS.get(ManaElixirItem.Grade.CRYSTAL).get();
        ManaElixirItem ancient = LogosMod.ANCIENT_MANA_ELIXIRS.get(ManaElixirItem.Grade.CRYSTAL).get();
        for (int i = 1; i <= ManaElixirItem.LIMIT; i++) {
            ItemStack left = drink(p, brewed);
            h.assertTrue(left.is(Items.GLASS_BOTTLE), "bottle " + i + " should give its bottle back, left " + left);
            h.assertTrue(m.baseMaxMana == 200 + 20 * i, "after bottle " + i + " the permanent cap is " + m.baseMaxMana);
        }
        h.assertTrue(brewed.drunk(p) == ManaElixirItem.LIMIT, "three drunk, counted " + brewed.drunk(p));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(brewed));
        h.assertTrue(brewed.use(p.level(), p, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "a fourth bottle should be refused");
        ItemStack kept = drink(p, brewed);
        h.assertTrue(kept.is(brewed) && m.baseMaxMana == 260, "a fourth bottle must be kept and add nothing: " + kept + ", " + m.baseMaxMana);
        drink(p, ancient);
        h.assertTrue(m.baseMaxMana == 280 && ancient.drunk(p) == 1, "the ancient one counts apart: " + m.baseMaxMana);
        h.assertTrue(PlayerMagic.CODEC.codec().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, m).flatMap(t ->
                PlayerMagic.CODEC.codec().parse(net.minecraft.nbt.NbtOps.INSTANCE, t)).getOrThrow().elixirs.equals(m.elixirs),
                "the counts must survive a save: " + m.elixirs);
        h.succeed();
    }

    /** Each brewed elixir ferments at the alchemy table; nothing brews an ancient one. */
    static void brewing(GameTestHelper h) {
        var recipes = h.getLevel().recipeAccess().recipeMap().byType(LogosMod.ALCHEMY_RECIPE.get());
        for (ManaElixirItem.Grade g : ManaElixirItem.Grade.values()) {
            Item brewed = LogosMod.MANA_ELIXIRS.get(g).get();
            Item ancient = LogosMod.ANCIENT_MANA_ELIXIRS.get(g).get();
            h.assertTrue(recipes.stream().anyMatch(r -> r.value().process() == Process.FERMENT && r.value().results().getFirst().create().is(brewed)),
                    "nothing ferments " + g + " elixirs");
            h.assertTrue(recipes.stream().noneMatch(r -> r.value().results().stream().anyMatch(t -> t.create().is(ancient))),
                    "the ancient " + g + " elixir must not be brewed");
        }
        h.succeed();
    }

    /**
     * Ancient city chests hold ancient echoing (1 in 4), firmament and sage elixirs, never a brewed one; rolled
     * 400 times, the echoing one is all but certain to show up (a miss is about 1 in 10^50).
     */
    static void chestLoot(GameTestHelper h) {
        LootTable table = h.getLevel().getServer().reloadableRegistries().getLootTable(
                ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/ancient_city")));
        LootParams params = new LootParams.Builder(h.getLevel()).withParameter(LootContextParams.ORIGIN, h.absoluteVec(new net.minecraft.world.phys.Vec3(1, 1, 1)))
                .create(LootContextParamSets.CHEST);
        Set<Item> found = new HashSet<>();
        for (int i = 0; i < 400; i++) table.getRandomItems(params, s -> found.add(s.getItem()));
        h.assertTrue(found.contains(LogosMod.ANCIENT_MANA_ELIXIRS.get(ManaElixirItem.Grade.ECHO).get()),
                "no ancient echoing elixir in 400 ancient city chests");
        for (var e : LogosMod.MANA_ELIXIRS.values()) h.assertTrue(!found.contains(e.get()), "a brewed elixir turned up in a chest: " + e.getId());
        h.assertTrue(!found.contains(LogosMod.ANCIENT_MANA_ELIXIRS.get(ManaElixirItem.Grade.BASIC).get()),
                "the basic ancient elixir belongs to dungeons and mineshafts, not ancient cities");
        h.succeed();
    }
}
