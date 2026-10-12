package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
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
}
