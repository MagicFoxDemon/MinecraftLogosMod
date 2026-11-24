package dev.logos.gametest;

import java.util.List;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.CultivationJarBlockEntity;
import dev.logos.block.ManaSpreaderBlockEntity;
import dev.logos.block.ManaStorageTankBlockEntity;
import dev.logos.item.ManaCasketItem;
import dev.logos.runtime.ManaField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 魔匣: a casket gives only what is lacking and is opened for the rest (the alchemy table and the storage
 * tank keep the open one, a player carries it); spent, it leaves its empty casket; and every casket is
 * made and filled without anything that gives mana itself. The spreader takes them too, and a jar takes mana by hand.
 */
final class ManaCasketGameTests {
    private ManaCasketGameTests() {}

    private static ItemStack in(net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler h, int slot) {
        return h.getResource(slot).toStack(h.getAmountAsInt(slot));
    }

    /**
     * Three iron caskets (750) at a table holding 900: one is opened and fills it, keeping 650; with the
     * pool emptied it gives those 650, its empty casket comes out among the products, and the next is opened.
     */
    static void tableTakesWhatIsLacking(GameTestHelper h) {
        AlchemyTableBlockEntity table = AlchemyGameTests.placeTable(h);
        BlockPos pos = h.absolutePos(AlchemyGameTests.MAIN);
        table.setMana(900);
        table.fuel().set(0, ItemResource.of(LogosMod.IRON_MANA_CASKET.get()), 3);
        AlchemyTableBlockEntity.serverTick(h.getLevel(), pos, table.getBlockState(), table);
        ItemStack open = in(table.openedCasket(), 0);
        h.assertTrue(table.mana() == 1000, "the table should be filled to 1000, it holds " + table.mana());
        h.assertTrue(ManaCasketItem.isOpened(open) && ManaCasketItem.manaIn(open) == 650, "the opened casket should keep 650, got " + open);
        h.assertTrue(table.fuel().getAmountAsInt(0) == 2, "one casket should leave the fuel slot");

        AlchemyTableBlockEntity.serverTick(h.getLevel(), pos, table.getBlockState(), table);
        h.assertTrue(table.fuel().getAmountAsInt(0) == 2, "a full table must not open another casket");

        table.setMana(0);
        AlchemyTableBlockEntity.serverTick(h.getLevel(), pos, table.getBlockState(), table);
        h.assertTrue(table.mana() == 650, "the opened casket should give its 650 first, the table holds " + table.mana());
        h.assertTrue(in(table.items(), AlchemyTableBlockEntity.RAW_SLOTS).is(LogosMod.EMPTY_IRON_CASKET.get()),
                "the empty casket should come out among the products, got " + in(table.items(), AlchemyTableBlockEntity.RAW_SLOTS));
        AlchemyTableBlockEntity.serverTick(h.getLevel(), pos, table.getBlockState(), table);
        h.assertTrue(table.mana() == 1000 && ManaCasketItem.manaIn(in(table.openedCasket(), 0)) == 400 && table.fuel().getAmountAsInt(0) == 1,
                "the next casket should top the table up and keep 400");
        h.succeed();
    }

    /**
     * A copper casket (200) at a tank holding 4900 gives 100 and keeps 100; emptied, it gives the rest and
     * its empty casket goes to the empties slot. With that slot full the spent casket waits there, and the
     * next stays shut until the slot is emptied. Broken, the tank drops the opened casket.
     */
    static void tankKeepsEmpties(GameTestHelper h) {
        BlockPos at = new BlockPos(1, 2, 1);
        h.setBlock(at, LogosMod.MANA_STORAGE_TANK.get());
        ManaStorageTankBlockEntity tank = (ManaStorageTankBlockEntity) h.getBlockEntity(at, ManaStorageTankBlockEntity.class);
        tank.setMana(4900);
        tank.input().set(0, ItemResource.of(LogosMod.COPPER_MANA_CASKET.get()), 3);
        tank.work(h.getLevel(), h.absolutePos(at));
        h.assertTrue(tank.mana() == 5000 && ManaCasketItem.manaIn(in(tank.openedCasket(), 0)) == 100, "the tank should take 100 and leave 100 in the casket");

        tank.setMana(0);
        tank.work(h.getLevel(), h.absolutePos(at));
        h.assertTrue(tank.mana() == 100, "the casket should give its last 100 before another opens, the tank holds " + tank.mana());
        h.assertTrue(in(tank.empties(), 0).is(LogosMod.EMPTY_COPPER_CASKET.get()) && tank.empties().getAmountAsInt(0) == 1,
                "the empty casket should go to the empties slot");

        tank.empties().set(0, ItemResource.of(LogosMod.EMPTY_COPPER_CASKET.get()), 64);
        tank.work(h.getLevel(), h.absolutePos(at));
        tank.work(h.getLevel(), h.absolutePos(at));
        h.assertTrue(tank.mana() == 300 && in(tank.openedCasket(), 0).is(LogosMod.EMPTY_COPPER_CASKET.get()) && tank.input().getAmountAsInt(0) == 1,
                "with the empties full the spent casket should wait and the last stay shut; tank " + tank.mana() + ", held " + in(tank.openedCasket(), 0));
        tank.empties().set(0, ItemResource.EMPTY, 0);
        tank.work(h.getLevel(), h.absolutePos(at));
        h.assertTrue(tank.mana() == 500 && tank.input().getAmountAsInt(0) == 0, "once the empties slot is free the last casket should open");
        h.succeed();
    }

    /** A copper casket in a spreader's drawer is opened and spent into it; its empty casket goes to the empties slot. */
    static void spreaderTakesCaskets(GameTestHelper h) {
        BlockPos at = new BlockPos(1, 2, 1);
        h.setBlock(at, LogosMod.MANA_SPREADER.get().defaultBlockState());
        ManaSpreaderBlockEntity spreader = (ManaSpreaderBlockEntity) h.getBlockEntity(at, ManaSpreaderBlockEntity.class);
        try (Transaction tx = Transaction.openRoot()) {
            h.assertTrue(spreader.automation().insert(ItemResource.of(LogosMod.COPPER_MANA_CASKET.get()), 1, tx) == 1, "the drawer refused a casket");
            tx.commit();
        }
        h.succeedWhen(() -> {
            h.assertTrue(spreader.fuel().getAmountAsInt(0) == 0 && in(spreader.openedCasket(), 0).isEmpty(), "the casket should be taken and spent");
            h.assertTrue(in(spreader.empties(), 0).is(LogosMod.EMPTY_COPPER_CASKET.get()), "its empty casket should be in the empties slot");
            h.assertTrue(spreader.mana() + ManaField.spread(h.getLevel(), h.absolutePos(at)) > 0, "the spreader got no mana");
        });
    }

    /**
     * Mana by hand into a cultivation jar (500): a crystal (1000) does not fit and is refused, a shard fills
     * it and a second is refused; a gold casket gives an empty jar 500 and goes on opened with 2000.
     */
    static void jarTakesManaByHand(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos at = new BlockPos(1, 2, 1), other = new BlockPos(3, 2, 1);
        h.setBlock(at, LogosMod.CULTIVATION_JAR.get());
        h.setBlock(other, LogosMod.CULTIVATION_JAR.get());
        CultivationJarBlockEntity jar = (CultivationJarBlockEntity) h.getBlockEntity(at, CultivationJarBlockEntity.class);
        ItemStack crystal = new ItemStack(LogosMod.MANA_CRYSTAL.get());
        h.assertTrue(!jar.fillMana(crystal, player) && crystal.getCount() == 1 && jar.mana() == 0, "a crystal does not fit and must be refused");
        ItemStack shards = new ItemStack(LogosMod.MANA_SHARD.get(), 2);
        h.assertTrue(jar.fillMana(shards, player) && shards.getCount() == 1 && jar.mana() == 500, "a shard should fill the jar");
        h.assertTrue(!jar.fillMana(shards, player) && shards.getCount() == 1, "a full jar must refuse the next shard");

        CultivationJarBlockEntity empty = (CultivationJarBlockEntity) h.getBlockEntity(other, CultivationJarBlockEntity.class);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.GOLD_MANA_CASKET.get(), 2));
        ItemStack held = player.getMainHandItem();
        h.assertTrue(empty.fillMana(held, player) && empty.mana() == 500 && held.getCount() == 1, "a gold casket should fill the jar, held " + held);
        h.assertTrue(player.getInventory().getNonEquipmentItems().stream().anyMatch(s -> ManaCasketItem.isOpened(s) && ManaCasketItem.manaIn(s) == 2000),
                "the opened casket should go on with 2000");
        h.assertTrue(!empty.fillMana(held, player), "a full jar must refuse a casket");
        h.succeed();
    }

    /**
     * A player feeding from a stack of three copper caskets: a lifeform that lacks 50 takes 50, and the
     * casket opened for it goes into the inventory; the next feeding drains that opened one first, and its
     * empty casket is left; a creative player uses nothing up.
     */
    static void playerFeedsFromOpenedFirst(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.COPPER_MANA_CASKET.get(), 3));
        ItemStack held = player.getMainHandItem();
        double given = ManaCasketItem.feed(player, held, a -> Math.min(a, 50));
        h.assertTrue(given == 50 && held.getCount() == 2, "feeding 50 should open one casket of the three, held " + held);
        ItemStack opened = player.getInventory().getNonEquipmentItems().stream().filter(ManaCasketItem::isOpened).findFirst().orElse(ItemStack.EMPTY);
        h.assertTrue(ManaCasketItem.manaIn(opened) == 150 && opened.getMaxStackSize() == 1, "the opened casket should keep 150 and not stack, got " + opened);

        given = ManaCasketItem.feed(player, held, a -> Math.min(a, 1000));
        h.assertTrue(given == 150 && held.getCount() == 2, "the opened casket should give first, gave " + given + ", held " + held);
        h.assertTrue(player.getInventory().getNonEquipmentItems().stream().anyMatch(s -> s.is(LogosMod.EMPTY_COPPER_CASKET.get())),
                "the spent casket should leave its empty casket");
        h.assertTrue(player.getInventory().getNonEquipmentItems().stream().noneMatch(ManaCasketItem::isOpened), "no opened casket should be left");

        Player creative = h.makeMockPlayer(GameType.CREATIVE);
        creative.getAbilities().instabuild = true;
        creative.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.GOLD_MANA_CASKET.get()));
        ItemStack gold = creative.getMainHandItem();
        ManaCasketItem.feed(creative, gold, a -> Math.min(a, 10));
        h.assertTrue(gold.getCount() == 1 && !ManaCasketItem.isOpened(gold), "a creative player should use nothing up");
        h.succeed();
    }

    /**
     * The casket line: living metal is fermented, empty caskets settled, caskets distilled; no ingredient of
     * any of it gives mana itself (a mana fuel or a casket), and filling costs less than the casket holds.
     * The four caskets sit between the solid mana's grades, never on one.
     */
    static void madeWithoutManaItems(GameTestHelper h) {
        var recipes = h.getLevel().recipeAccess().recipeMap().byType(LogosMod.ALCHEMY_RECIPE.get());
        Set<Item> line = Set.of(LogosMod.LIVING_COPPER_INGOT.get(), LogosMod.LIVING_IRON_INGOT.get(), LogosMod.LIVING_GOLD_INGOT.get(),
                LogosMod.LIVING_NETHERITE_INGOT.get(), LogosMod.EMPTY_COPPER_CASKET.get(), LogosMod.EMPTY_IRON_CASKET.get(),
                LogosMod.EMPTY_GOLD_CASKET.get(), LogosMod.EMPTY_NETHERITE_CASKET.get());
        int found = 0;
        for (var holder : recipes) {
            var recipe = holder.value();
            Item made = recipe.results().getFirst().create().getItem();
            boolean casket = made instanceof ManaCasketItem;
            if (!casket && !line.contains(made)) continue;
            found++;
            Process expected = casket ? Process.DISTILL : made.toString().contains("living_") ? Process.FERMENT : Process.SETTLE;
            h.assertTrue(recipe.process() == expected, made + " should be made by " + expected + ", not " + recipe.process());
            for (var need : recipe.ingredients()) {
                for (Holder<Item> item : need.ingredient().items().toList()) {
                    h.assertTrue(item.getData(LogosMod.MANA_FUEL) == null && !(item.value() instanceof ManaCasketItem),
                            made + " is made with " + item.value() + ", which gives mana itself");
                }
            }
            if (casket) h.assertTrue(recipe.mana() < ((ManaCasketItem) made).fullMana(), made + " costs more to fill than it holds");
        }
        h.assertTrue(found == 12, "expected 12 recipes for the casket line, found " + found);
        List<Double> solid = List.of(25.0, 50.0, 400.0, 500.0, 1000.0, 5000.0);
        for (var casket : LogosMod.MANA_CASKETS) {
            h.assertTrue(!solid.contains(casket.get().fullMana()), casket.get() + " holds as much as a solid mana item");
        }
        h.succeed();
    }
}
