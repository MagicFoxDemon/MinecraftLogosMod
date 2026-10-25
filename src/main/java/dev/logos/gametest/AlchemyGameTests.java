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

    /** Two caput mortuum, adhesive and a copper ingot, as filler, binder and patch, make a maintenance kit. */
    static void craftsMaintenanceKit(GameTestHelper h) {
        CraftingInput input = CraftingInput.of(2, 2, List.of(new ItemStack(LogosMod.CAPUT_MORTUUM.get()), new ItemStack(Items.COPPER_INGOT),
                new ItemStack(LogosMod.ADHESIVE.get()), new ItemStack(LogosMod.CAPUT_MORTUUM.get())));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(LogosMod.MAINTENANCE_KIT.get()), "caput mortuum, adhesive and copper made " + out);
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
            h.assertTrue(table.mana() == 6, "a distillation (3) and a settling (1) left " + table.mana() + " mana");
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

    /** With a blast furnace beside the table, a calcination card burns whatever a furnace smelts, one mana a craft and no fuel. */
    static void processCalcines(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.assertTrue(table.addCard(Process.CALCINE) == null, "a calcination card was laid with no blast furnace beside the table");
        h.setBlock(SIDE.east(), Blocks.BLAST_FURNACE);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.CALCINE);
        h.assertTrue(card != null, "no calcination card with a blast furnace beside the table");
        put(card.inputs(), new ItemStack(Items.RAW_IRON, 2));
        put(card.inputs(), new ItemStack(Items.COBBLESTONE));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), Items.IRON_INGOT) == 2 && count(card.outputs(), Items.STONE) == 1,
                    "the card made " + count(card.outputs(), Items.IRON_INGOT) + " iron and " + count(card.outputs(), Items.STONE) + " stone");
            h.assertTrue(table.mana() == 7, "three crafts left " + table.mana() + " mana");
        });
    }

    /** Taking the blast furnace away holds calcination, and says why; putting it back lets it go on. */
    static void calcineNeedsBlastFurnace(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.setBlock(MAIN.west(), Blocks.BLAST_FURNACE);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.CALCINE);
        put(card.inputs(), new ItemStack(Items.RAW_GOLD));
        h.setBlock(MAIN.west(), Blocks.AIR);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(card.status().equals(net.minecraft.network.chat.Component.translatable("logos.alchemy_card.no_furnace")), "the card says " + card.status().getString()))
                .thenExecute(() -> {
                    h.assertTrue(count(card.outputs(), Items.GOLD_INGOT) == 0 && table.mana() == 10, "the card worked with no blast furnace");
                    h.setBlock(MAIN.west(), Blocks.BLAST_FURNACE);
                })
                .thenWaitUntil(() -> h.assertTrue(count(card.outputs(), Items.GOLD_INGOT) == 1, "the card did not go on once the furnace was back"))
                .thenSucceed();
    }

    private static int bottlesBack(AlchemyTableBlockEntity table, ProcessCard card) {
        return count(table.items(), Items.GLASS_BOTTLE) + count(card.outputs(), Items.GLASS_BOTTLE);
    }

    /** Three redstone and a glass bottle distil into a bottle of mercury and a sulfur powder. */
    static void redstoneDistilsMercury(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISTILL);
        put(card.inputs(), new ItemStack(Items.REDSTONE, 3));
        put(card.inputs(), new ItemStack(Items.GLASS_BOTTLE));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.MERCURY_BOTTLE.get()) == 1 && count(card.outputs(), LogosMod.SULFUR_POWDER.get()) == 1,
                    "the card made " + count(card.outputs(), LogosMod.MERCURY_BOTTLE.get()) + " mercury and " + count(card.outputs(), LogosMod.SULFUR_POWDER.get()) + " sulfur");
            h.assertTrue(count(card.inputs(), Items.REDSTONE) + count(card.inputs(), Items.GLASS_BOTTLE) == 0, "the materials were not used up");
        });
    }

    /** Mercury, saltpeter and sulfur dissolve into philosophers' mercury in the mercury's own bottle, with no water. */
    static void triaPrimaDissolveIntoPhilosophersMercury(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.MERCURY_BOTTLE.get()));
        put(card.inputs(), new ItemStack(LogosMod.SALTPETER.get()));
        put(card.inputs(), new ItemStack(LogosMod.SULFUR_POWDER.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.PHILOSOPHERS_MERCURY.get()) == 1, "no philosophers' mercury was made");
            h.assertTrue(bottlesBack(table, card) == 0, "a bottle was given back though the mercury's bottle holds the product");
        });
    }

    /**
     * Philosophers' mercury settles into rebis for the 20 mana its recipe asks: with 10 the card waits
     * and says how much it needs; with 30 it settles, leaving 10, and the bottle goes back to the raw materials.
     */
    static void philosophersMercurySettlesIntoRebis(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.SETTLE);
        put(card.inputs(), new ItemStack(LogosMod.PHILOSOPHERS_MERCURY.get()));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(card.status().equals(net.minecraft.network.chat.Component.translatable("logos.alchemy_card.needs_mana", 20)),
                        "the card says " + card.status().getString()))
                .thenExecute(() -> {
                    h.assertTrue(count(card.outputs(), LogosMod.REBIS.get()) == 0 && table.mana() == 10, "the card settled without the mana");
                    table.setMana(30);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(count(card.outputs(), LogosMod.REBIS.get()) == 1, "no rebis was settled");
                    h.assertTrue(table.mana() == 10, "settling left " + table.mana() + " mana, not 10");
                    h.assertTrue(count(table.items(), Items.GLASS_BOTTLE) == 1, "the bottle did not go back to the raw materials");
                    h.assertTrue(!card.made().contains(ItemResource.of(Items.GLASS_BOTTLE)), "the given-back bottle is listed as a product");
                })
                .thenSucceed();
    }

    private static ItemStack powder(net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        return dev.logos.item.PotionPowderItem.of(LogosMod.POTION_POWDER.get(), new PotionContents(potion), 1);
    }

    /** Philosophers' mercury and three different powders dissolve into one potion of all three effects, rather than one powder back into its potion. */
    static void conjunctionJoinsPowders(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(20);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.PHILOSOPHERS_MERCURY.get()));
        put(card.inputs(), powder(Potions.SWIFTNESS));
        put(card.inputs(), powder(Potions.STRENGTH));
        put(card.inputs(), powder(Potions.NIGHT_VISION));
        // Bottles and water for the powder-back recipe: it must not win over the conjunction.
        put(card.inputs(), new ItemStack(Items.GLASS_BOTTLE, 3));
        h.succeedWhen(() -> {
            ItemStack potion = ItemStack.EMPTY;
            for (int i = 0; i < ProcessCard.SLOTS; i++) if (card.outputs().getResource(i).is(Items.POTION)) potion = card.outputs().getResource(i).toStack();
            h.assertTrue(!potion.isEmpty(), "no potion was made");
            PotionContents contents = potion.get(DataComponents.POTION_CONTENTS);
            var effects = new java.util.ArrayList<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>>();
            contents.getAllEffects().forEach(e -> effects.add(e.getEffect()));
            h.assertTrue(effects.size() == 3 && effects.contains(net.minecraft.world.effect.MobEffects.SPEED) && effects.contains(net.minecraft.world.effect.MobEffects.STRENGTH)
                    && effects.contains(net.minecraft.world.effect.MobEffects.NIGHT_VISION), "the potion holds " + effects);
            h.assertTrue(contents.customName().equals(java.util.Optional.of(dev.logos.alchemy.SpecialRecipes.CONJUNCTION)), "the potion is not named a conjunction");
            h.assertTrue(count(card.inputs(), LogosMod.PHILOSOPHERS_MERCURY.get()) + count(card.inputs(), LogosMod.POTION_POWDER.get()) == 0, "the materials were not used up");
            h.assertTrue(count(card.inputs(), Items.GLASS_BOTTLE) == 3 && table.fluid().getAmount() == 1000, "the powder-back recipe ran too");
            h.assertTrue(table.mana() == 5, "joining three powders left " + table.mana() + " mana, not 5");
        });
    }

    /** Purification needs a cauldron beside the table; with philosophers' mercury it makes four tainted shards clean mana shards. */
    static void purifyCleansTaintedShards(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.assertTrue(table.addCard(Process.PURIFY) == null, "a purification card was laid with no cauldron beside the table");
        h.setBlock(MAIN.north(), Blocks.CAULDRON);
        table.setMana(50);
        ProcessCard card = table.addCard(Process.PURIFY);
        h.assertTrue(card != null, "no purification card with a cauldron beside the table");
        put(card.inputs(), new ItemStack(LogosMod.TAINTED_MANA_SHARD.get(), 4));
        put(card.inputs(), new ItemStack(LogosMod.PHILOSOPHERS_MERCURY.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.MANA_SHARD.get()) == 4, "the card made " + count(card.outputs(), LogosMod.MANA_SHARD.get()) + " mana shards");
            h.assertTrue(count(card.inputs(), LogosMod.TAINTED_MANA_SHARD.get()) == 0 && bottlesBack(table, card) == 1, "the shards or the bottle are off");
            h.assertTrue(AlchemyTableBlockEntity.fuelValue(new ItemStack(LogosMod.MANA_SHARD.get())) == 500, "a mana shard is not 500 mana of fuel");
            h.assertTrue(table.mana() == 10, "purifying left " + table.mana() + " mana, not 10");
        });
    }

    /** Purification washes as a water cauldron does, a bottle's worth of water each: dye, a banner's top pattern, a shulker box's colour; and concrete powder sets. */
    static void purifyWashesLikeACauldron(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.setBlock(SIDE.south(), Blocks.WATER_CAULDRON);
        table.setMana(10);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
        ProcessCard card = table.addCard(Process.PURIFY);
        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        helmet.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(0xFF0000));
        ItemStack banner = new ItemStack(Items.WHITE_BANNER);
        var stripe = h.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN)
                .getOrThrow(net.minecraft.world.level.block.entity.BannerPatterns.STRIPE_TOP);
        banner.set(DataComponents.BANNER_PATTERNS, new net.minecraft.world.level.block.entity.BannerPatternLayers.Builder()
                .add(stripe, net.minecraft.world.item.DyeColor.RED).add(stripe, net.minecraft.world.item.DyeColor.BLUE).build());
        put(card.inputs(), helmet);
        put(card.inputs(), banner);
        put(card.inputs(), new ItemStack(Items.RED_SHULKER_BOX));
        put(card.inputs(), new ItemStack(Items.WHITE_CONCRETE_POWDER));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), Items.WHITE_CONCRETE) == 1 && count(card.outputs(), Items.SHULKER_BOX) == 1, "the powder or the box was not washed");
            ItemStack washedHelmet = ItemStack.EMPTY, washedBanner = ItemStack.EMPTY;
            for (int i = 0; i < ProcessCard.SLOTS; i++) {
                ItemStack s = card.outputs().getResource(i).toStack();
                if (s.is(Items.LEATHER_HELMET)) washedHelmet = s;
                if (s.is(Items.WHITE_BANNER)) washedBanner = s;
            }
            h.assertTrue(!washedHelmet.isEmpty() && !washedHelmet.has(DataComponents.DYED_COLOR), "the helmet kept its dye");
            h.assertTrue(!washedBanner.isEmpty() && washedBanner.get(DataComponents.BANNER_PATTERNS).layers().size() == 1, "the banner did not lose just its top pattern");
            h.assertTrue(table.fluid().getAmount() == 0, "four washes left " + table.fluid().getAmount() + " mB");
        });
    }

    /** With a blast furnace beside the table, blackstone calcines into caput mortuum for two mana. */
    static void blackstoneCalcinesToCaputMortuum(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.setBlock(SIDE.east(), Blocks.BLAST_FURNACE);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.CALCINE);
        put(card.inputs(), new ItemStack(Items.BLACKSTONE));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.CAPUT_MORTUUM.get()) == 1, "no caput mortuum was calcined");
            h.assertTrue(table.mana() == 8, "calcining left " + table.mana() + " mana, not 8");
        });
    }

    // ---- citrinitas: the firmament grade -------------------------------------------------------

    /**
     * Sublimation needs a lit beacon beside the table: an unlit one (nothing under it) will not do.
     * Once a pyramid lights it, solar amalgam sublimes into four solar blossoms, and its mercury rises
     * back into the bottle.
     */
    static void sublimationNeedsLitBeacon(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        BlockPos beacon = MAIN.north();
        h.setBlock(beacon, Blocks.BEACON);
        table.setMana(30);
        h.assertTrue(table.addCard(Process.SUBLIME) == null, "a sublimation card was laid beside an unlit beacon");
        ProcessCard[] card = new ProcessCard[1];
        h.startSequence()
                .thenExecute(() -> {
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) h.setBlock(beacon.offset(x, -1, z), Blocks.IRON_BLOCK);
                })
                .thenWaitUntil(() -> h.assertTrue(table.hasStation(Process.SUBLIME), "the beacon is not lit yet"))
                .thenExecute(() -> {
                    card[0] = table.addCard(Process.SUBLIME);
                    h.assertTrue(card[0] != null, "no sublimation card beside a lit beacon");
                    put(card[0].inputs(), new ItemStack(LogosMod.SOLAR_AMALGAM.get()));
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(count(card[0].outputs(), LogosMod.SOLAR_BLOSSOM.get()) == 4, "sublimed " + count(card[0].outputs(), LogosMod.SOLAR_BLOSSOM.get()) + " solar blossoms");
                    h.assertTrue(count(card[0].outputs(), LogosMod.MERCURY_BOTTLE.get()) + count(table.items(), LogosMod.MERCURY_BOTTLE.get()) == 1, "the mercury did not come back");
                    h.assertTrue(table.mana() == 10, "sublimating left " + table.mana() + " mana, not 10");
                })
                .thenSucceed();
    }

    /** Gold dissolves in philosophers' mercury into solar amalgam, which keeps the bottle. */
    static void goldDissolvesIntoAmalgam(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(Items.GOLD_INGOT));
        put(card.inputs(), new ItemStack(LogosMod.PHILOSOPHERS_MERCURY.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.SOLAR_AMALGAM.get()) == 1, "no solar amalgam was dissolved");
            h.assertTrue(bottlesBack(table, card) == 0 && table.mana() == 0, "a bottle came back, or the mana is " + table.mana());
        });
    }

    /** Four chalk and a rebis calcine together into the incandescent white stone. */
    static void chalkAndRebisCalcineWhiteStone(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.setBlock(SIDE.east(), Blocks.BLAST_FURNACE);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.CALCINE);
        put(card.inputs(), new ItemStack(LogosMod.CHALK.get(), 4));
        put(card.inputs(), new ItemStack(LogosMod.REBIS.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.INCANDESCENT_WHITE_STONE.get()) == 1, "no incandescent white stone was calcined");
            h.assertTrue(count(card.inputs(), LogosMod.CHALK.get()) == 0 && table.mana() == 0, "the chalk or the mana are off");
        });
    }

    /**
     * Solar blossoms, glowstone, quartz, the white stone and a nether star dissolve in a bucket of water
     * into four bottles of firmament liquor; four bottles of dragon's breath do instead of the star and
     * the bottles.
     */
    static void firmamentLiquidDissolves(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(120);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(LogosMod.SOLAR_BLOSSOM.get(), 4));
        put(card.inputs(), new ItemStack(Items.GLOWSTONE_DUST, 4));
        put(card.inputs(), new ItemStack(Items.QUARTZ, 4));
        put(card.inputs(), new ItemStack(Items.NETHER_STAR));
        put(card.inputs(), new ItemStack(LogosMod.INCANDESCENT_WHITE_STONE.get()));
        put(card.inputs(), new ItemStack(Items.GLASS_BOTTLE, 4));
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(count(card.outputs(), LogosMod.FIRMAMENT_LIQUID.get()) == 4, "dissolved " + count(card.outputs(), LogosMod.FIRMAMENT_LIQUID.get()) + " firmament liquor");
                    h.assertTrue(table.fluid().getAmount() == 0 && table.mana() == 60, "the water or the mana are off: " + table.fluid().getAmount() + " mB, " + table.mana());
                })
                .thenExecute(() -> {
                    table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
                    put(card.inputs(), new ItemStack(LogosMod.SOLAR_BLOSSOM.get(), 4));
                    put(card.inputs(), new ItemStack(Items.GLOWSTONE_DUST, 4));
                    put(card.inputs(), new ItemStack(Items.QUARTZ, 4));
                    put(card.inputs(), new ItemStack(Items.DRAGON_BREATH, 4));
                    put(card.inputs(), new ItemStack(LogosMod.INCANDESCENT_WHITE_STONE.get()));
                })
                .thenWaitUntil(() -> h.assertTrue(count(card.outputs(), LogosMod.FIRMAMENT_LIQUID.get()) + count(table.items(), LogosMod.FIRMAMENT_LIQUID.get()) == 8,
                        "dragon's breath did not dissolve into four more"))
                .thenSucceed();
    }

    /** Firmament liquor settles into the firmament stone and gives its bottle back. */
    static void firmamentLiquidSettles(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(20);
        ProcessCard card = table.addCard(Process.SETTLE);
        put(card.inputs(), new ItemStack(LogosMod.FIRMAMENT_LIQUID.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.FIRMAMENT_STONE.get()) == 1, "no firmament stone settled");
            h.assertTrue(bottlesBack(table, card) == 1 && table.mana() == 0, "the bottle or the mana are off");
        });
    }

    /** Caput mortuum, soul soil and starter culture rot into three soul humus for five mana. */
    static void caputMortuumRotsIntoSoulHumus(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.FERMENT);
        put(card.inputs(), new ItemStack(LogosMod.CAPUT_MORTUUM.get(), 3));
        put(card.inputs(), new ItemStack(Items.SOUL_SOIL, 2));
        put(card.inputs(), new ItemStack(LogosMod.STARTER_CULTURE.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.SOUL_HUMUS_ITEM.get()) == 3, "the card made " + count(card.outputs(), LogosMod.SOUL_HUMUS_ITEM.get()) + " soul humus");
            h.assertTrue(table.mana() == 5, "rotting left " + table.mana() + " mana, not 5");
        });
    }

    /** With a cauldron beside the table, four soul humus, rebis, philosophers' mercury and a ghast tear wash white into four chalk, with a bucket's worth of water. */
    static void soulHumusWashesIntoChalk(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        h.setBlock(MAIN.north(), Blocks.CAULDRON);
        table.setMana(40);
        table.tank().set(0, FluidResource.of(Fluids.WATER), 1000);
        ProcessCard card = table.addCard(Process.PURIFY);
        put(card.inputs(), new ItemStack(LogosMod.SOUL_HUMUS_ITEM.get(), 4));
        put(card.inputs(), new ItemStack(LogosMod.REBIS.get()));
        put(card.inputs(), new ItemStack(LogosMod.PHILOSOPHERS_MERCURY.get()));
        put(card.inputs(), new ItemStack(Items.GHAST_TEAR));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.CHALK.get()) == 4, "the card made " + count(card.outputs(), LogosMod.CHALK.get()) + " chalk");
            h.assertTrue(table.fluid().isEmpty() && bottlesBack(table, card) == 1, "the water or the bottle are off");
            h.assertTrue(table.mana() == 10, "washing left " + table.mana() + " mana, not 10");
        });
    }

    /** An echo shard dissolves in five raw mash into five echo tinctures for four mana; the mash's bottles carry the tincture. */
    static void echoShardDissolvesIntoTincture(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(Items.ECHO_SHARD));
        put(card.inputs(), new ItemStack(LogosMod.RAW_MASH.get(), 5));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.ECHO_TINCTURE.get()) == 5, "the card made " + count(card.outputs(), LogosMod.ECHO_TINCTURE.get()) + " echo tincture");
            h.assertTrue(table.mana() == 6, "dissolving left " + table.mana() + " mana, not 6");
        });
    }

    /** Three sculk veins dissolve in one raw mash into one echo tincture for two mana. */
    static void sculkVeinsDissolveIntoTincture(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.DISSOLVE);
        put(card.inputs(), new ItemStack(Items.SCULK_VEIN, 3));
        put(card.inputs(), new ItemStack(LogosMod.RAW_MASH.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.ECHO_TINCTURE.get()) == 1, "the card made " + count(card.outputs(), LogosMod.ECHO_TINCTURE.get()) + " echo tincture");
            h.assertTrue(table.mana() == 8, "dissolving left " + table.mana() + " mana, not 8");
        });
    }

    /** Two caput mortuum rot with philosophers' mercury into raven's head, the blackening, for fifteen mana. */
    static void caputMortuumRotsIntoRavenHead(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(20);
        ProcessCard card = table.addCard(Process.FERMENT);
        put(card.inputs(), new ItemStack(LogosMod.CAPUT_MORTUUM.get(), 2));
        put(card.inputs(), new ItemStack(LogosMod.PHILOSOPHERS_MERCURY.get()));
        h.succeedWhen(() -> {
            h.assertTrue(count(card.outputs(), LogosMod.RAVEN_HEAD.get()) == 1, "the card made " + count(card.outputs(), LogosMod.RAVEN_HEAD.get()) + " raven's head");
            h.assertTrue(table.mana() == 5, "rotting left " + table.mana() + " mana, not 5");
        });
    }

    /** Soul humus is a block that breaks back into itself, and Soul Speed quickens on it as on soul soil. */
    static void soulHumusDropsItself(GameTestHelper h) {
        h.setBlock(MAIN, LogosMod.SOUL_HUMUS.get());
        h.assertTrue(h.getBlockState(MAIN).is(net.minecraft.tags.BlockTags.SOUL_SPEED_BLOCKS), "Soul Speed does not count soul humus");
        h.getLevel().destroyBlock(h.absolutePos(MAIN), true);
        h.succeedWhen(() -> h.assertTrue(dropped(h, LogosMod.SOUL_HUMUS_ITEM.get()).size() == 1, "soul humus did not drop itself"));
    }

    /** Wheat seeds planted in a cultivation jar ripen stage by stage; the harvest is wheat, and one seed stays planted. */
    static void cultivationJarGrowsCrops(GameTestHelper h) {
        h.setBlock(MAIN, LogosMod.CULTIVATION_JAR.get());
        dev.logos.block.CultivationJarBlockEntity jar = (dev.logos.block.CultivationJarBlockEntity) h.getBlockEntity(MAIN, dev.logos.block.CultivationJarBlockEntity.class);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(h.absolutePos(MAIN)), Direction.UP, h.absolutePos(MAIN), false);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(jar.isEmpty(), "dirt was planted");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS, 2));
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(jar.seed().is(Items.WHEAT_SEEDS) && p.getMainHandItem().getCount() == 1, "planting took " + p.getMainHandItem());
        for (int i = 0; i < 7; i++) {
            h.assertFalse(jar.isRipe(), "ripe after " + i + " stages");
            h.assertTrue(jar.grow(), "did not grow at stage " + i);
        }
        h.assertTrue(jar.isRipe(), "not ripe after seven stages");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.getBlockState(MAIN).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(!jar.isRipe() && jar.seed().is(Items.WHEAT_SEEDS), "the harvest did not replant");
        h.succeedWhen(() -> h.assertTrue(dropped(h, Items.WHEAT).size() == 1, "the harvest gave no wheat"));
    }

    /**
     * Solar amalgam poured on carrots (its bottle given back) makes the harvest golden carrots and is spent
     * by it; a second bottle waits in the reserve and is poured when the first runs out, its bottle going to the outputs.
     */
    static void cultivationJarMedium(GameTestHelper h) {
        h.setBlock(MAIN, LogosMod.CULTIVATION_JAR.get());
        dev.logos.block.CultivationJarBlockEntity jar = (dev.logos.block.CultivationJarBlockEntity) h.getBlockEntity(MAIN, dev.logos.block.CultivationJarBlockEntity.class);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(h.absolutePos(MAIN)), Direction.UP, h.absolutePos(MAIN), false);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CARROT));
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SOLAR_AMALGAM.get(), 2));
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        h.getBlockState(MAIN).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        h.assertTrue(jar.medium().is(LogosMod.SOLAR_AMALGAM.get()) && jar.mediumUses() == 2, "the medium is " + jar.medium() + " x" + jar.mediumUses());
        h.assertTrue(jar.reserve().getAmountAsInt(0) == 1, "the second bottle did not go to the reserve");
        h.assertTrue(p.getInventory().countItem(Items.GLASS_BOTTLE) == 1, "the poured bottle was not given back");
        h.assertTrue(h.getBlockState(MAIN).getValue(dev.logos.block.CultivationJarBlock.MEDIUM), "the medium layer is not shown");
        for (int harvest = 0; harvest < 2; harvest++) {
            while (jar.grow()) {}
            h.assertTrue(jar.isRipe(), "the carrots did not ripen");
            jar.randomTick(h.getLevel(), h.getLevel().getRandom());
        }
        h.assertTrue(jar.medium().is(LogosMod.SOLAR_AMALGAM.get()) && jar.mediumUses() == 2 && jar.reserve().getAmountAsInt(0) == 0,
                "the reserve was not poured when the first bottle ran out");
        int golden = 0, bottles = 0;
        for (int i = 0; i < dev.logos.block.CultivationJarBlockEntity.OUTPUTS; i++) {
            ItemStack s = jar.outputs().getResource(i).toStack(jar.outputs().getAmountAsInt(i));
            if (s.is(Items.GOLDEN_CARROT)) golden += s.getCount();
            if (s.is(Items.GLASS_BOTTLE)) bottles += s.getCount();
        }
        h.assertTrue(golden == 6 && bottles == 1, "the outputs hold " + golden + " golden carrots and " + bottles + " bottles");
        h.assertTrue(jar.seed().is(Items.CARROT), "the carrot was not kept planted");
        h.succeed();
    }

    /** 灌魔 fills an alchemy table through its side half, paying 1.1 a point for only what fits; a full table costs nothing. */
    static void infuseBlockFillsTable(GameTestHelper h) {
        AlchemyTableBlockEntity table = placeTable(h);
        table.setMana(AlchemyTableBlockEntity.MAX_MANA - 30);
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        // South of the side half, facing north: two blocks ahead of the body is the side half.
        Vec3 at = h.absoluteVec(new Vec3(SIDE.getX() + 0.5, 2, SIDE.getZ() + 2.5));
        p.snapTo(at.x, at.y, at.z, 180, 0);
        String src = "(spell :mana 100 (infuse-block (ahead 2 :from (pos-of caster)) 50))";
        var filling = dev.logos.runtime.Casting.cast(p, dev.logos.runtime.Casting.compile(src), null, null);
        h.assertTrue(filling != null, "the cast was refused");
        h.assertTrue(table.mana() == AlchemyTableBlockEntity.MAX_MANA, "the table holds " + table.mana());
        var full = dev.logos.runtime.Casting.cast(p, dev.logos.runtime.Casting.compile(src), null, null);
        h.assertTrue(Math.abs(full.mana - filling.mana - 33) < 1e-6, "filling 30 should cost 33 more than infusing a full table, not "
                + (full.mana - filling.mana));
        h.succeed();
    }

    /** A bottle of mercury is poison: drinking it poisons and sickens, and leaves the glass bottle. */
    static void mercuryIsPoison(GameTestHelper h) {
        var cow = h.spawn(net.minecraft.world.entity.EntityType.COW, new Vec3(1.5, 2, 1.5), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        ItemStack left = new ItemStack(LogosMod.MERCURY_BOTTLE.get()).finishUsingItem(h.getLevel(), cow);
        h.assertTrue(cow.hasEffect(net.minecraft.world.effect.MobEffects.POISON) && cow.hasEffect(net.minecraft.world.effect.MobEffects.NAUSEA), "mercury did no harm");
        h.assertTrue(left.is(Items.GLASS_BOTTLE), "drinking mercury left " + left);
        h.succeed();
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
        for (Process p : Process.values()) if (p.onTable()) h.assertTrue(table.addCard(p) != null, "the table refused card " + p);
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
