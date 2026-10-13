package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** In-world tests for alchemy materials and how they plug into vanilla brewing and crafting. */
final class AlchemyGameTests {
    private AlchemyGameTests() {}

    /** Starter culture brews a water bottle, and only a water bottle, into raw mash. */
    static void brewsRawMash(GameTestHelper h) {
        var brewing = h.getLevel().potionBrewing();
        ItemStack culture = new ItemStack(LogosMod.STARTER_CULTURE.get());
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        ItemStack awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        h.assertTrue(brewing.isIngredient(culture), "starter culture is not a brewing ingredient");
        h.assertTrue(brewing.hasMix(water, culture), "water and starter culture do not mix");
        h.assertTrue(brewing.mix(culture, water).is(LogosMod.RAW_MASH.get()), "water and starter culture did not brew raw mash");
        h.assertFalse(brewing.hasMix(awkward, culture), "starter culture brewed something other than water");
        h.succeed();
    }

    /** Sulfur, coal or charcoal and saltpeter craft three gunpowder. */
    static void craftsGunpowder(GameTestHelper h) {
        for (var fuel : List.of(Items.COAL, Items.CHARCOAL)) {
            CraftingInput input = CraftingInput.of(3, 1, List.of(new ItemStack(LogosMod.SALTPETER.get()), new ItemStack(fuel),
                    new ItemStack(LogosMod.SULFUR_POWDER.get())));
            ItemStack out = h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                    .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
            h.assertTrue(out.is(Items.GUNPOWDER) && out.getCount() == 3, "sulfur, " + fuel + " and saltpeter made " + out);
        }
        h.succeed();
    }

    /** Glass, a brewing stand and a bottle over copper and a cauldron, on a crafting table, make the alchemy table. */
    static void craftsAlchemyTable(GameTestHelper h) {
        List<ItemStack> grid = List.of(
                new ItemStack(Items.GLASS), new ItemStack(Items.BREWING_STAND), new ItemStack(Items.GLASS_BOTTLE),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.CAULDRON), new ItemStack(Items.COPPER_INGOT),
                ItemStack.EMPTY, new ItemStack(Items.CRAFTING_TABLE), ItemStack.EMPTY);
        CraftingInput input = CraftingInput.of(3, 3, grid);
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(LogosMod.ALCHEMY_TABLE_ITEM.get()) && out.getCount() == 1, "the recipe made " + out);
        h.succeed();
    }

    // ---- the alchemy table ----------------------------------------------------------------------

    /** The main half, relative to the test; the table faces south, so its side half is to the east. */
    private static final BlockPos MAIN = new BlockPos(2, 2, 2), SIDE = MAIN.east();

    /** A table placed as a player would: the main half, then its side half beside it. */
    private static AlchemyTableBlockEntity placeTable(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 1, z, Blocks.STONE);
        BlockState state = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
        h.setBlock(MAIN, state);
        state.getBlock().setPlacedBy(h.getLevel(), h.absolutePos(MAIN), state, null, new ItemStack(LogosMod.ALCHEMY_TABLE_ITEM.get()));
        return (AlchemyTableBlockEntity) h.getBlockEntity(MAIN, AlchemyTableBlockEntity.class);
    }

    private static List<ItemEntity> dropped(GameTestHelper h, net.minecraft.world.item.Item item) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(MAIN)).inflate(3), e -> e.getItem().is(item));
    }

    /** Placing makes both halves; breaking the side half in survival takes both and drops one table and the contents. */
    static void tableHalvesBreakTogether(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        BlockState side = h.getBlockState(SIDE);
        h.assertTrue(side.is(LogosMod.ALCHEMY_TABLE.get()) && side.getValue(AlchemyTableBlock.PART) == AlchemyTableBlock.Part.SIDE,
                "no side half east of the main half: " + side);
        h.assertTrue(h.getLevel().getBlockEntity(h.absolutePos(SIDE)) == null, "the side half has its own block entity");
        table.items().set(0, ItemResource.of(Items.SUGAR), 5);
        h.getLevel().destroyBlock(h.absolutePos(SIDE), true);
        h.assertBlockNotPresent(LogosMod.ALCHEMY_TABLE.get(), MAIN);
        h.assertBlockNotPresent(LogosMod.ALCHEMY_TABLE.get(), SIDE);
        int tables = dropped(h, LogosMod.ALCHEMY_TABLE_ITEM.get()).stream().mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(tables == 1, "breaking the table dropped " + tables + " tables");
        h.assertTrue(dropped(h, Items.SUGAR).stream().mapToInt(e -> e.getItem().getCount()).sum() == 5, "the table's contents were not dropped");
        h.succeed();
    }

    /** Pipes put into the raw area (or the fuel slot), take only from the product area, through either half. */
    static void tableAutomationIsOneWay(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        var level = h.getLevel();
        var viaSide = level.getCapability(Capabilities.Item.BLOCK, h.absolutePos(SIDE), Direction.UP);
        h.assertTrue(viaSide != null, "the side half has no item handler");
        try (Transaction tx = Transaction.openRoot()) {
            h.assertTrue(viaSide.insert(ItemResource.of(Items.WHEAT), 10, tx) == 10, "wheat was not accepted");
            h.assertTrue(viaSide.insert(ItemResource.of(Items.AMETHYST_SHARD), 4, tx) == 4, "fuel was not accepted");
            h.assertTrue(viaSide.extract(ItemResource.of(Items.WHEAT), 10, tx) == 0, "raw materials could be taken out");
            tx.commit();
        }
        h.assertTrue(table.items().getResource(0).is(Items.WHEAT), "the wheat did not land in the raw area");
        h.assertTrue(table.fuel().getResource(0).is(Items.AMETHYST_SHARD) || table.mana() > 0, "the shards did not land in the fuel slot");
        table.items().set(AlchemyTableBlockEntity.RAW_SLOTS, ItemResource.of(Items.GUNPOWDER), 3);
        try (Transaction tx = Transaction.openRoot()) {
            h.assertTrue(viaSide.extract(ItemResource.of(Items.GUNPOWDER), 3, tx) == 3, "products could not be taken out");
            tx.commit();
        }
        // A real hopper above feeds the raw area; one below empties the product area.
        table.items().set(AlchemyTableBlockEntity.RAW_SLOTS, ItemResource.of(Items.GUNPOWDER), 2);
        h.setBlock(SIDE.above(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        ((HopperBlockEntity) h.getBlockEntity(SIDE.above(), HopperBlockEntity.class)).setItem(0, new ItemStack(Items.SUGAR, 2));
        h.setBlock(MAIN.below(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        h.succeedWhen(() -> {
            int sugar = 0;
            for (int i = 0; i < AlchemyTableBlockEntity.RAW_SLOTS; i++) if (table.items().getResource(i).is(Items.SUGAR)) sugar += table.items().getAmountAsInt(i);
            h.assertTrue(sugar == 2, "the hopper above has fed " + sugar + " of 2 sugar");
            h.assertTrue(((HopperBlockEntity) h.getBlockEntity(MAIN.below(), HopperBlockEntity.class)).countItem(Items.GUNPOWDER) == 2,
                    "the hopper below has not emptied the products");
        });
    }

    /** The tank holds ten buckets of one fluid. */
    static void tableTankHoldsOneFluid(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        var tank = h.getLevel().getCapability(Capabilities.Fluid.BLOCK, h.absolutePos(SIDE), null);
        h.assertTrue(tank != null, "the side half has no fluid handler");
        try (Transaction tx = Transaction.openRoot()) {
            int in = tank.insert(FluidResource.of(Fluids.WATER), 12_000, tx);
            h.assertTrue(in == AlchemyTableBlockEntity.TANK_CAPACITY, "the tank took " + in + " mB of water");
            h.assertTrue(tank.insert(FluidResource.of(Fluids.LAVA), 1000, tx) == 0, "lava mixed into water");
            tx.commit();
        }
        table.clearTank();
        h.assertTrue(table.fluid().isEmpty(), "the tank was not emptied");
        try (Transaction tx = Transaction.openRoot()) {
            h.assertTrue(tank.insert(FluidResource.of(Fluids.LAVA), 1000, tx) == 1000, "an emptied tank refused lava");
        }
        h.succeed();
    }

    /** Fuel burns only when all its mana fits; tainted shards are refused; a wand hands over its holder's mana. */
    static void tableManaSources(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.assertFalse(table.fuel().isValid(0, ItemResource.of(LogosMod.TAINTED_MANA_SHARD.get())), "the table takes tainted shards");
        table.setMana(990);
        table.fuel().set(0, ItemResource.of(Items.AMETHYST_SHARD), 3);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        PlayerMagic.of(p).mana = 500;
        ItemStack wand = new ItemStack(LogosMod.WAND.get());
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        h.runAfterDelay(5, () -> {
            h.assertTrue(table.fuel().getAmountAsInt(0) == 3, "fuel burnt though it did not fit");
            table.setMana(0);
            h.succeedWhen(() -> {
                h.assertTrue(table.fuel().getAmountAsInt(0) == 0 && table.mana() == 75, "three shards gave " + table.mana());
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(h.absolutePos(SIDE)), Direction.UP, h.absolutePos(SIDE), false);
                h.getBlockState(SIDE).useItemOn(wand, h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
                h.assertTrue(table.mana() == 75 + AlchemyTableBlockEntity.WAND_CHARGE, "the wand charged the table to " + table.mana());
                h.assertTrue(PlayerMagic.of(p).mana == 500 - AlchemyTableBlockEntity.WAND_CHARGE, "the player has " + PlayerMagic.of(p).mana + " left");
            });
        });
    }

    /** A water bottle pours 250 mB and leaves a glass bottle; a glass bottle draws one back. */
    static void tableBottles(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(h.absolutePos(MAIN)), Direction.UP, h.absolutePos(MAIN), false);
        p.setItemInHand(InteractionHand.MAIN_HAND, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(table.fluid().getAmount() == AlchemyTableBlock.BOTTLE, "a water bottle poured " + table.fluid().getAmount() + " mB");
        h.assertTrue(p.getMainHandItem().is(Items.GLASS_BOTTLE), "the poured bottle became " + p.getMainHandItem());
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(table.fluid().isEmpty(), "drawing a bottle left " + table.fluid().getAmount() + " mB");
        h.assertTrue(p.getMainHandItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER), "no water bottle was drawn");
        h.succeed();
    }

    /** Free mana lying on the table is taken into its pool. */
    static void tableGathersFreeMana(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        AlchemyTableBlockEntity table = placeTable(h);
        CompoundTag tag = new CompoundTag();
        tag.putFloat("amount", 50);
        FreeManaEntity orb = new FreeManaEntity(LogosMod.FREE_MANA.get(), h.getLevel());
        orb.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(MAIN.above()));
        orb.setPos(at.x, at.y, at.z);
        orb.setNoGravity(true);
        h.getLevel().addFreshEntity(orb);
        h.succeedWhen(() -> {
            h.assertTrue(Math.abs(table.mana() - 50 * 0.8) < 0.01, "the table gathered " + table.mana() + " of 40");
            h.assertFalse(orb.isAlive(), "the gathered orb is still there");
        });
    }

    // ---- process cards ---------------------------------------------------------------------------

    private static ItemStack waterBottle() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    /** How many of {@code item} lie in {@code handler}. */
    private static int count(ResourceHandler<ItemResource> handler, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < handler.size(); i++) if (handler.getResource(i).is(item)) n += handler.getAmountAsInt(i);
        return n;
    }

    private static void put(ResourceHandler<ItemResource> handler, ItemStack stack) {
        try (Transaction tx = Transaction.openRoot()) {
            handler.insert(ItemResource.of(stack), stack.getCount(), tx);
            tx.commit();
        }
    }

    /** Sugar, a mushroom and wheat laid in a fermentation card become three starter cultures in two seconds, for one mana. */
    static void processFerments(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.FERMENT);
        put(card.inputs(), new ItemStack(Items.SUGAR));
        put(card.inputs(), new ItemStack(Items.RED_MUSHROOM));
        put(card.inputs(), new ItemStack(Items.WHEAT));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.STARTER_CULTURE.get()) == 3, "the card made " + count(card.outputs(), LogosMod.STARTER_CULTURE.get()) + " starter cultures");
            h.assertTrue(count(card.inputs(), Items.SUGAR) + count(card.inputs(), Items.WHEAT) + count(card.inputs(), Items.RED_MUSHROOM) == 0, "the materials were not used up");
            h.assertTrue(table.mana() == 9, "the craft left " + table.mana() + " mana");
            h.assertTrue(card.made().equals(List.of(ItemResource.of(LogosMod.STARTER_CULTURE.get()))), "the card lists " + card.made() + " as made");
        });
    }

    /** A card takes designated items from storage (and nothing else), and sends its products to the product area. */
    static void processDesignatesAndRoutes(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.FERMENT);
        card.designate(0, Items.SUGAR);
        card.designate(1, Items.BROWN_MUSHROOM);
        card.designate(2, Items.WHEAT);
        card.setRoute(LogosMod.STARTER_CULTURE.get(), ProcessCard.PRODUCTS);
        put(table.rawArea(), new ItemStack(Items.SUGAR, 2));
        put(table.rawArea(), new ItemStack(Items.BROWN_MUSHROOM, 2));
        put(table.rawArea(), new ItemStack(Items.WHEAT, 2));
        // Not designated: the starter-and-wheat recipe must not touch these.
        put(table.rawArea(), new ItemStack(LogosMod.STARTER_CULTURE.get(), 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(table.productArea(), LogosMod.STARTER_CULTURE.get()) == 6, "the product area holds "
                    + count(table.productArea(), LogosMod.STARTER_CULTURE.get()) + " of 6 starter cultures");
            h.assertTrue(count(table.rawArea(), LogosMod.STARTER_CULTURE.get()) == 5, "undesignated starter culture was used");
            h.assertTrue(table.mana() == 8, "two crafts left " + table.mana() + " mana");
        });
    }

    /** Three water bottles pour 750 mB and their bottles hold the three raw mash: no bottle is gained or lost. */
    static void processDissolvesWaterBottles(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.STARTER_CULTURE.get()));
        for (int i = 0; i < 3; i++) put(card.inputs(), waterBottle());
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.RAW_MASH.get()) == 3, "the card made " + count(card.outputs(), LogosMod.RAW_MASH.get()) + " raw mash");
            h.assertTrue(count(card.inputs(), Items.POTION) == 0, "water bottles are left");
            h.assertTrue(count(table.items(), Items.GLASS_BOTTLE) + count(card.outputs(), Items.GLASS_BOTTLE) == 0, "bottles were left over");
            h.assertTrue(table.fluid().isEmpty(), "the tank holds " + table.fluid().getAmount() + " mB");
        });
    }

    /** With water in the tank, a card fills empty bottles from storage instead. */
    static void processDissolvesFromTank(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.STARTER_CULTURE.get()));
        card.designate(1, Items.GLASS_BOTTLE);
        put(table.rawArea(), new ItemStack(Items.GLASS_BOTTLE, 3));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.RAW_MASH.get()) == 3, "the card made " + count(card.outputs(), LogosMod.RAW_MASH.get()) + " raw mash");
            h.assertTrue(count(table.rawArea(), Items.GLASS_BOTTLE) == 0, "the bottles in storage were not used");
            h.assertTrue(table.fluid().getAmount() == 250, "the tank holds " + table.fluid().getAmount() + " of 250 mB");
        });
    }

    /**
     * A chain: distilling foul brine sends its niter mother liquor to a settling card and its sulfur
     * to the products; settling makes saltpeter and the empty bottle goes back to the raw area.
     */
    static void processChains(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard distill = table.addCard(Process.DISTILL);
        ProcessCard settle = table.addCard(Process.SETTLE);
        distill.setRoute(LogosMod.NITER_MOTHER_LIQUOR.get(), 1);
        distill.setRoute(LogosMod.SULFUR_POWDER.get(), ProcessCard.PRODUCTS);
        put(distill.inputs(), new ItemStack(LogosMod.FOUL_BRINE.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(table.productArea(), LogosMod.SULFUR_POWDER.get()) == 2, "the products hold " + count(table.productArea(), LogosMod.SULFUR_POWDER.get()) + " of 2 sulfur");
            h.assertTrue(count(settle.outputs(), LogosMod.SALTPETER.get()) == 2, "settling made " + count(settle.outputs(), LogosMod.SALTPETER.get()) + " of 2 saltpeter");
            h.assertTrue(count(table.rawArea(), Items.GLASS_BOTTLE) == 1, "the empty bottle did not come back to the raw area");
            h.assertTrue(table.mana() == 8, "two crafts left " + table.mana() + " mana");
        });
    }

    /** Without mana nothing is made; with one mana, one craft is. */
    static void processNeedsMana(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        ProcessCard card = table.addCard(Process.SETTLE);
        put(card.inputs(), new ItemStack(LogosMod.NITER_MOTHER_LIQUOR.get(), 2));
        h.runAfterDelay(ProcessCard.CRAFT_TICKS + 20, () -> {
            h.assertTrue(count(card.outputs(), LogosMod.SALTPETER.get()) == 0 && card.progress() == 0, "the card worked without mana");
            table.setMana(1);
            h.succeedWhen(() -> {
                h.assertTrue(count(card.outputs(), LogosMod.SALTPETER.get()) == 2, "one mana did not buy one craft");
                h.assertTrue(table.mana() == 0 && count(card.inputs(), LogosMod.NITER_MOTHER_LIQUOR.get()) == 1, "more than one craft was made");
            });
        });
    }

    /** While a card ferments the table says so, and tells clients, so the airlock bubbles; an idle card does not count, and it stops once the materials are gone. */
    static void processShowsWorking(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.FERMENT);
        // Nothing to distil: the still stays cold.
        table.addCard(Process.DISTILL);
        put(card.inputs(), new ItemStack(Items.SUGAR));
        put(card.inputs(), new ItemStack(Items.RED_MUSHROOM));
        put(card.inputs(), new ItemStack(Items.WHEAT));
        boolean[] sawWorking = {false};
        h.succeedWhen(() -> {
            if (table.isWorking(Process.FERMENT)) {
                sawWorking[0] = true;
                h.assertTrue(!table.isWorking(Process.DISTILL), "an idle distilling card counts as working");
                int sent = table.getUpdateTag(h.getLevel().registryAccess()).getIntOr("working", 0);
                h.assertTrue(sent == 1 << Process.FERMENT.ordinal(), "clients are told " + sent + " is working");
            }
            h.assertTrue(sawWorking[0], "the table never showed the card fermenting");
            h.assertTrue(count(card.outputs(), LogosMod.STARTER_CULTURE.get()) == 3, "the card has not finished its craft");
            h.assertTrue(!table.isWorking(Process.FERMENT), "the table still ferments with nothing left");
        });
    }

    /** At most four cards; cards, designations and routes are saved; a removed card's contents go back to storage and routes to it are undone. */
    static void processCardsKeepAndRemove(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        for (Process p : Process.values()) h.assertTrue(table.addCard(p) != null, "the table refused card " + p);
        h.assertTrue(table.addCard(Process.FERMENT) == null, "the table took a fifth card");
        table.cards().get(0).setRoute(Items.SUGAR, 2);
        table.cards().get(0).setDefaultRoute(3);
        table.cards().get(0).moveTo(10, 20);
        table.cards().get(1).designate(4, Items.CLAY_BALL);
        put(table.cards().get(2).inputs(), new ItemStack(Items.SUGAR, 7));
        // Made products are remembered: craft once on card 0 the long way, by hand.
        table.setMana(1);
        put(table.cards().get(0).inputs(), new ItemStack(Items.SUGAR));
        put(table.cards().get(0).inputs(), new ItemStack(Items.RED_MUSHROOM));
        put(table.cards().get(0).inputs(), new ItemStack(Items.WHEAT));
        h.assertTrue(table.cards().get(0).craft(table, true) != null, "card 0 could not craft");

        var registries = h.getLevel().registryAccess();
        AlchemyTableBlockEntity copy = new AlchemyTableBlockEntity(table.getBlockPos(), table.getBlockState());
        copy.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, table.saveCustomOnly(registries)));
        h.assertTrue(copy.cards().size() == 4 && copy.cards().get(3).process == Process.SETTLE, "the cards were not saved");
        h.assertTrue(copy.cards().get(0).route(Items.SUGAR) == 2 && copy.cards().get(0).defaultRoute() == 3, "the routes were not saved");
        h.assertTrue(copy.cards().get(1).designated(4).is(Items.CLAY_BALL), "the designation was not saved");
        h.assertTrue(copy.cards().get(0).made().equals(List.of(ItemResource.of(LogosMod.STARTER_CULTURE.get()))), "what the card made was not saved: " + copy.cards().get(0).made());
        h.assertTrue(copy.cards().get(0).x() == 10 && copy.cards().get(0).y() == 20, "the card's place on the canvas was not saved");
        h.assertTrue(count(copy.cards().get(2).inputs(), Items.SUGAR) == 7, "the card's contents were not saved");

        table.removeCard(2);
        h.assertTrue(table.cards().size() == 3, "the card was not removed");
        h.assertTrue(count(table.rawArea(), Items.SUGAR) == 7, "the removed card's sugar did not go to storage");
        h.assertTrue(table.cards().get(0).route(Items.SUGAR) == ProcessCard.KEEP, "a route to the removed card was kept");
        h.assertTrue(table.cards().get(0).defaultRoute() == 2, "a route past the removed card did not move down");
        h.succeed();
    }

    /**
     * Water bottles laid in the card are poured before the tank is touched, so their bottles hold
     * the product: starter culture and three water bottles make three raw mash even with water in the tank.
     */
    static void processPoursBottlesFirst(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 500);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.STARTER_CULTURE.get()));
        for (int i = 0; i < 3; i++) put(card.inputs(), waterBottle());
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.RAW_MASH.get()) == 3, "the card made " + count(card.outputs(), LogosMod.RAW_MASH.get()) + " raw mash");
            h.assertTrue(table.fluid().getAmount() == 500, "the tank was drawn on: " + table.fluid().getAmount() + " mB left");
            h.assertTrue(count(table.items(), Items.GLASS_BOTTLE) + count(card.outputs(), Items.GLASS_BOTTLE) == 0, "bottles were left over");
        });
    }

    /** A card that cannot work says why: no mana, then (with mana) the three bottles its raw mash needs. */
    static void processSaysWhatIsMissing(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.STARTER_CULTURE.get()));
        h.runAfterDelay(25, () -> {
            h.assertTrue(card.status().toString().contains("logos.alchemy_card.no_mana"), "without mana the card says " + card.status());
            table.setMana(10);
            h.succeedWhen(() -> {
                String said = card.status().toString();
                h.assertTrue(said.contains("logos.alchemy_card.missing") && said.contains("missing_item") && said.contains("3"),
                        "the card does not say it lacks three bottles: " + said);
                h.assertFalse(said.contains("missing_fluid"), "the card thinks it lacks water: " + said);
            });
        });
    }

    /** Raw mash, foul brine and niter mother liquor are drunk like potions: their effects, and the bottle back. */
    static void bottlesAreDrinkable(GameTestHelper h) {
        record Drink(net.minecraft.world.item.Item item, List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> effects) {}
        List<Drink> drinks = List.of(
                new Drink(LogosMod.RAW_MASH.get(), List.of(net.minecraft.world.effect.MobEffects.STRENGTH, net.minecraft.world.effect.MobEffects.NAUSEA)),
                new Drink(LogosMod.FOUL_BRINE.get(), List.of(net.minecraft.world.effect.MobEffects.POISON, net.minecraft.world.effect.MobEffects.NAUSEA,
                        net.minecraft.world.effect.MobEffects.WEAKNESS)),
                new Drink(LogosMod.NITER_MOTHER_LIQUOR.get(), List.of(net.minecraft.world.effect.MobEffects.POISON)));
        for (Drink d : drinks) {
            @SuppressWarnings("removal")
            ServerPlayer p = h.makeMockServerPlayerInLevel();
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            ItemStack bottle = new ItemStack(d.item());
            h.assertTrue(bottle.getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.DRINK, d.item() + " is not drunk");
            p.setItemInHand(InteractionHand.MAIN_HAND, bottle);
            ItemStack left = bottle.finishUsingItem(h.getLevel(), p);
            for (var effect : d.effects()) h.assertTrue(p.hasEffect(effect), d.item() + " did not give " + effect.getRegisteredName());
            h.assertTrue(left.is(Items.GLASS_BOTTLE), d.item() + " left " + left + " instead of a glass bottle");
        }
        h.succeed();
    }

    /** The switch pauses the cards (keeping a craft's progress), a redstone signal stops them too, and both resume. */
    static void tableSwitchAndRedstone(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.SETTLE);
        put(card.inputs(), new ItemStack(LogosMod.NITER_MOTHER_LIQUOR.get(), 2));
        h.runAfterDelay(20, () -> {
            int progress = card.progress();
            h.assertTrue(progress > 0, "the card had not started");
            table.setEnabled(false);
            h.runAfterDelay(ProcessCard.CRAFT_TICKS, () -> {
                h.assertTrue(card.progress() == progress && count(card.outputs(), LogosMod.SALTPETER.get()) == 0, "the card worked while switched off");
                h.assertTrue(card.status().toString().contains("switched_off"), "a switched-off card says " + card.status());
                table.setEnabled(true);
                h.setBlock(SIDE.east(), Blocks.REDSTONE_BLOCK);
                h.runAfterDelay(ProcessCard.CRAFT_TICKS, () -> {
                    h.assertTrue(count(card.outputs(), LogosMod.SALTPETER.get()) == 0, "the card worked under a redstone signal");
                    h.assertTrue(card.status().toString().contains("logos.alchemy_card.redstone"), "a powered card says " + card.status());
                    h.setBlock(SIDE.east(), Blocks.AIR);
                    h.succeedWhen(() -> h.assertTrue(count(card.outputs(), LogosMod.SALTPETER.get()) >= 2, "the card did not resume"));
                });
            });
        });
    }

    // ---- potion powder ---------------------------------------------------------------------------

    private static boolean holdsSwiftness(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.SWIFTNESS);
    }

    /** Settling a potion, drunk or splash, gives a powder of it and the bottle back; a tipped arrow is no potion to settle. */
    static void powderSettles(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.SETTLE);
        put(card.inputs(), PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
        put(card.inputs(), PotionContents.createItemStack(Items.SPLASH_POTION, Potions.SWIFTNESS));
        put(card.inputs(), PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.SWIFTNESS));
        h.succeedWhen(() -> {
            int powders = 0;
            for (int i = 0; i < card.outputs().size(); i++) {
                ItemStack s = card.outputs().getResource(i).toStack(card.outputs().getAmountAsInt(i));
                if (s.is(LogosMod.POTION_POWDER.get())) {
                    h.assertTrue(holdsSwiftness(s), "the powder does not hold swiftness: " + s);
                    powders += s.getCount();
                }
            }
            h.assertTrue(powders == 2, "settling made " + powders + " of 2 powders");
            h.assertTrue(count(table.rawArea(), Items.GLASS_BOTTLE) == 2, "the bottles did not come back");
            h.assertTrue(count(card.inputs(), Items.TIPPED_ARROW) == 1, "the tipped arrow was settled");
        });
    }

    /** A powder is eaten for its potion's effects. */
    static void powderIsEaten(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        ItemStack powder = dev.logos.item.PotionPowderItem.of(LogosMod.POTION_POWDER.get(), new PotionContents(Potions.SWIFTNESS), 2);
        h.assertTrue(powder.getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.EAT, "the powder is not eaten");
        p.setItemInHand(InteractionHand.MAIN_HAND, powder);
        ItemStack left = powder.finishUsingItem(h.getLevel(), p);
        h.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED), "eating swiftness powder gave no speed");
        h.assertTrue(left.is(LogosMod.POTION_POWDER.get()) && left.getCount() == 1, "eating left " + left);
        h.succeed();
    }

    /** Dissolving a powder with a bottle of water and an empty bottle gives its potion back. */
    static void powderDissolvesBack(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        table.tank().set(0, FluidResource.of(Fluids.WATER), AlchemyTableBlock.BOTTLE);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), dev.logos.item.PotionPowderItem.of(LogosMod.POTION_POWDER.get(), new PotionContents(Potions.SWIFTNESS), 1));
        put(card.inputs(), new ItemStack(Items.GLASS_BOTTLE));
        h.succeedWhen(() -> {
            ItemStack out = card.outputs().getResource(0).toStack(card.outputs().getAmountAsInt(0));
            h.assertTrue(out.is(Items.POTION) && holdsSwiftness(out), "dissolving gave " + out);
            h.assertTrue(table.fluid().isEmpty(), "the tank still holds " + table.fluid().getAmount() + " mB");
        });
    }

    // ---- brewing in dissolution -----------------------------------------------------------------

    private static int countPotion(ResourceHandler<ItemResource> handler, net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        int n = 0;
        for (int i = 0; i < handler.size(); i++) {
            ItemStack s = handler.getResource(i).toStack(handler.getAmountAsInt(i));
            if (s.is(Items.POTION) && s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(potion)) n += s.getCount();
        }
        return n;
    }

    /** Sugar and three awkward potions dissolve into three potions of swiftness, as in a brewing stand. */
    static void brewsFromBase(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(Items.SUGAR, 2));
        for (int i = 0; i < 3; i++) put(card.inputs(), PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
        h.succeedWhen(() -> {
            h.assertTrue(countPotion(card.outputs(), Potions.SWIFTNESS) == 3, "brewing made " + countPotion(card.outputs(), Potions.SWIFTNESS) + " of 3 swiftness");
            h.assertTrue(count(card.inputs(), Items.SUGAR) == 1, "more than one sugar was used");
            h.assertTrue(countPotion(card.inputs(), Potions.AWKWARD) == 0, "awkward potions are left");
        });
    }

    /** Nether wart, three empty bottles and 750 mB from the tank dissolve into three awkward potions. */
    static void brewsFromWater(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 750);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(Items.NETHER_WART));
        put(card.inputs(), new ItemStack(Items.GLASS_BOTTLE, 3));
        h.succeedWhen(() -> {
            h.assertTrue(countPotion(card.outputs(), Potions.AWKWARD) == 3, "brewing made " + countPotion(card.outputs(), Potions.AWKWARD) + " of 3 awkward");
            h.assertTrue(table.fluid().isEmpty(), "the tank still holds " + table.fluid().getAmount() + " mB");
            h.assertTrue(count(card.inputs(), Items.GLASS_BOTTLE) == 0, "bottles are left");
        });
    }

    /**
     * Designations are exact: a card set aside for potions of swiftness takes those from storage and
     * leaves a poison potion be. A card saved when designations were only an item id still loads.
     */
    static void processDesignatesExactly(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.SETTLE);
        ItemStack swiftness = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        card.designate(0, ItemResource.of(swiftness));
        put(table.rawArea(), swiftness.copy());
        put(table.rawArea(), PotionContents.createItemStack(Items.POTION, Potions.POISON));

        CompoundTag old = new CompoundTag();
        old.putString("process", "settle");
        old.putString("designated3", "minecraft:sugar");
        ProcessCard loaded = ProcessCard.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), old));
        h.assertTrue(loaded != null && loaded.designated(3).is(Items.SUGAR), "an old item-id designation did not load");

        h.succeedWhen(() -> {
            int powders = 0;
            for (int i = 0; i < card.outputs().size(); i++) if (card.outputs().getResource(i).is(LogosMod.POTION_POWDER.get())) powders += card.outputs().getAmountAsInt(i);
            h.assertTrue(powders == 1, "the card settled " + powders + " potions, not just the swiftness one");
            h.assertTrue(countPotion(table.rawArea(), Potions.POISON) == 1, "the undesignated poison potion was taken");
        });
    }

    /**
     * Products are told apart exactly: settling swiftness and poison potions lists two powders, and
     * wiring only the swiftness powder to the product area sends only that one there. A card saved
     * when products and wires were only item ids still loads them.
     */
    static void processProductsExact(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.SETTLE);
        put(card.inputs(), PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
        put(card.inputs(), PotionContents.createItemStack(Items.POTION, Potions.POISON));
        ItemResource swiftPowder = ItemResource.of(dev.logos.item.PotionPowderItem.of(LogosMod.POTION_POWDER.get(), new PotionContents(Potions.SWIFTNESS), 1));
        card.setRoute(swiftPowder, ProcessCard.PRODUCTS);

        CompoundTag old = new CompoundTag();
        old.putString("process", "ferment");
        CompoundTag routes = new CompoundTag();
        routes.putInt("minecraft:sugar", 1);
        old.put("routes", routes);
        net.minecraft.nbt.ListTag made = new net.minecraft.nbt.ListTag();
        made.add(net.minecraft.nbt.StringTag.valueOf("logos:starter_culture"));
        old.put("made", made);
        ProcessCard loaded = ProcessCard.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), old));
        h.assertTrue(loaded != null && loaded.route(Items.SUGAR) == 1, "an old item-id wire did not load");
        h.assertTrue(loaded.made().equals(List.of(ItemResource.of(LogosMod.STARTER_CULTURE.get()))), "an old item-id product list did not load: " + loaded.made());

        h.succeedWhen(() -> {
            h.assertTrue(card.made().size() == 2, "the card lists " + card.made().size() + " kinds of powder, not 2");
            h.assertTrue(count(table.productArea(), LogosMod.POTION_POWDER.get()) == 1, "the product area holds "
                    + count(table.productArea(), LogosMod.POTION_POWDER.get()) + " powders, not just the swiftness one");
            h.assertTrue(table.productArea().getResource(0).equals(swiftPowder), "the powder in the product area is " + table.productArea().getResource(0));
            h.assertTrue(count(card.outputs(), LogosMod.POTION_POWDER.get()) == 1, "the unwired poison powder did not stay in the card");
        });
    }

    /** A card set up for sugar, mushrooms and wheat with none left asks for those, not for another recipe's starter culture. */
    static void statusFollowsDesignations(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.FERMENT);
        card.designate(0, Items.SUGAR);
        card.designate(1, Items.RED_MUSHROOM);
        card.designate(2, Items.WHEAT);
        h.succeedWhen(() -> {
            String said = card.status().toString();
            h.assertTrue(said.contains("logos.alchemy_card.missing") && said.contains("item.minecraft.sugar"), "the card does not ask for sugar: " + said);
            h.assertFalse(said.contains("starter_culture"), "the card asks for another recipe's starter culture: " + said);
        });
    }

    /** A card with every material for a brew but too few free output slots says its outputs are full, not that it lacks bottles. */
    static void statusSaysOutputsFull(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(Items.NETHER_WART));
        for (int i = 0; i < 3; i++) put(card.inputs(), waterBottle());
        // Seven of nine outputs taken: three brewed bottles cannot all go in.
        for (int i = 0; i < 7; i++) card.outputs().set(i, ItemResource.of(Items.DIRT), 64);
        h.succeedWhen(() -> {
            String said = card.status().toString();
            h.assertTrue(said.contains("logos.alchemy_card.output_full"), "the card does not say its outputs are full: " + said);
            h.assertFalse(said.contains("logos.alchemy_card.missing"), "the card says it is missing something: " + said);
        });
    }
}
