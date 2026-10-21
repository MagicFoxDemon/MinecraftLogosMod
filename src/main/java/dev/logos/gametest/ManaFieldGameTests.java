package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.ManaCollectorBlock;
import dev.logos.block.ManaCollectorBlockEntity;
import dev.logos.block.ManaSpreaderBlock;
import dev.logos.block.ManaSpreaderBlockEntity;
import dev.logos.runtime.ManaField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** The mana field (ManaField), the spreader that fills it and the collector that draws on it. */
final class ManaFieldGameTests {
    private ManaFieldGameTests() {}

    /**
     * Chunks {@code away} chunks east of the test, forced loaded {@code reach} chunks around, so other
     * tests' spreaders and collectors do not touch them. Returns the centre block of the middle chunk.
     */
    private static BlockPos faraway(GameTestHelper h, int away, int reach) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int cx = (origin.getX() >> 4) + away, cz = origin.getZ() >> 4;
        for (int x = -reach; x <= reach; x++) for (int z = -reach; z <= reach; z++) h.getLevel().setChunkForced(cx + x, cz + z, true);
        return new BlockPos(cx * 16 + 8, 100, cz * 16 + 8);
    }

    private static void assertLoaded(GameTestHelper h, BlockPos center, int reach) {
        for (int x = -reach; x <= reach; x++) for (int z = -reach; z <= reach; z++) {
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow((center.getX() >> 4) + x, (center.getZ() >> 4) + z) != null, "the chunks are still loading");
        }
    }

    /** One full chunk settles into 200, 130 next to it and 60 two chunks off (diagonals included), and spreads no further. */
    static void fieldSettlesIntoSteps(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = faraway(h, 300, 3);
        h.succeedWhen(() -> {
            assertLoaded(h, center, 3);
            for (int i = 0; i < 300; i++) {
                ManaField.pour(level, center, ManaField.CAP);
                ManaField.work(level);
            }
            ManaField.pour(level, center, ManaField.CAP);
            double c0 = ManaField.spread(level, center), c1 = ManaField.spread(level, center.east(16)), diag = ManaField.spread(level, center.offset(16, 0, 16)),
                    c2 = ManaField.spread(level, center.east(32)), c3 = ManaField.spread(level, center.east(48));
            h.assertTrue(Math.abs(c0 - 200) < 1e-6, "the full chunk holds " + c0);
            h.assertTrue(Math.abs(c1 - 130) < 2, "next to it is " + c1 + ", not 130");
            h.assertTrue(Math.abs(c2 - 60) < 2 && Math.abs(diag - 60) < 2, "two chunks off are " + c2 + " and " + diag + ", not 60");
            h.assertTrue(c3 < 1, "three chunks off still holds " + c3);
        });
    }

    /**
     * Caput mortuum holds mana back: beside a full chunk, a chunk with one block settles at 60 rather
     * than 130, one with two gets nothing; taking a block away lets the mana in again. Removing its
     * support makes it fall, and its marker still insulates after landing.
     */
    static void caputMortuumInsulates(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = faraway(h, 380, 1);
        BlockPos east = center.east(16), north = center.north(16), west = center.west(16);
        BlockState ash = LogosMod.CAPUT_MORTUUM_BLOCK.get().defaultBlockState();
        Runnable settle = () -> {
            for (int i = 0; i < 300; i++) {
                ManaField.pour(level, center, ManaField.CAP);
                ManaField.work(level);
            }
        };
        h.startSequence()
                .thenWaitUntil(() -> assertLoaded(h, center, 1))
                .thenExecute(() -> {
                    level.setBlock(east, ash, 3);
                    level.setBlock(north, ash, 3);
                    level.setBlock(north.east(2), ash, 3);
                    h.assertTrue(ManaField.insulation(level.getChunkAt(north)) == 2, "the north chunk counts " + ManaField.insulation(level.getChunkAt(north)) + " blocks");
                    settle.run();
                    double e = ManaField.spread(level, east), n = ManaField.spread(level, north), w = ManaField.spread(level, west);
                    h.assertTrue(Math.abs(w - 130) < 2, "the bare chunk holds " + w + ", not 130");
                    h.assertTrue(Math.abs(e - 60) < 2, "the chunk with one block holds " + e + ", not 60");
                    h.assertTrue(n < 1, "the chunk with two blocks still let in " + n);
                    level.setBlock(east, Blocks.AIR.defaultBlockState(), 3);
                    settle.run();
                    h.assertTrue(Math.abs(ManaField.spread(level, east) - 130) < 2, "with its block gone the chunk holds " + ManaField.spread(level, east) + ", not 130");
                    level.setBlock(north, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(north.east(2), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenExecute(() -> {
                    level.setBlock(east.below(4), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(east.below(3), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(east.below(2), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(east.below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(east, ash, 3);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(east).is(ash.getBlock()), "supported ash fell");
                    level.setBlock(east.below(), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(level.getBlockState(east).isAir(), "unsupported ash did not fall");
                    h.assertTrue(level.getBlockState(east.below(3)).is(ash.getBlock()), "ash did not land on the floor");
                    h.assertTrue(level.getBlockEntity(east) == null, "the falling ash left its marker behind");
                    h.assertTrue(level.getBlockEntity(east.below(3)) instanceof dev.logos.block.CaputMortuumBlock.Marker,
                            "the landed ash lost its marker");
                    h.assertTrue(ManaField.insulation(level.getChunkAt(east)) == 1, "the landed ash no longer insulates exactly once");
                })
                .thenExecute(() -> {
                    level.setBlock(east.below(3), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(east.below(4), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenSucceed();
    }

    /**
     * The spreader screen's survey: a full chunk reads 200 (in tenths) and flows out all four sides into
     * empty neighbours; once settled, nothing reads as flowing any more.
     */
    static void surveyShowsFieldAndFlow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = faraway(h, 420, 1);
        h.succeedWhen(() -> {
            assertLoaded(h, center, 1);
            ManaField.pour(level, center, ManaField.CAP);
            int[] s = ManaField.survey(level, center, 1);
            h.assertTrue(s.length == 18 && s[4] == 2000 && s[5] == 0, "the survey reads " + java.util.Arrays.toString(s));
            h.assertTrue(s[9 + 4] == 15 && s[9 + 5] == 0, "a full chunk beside empty ones should flow all four ways: " + java.util.Arrays.toString(s));
            for (int i = 0; i < 300; i++) {
                ManaField.pour(level, center, ManaField.CAP);
                ManaField.work(level);
            }
            ManaField.pour(level, center, ManaField.CAP);
            int[] settled = ManaField.survey(level, center, 1);
            h.assertTrue(java.util.Arrays.stream(settled, 9, 18).allMatch(b -> b == 0), "a settled field still reads as flowing: " + java.util.Arrays.toString(settled));
            h.assertTrue(Math.abs(settled[5] - 1300) < 20, "the chunk beside it reads " + settled[5] + " tenths, not about 1300");
        });
    }

    /** Nine caput mortuum press into a block, and the block breaks back into nine. */
    static void caputMortuumBlockCrafts(GameTestHelper h) {
        var nine = net.minecraft.world.item.crafting.CraftingInput.of(3, 3,
                java.util.Collections.nCopies(9, new ItemStack(LogosMod.CAPUT_MORTUUM.get())));
        ItemStack block = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, nine, h.getLevel())
                .map(r -> r.value().assemble(nine)).orElse(ItemStack.EMPTY);
        h.assertTrue(block.is(LogosMod.CAPUT_MORTUUM_BLOCK_ITEM.get()), "nine caput mortuum made " + block);
        var one = net.minecraft.world.item.crafting.CraftingInput.of(1, 1, java.util.List.of(new ItemStack(LogosMod.CAPUT_MORTUUM_BLOCK_ITEM.get())));
        ItemStack back = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, one, h.getLevel())
                .map(r -> r.value().assemble(one)).orElse(ItemStack.EMPTY);
        h.assertTrue(back.is(LogosMod.CAPUT_MORTUUM.get()) && back.getCount() == 9, "the block broke into " + back);
        h.succeed();
    }

    /** Collectors in one chunk share its ambient mana: two together take no more than the chunk gives, and neither more than its own limit. */
    static void collectorsShareAmbient(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = faraway(h, 340, 0);
        BlockPos a = center, b = center.east(2);
        double[] before = new double[2];
        h.startSequence()
                .thenWaitUntil(() -> assertLoaded(h, center, 0))
                .thenExecute(() -> {
                    // Facing up into the air: nothing to pass their mana on to.
                    BlockState collector = LogosMod.MANA_COLLECTOR.get().defaultBlockState().setValue(ManaCollectorBlock.FACING, Direction.UP);
                    level.setBlock(a, collector, 3);
                    level.setBlock(b, collector, 3);
                })
                .thenIdle(3 * ManaField.PERIOD)
                .thenExecute(() -> {
                    before[0] = collector(h, a).mana();
                    before[1] = collector(h, b).mana();
                })
                .thenIdle(ManaField.PERIOD)
                .thenExecute(() -> {
                    double ga = collector(h, a).mana() - before[0], gb = collector(h, b).mana() - before[1];
                    double rate = ManaField.ambientRate(level, a);
                    h.assertTrue(ga > 0 && gb > 0, "the collectors took in " + ga + " and " + gb);
                    h.assertTrue(ga <= ManaField.COLLECTOR_AMBIENT + 1e-9 && gb <= ManaField.COLLECTOR_AMBIENT + 1e-9, "one took more than its limit");
                    h.assertTrue(ga + gb <= rate + 1e-9, "two collectors took " + (ga + gb) + " of a chunk that gives " + rate);
                    level.setBlock(a, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenSucceed();
    }

    private static ManaCollectorBlockEntity collector(GameTestHelper h, BlockPos at) {
        return (ManaCollectorBlockEntity) h.getLevel().getBlockEntity(at);
    }

    /**
     * A spreader burns a mana shard into its chunk's air and lights up; a collector placed against an
     * alchemy table faces it and feeds it from the air.
     */
    static void spreaderFeedsTableThroughCollector(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 1, z, Blocks.STONE);
        BlockPos main = new BlockPos(2, 2, 2);
        BlockState table = LogosMod.ALCHEMY_TABLE.get().defaultBlockState().setValue(AlchemyTableBlock.FACING, Direction.SOUTH);
        h.setBlock(main, table);
        table.getBlock().setPlacedBy(h.getLevel(), h.absolutePos(main), table, null, new ItemStack(LogosMod.ALCHEMY_TABLE_ITEM.get()));
        AlchemyTableBlockEntity tableEntity = (AlchemyTableBlockEntity) h.getBlockEntity(main, AlchemyTableBlockEntity.class);
        // On top of the table, facing down into it.
        h.setBlock(main.above(), LogosMod.MANA_COLLECTOR.get().defaultBlockState().setValue(ManaCollectorBlock.FACING, Direction.DOWN));
        BlockPos spreaderAt = new BlockPos(0, 2, 2);
        h.setBlock(spreaderAt, LogosMod.MANA_SPREADER.get().defaultBlockState());
        ManaSpreaderBlockEntity spreader = (ManaSpreaderBlockEntity) h.getBlockEntity(spreaderAt, ManaSpreaderBlockEntity.class);
        try (Transaction tx = Transaction.openRoot()) {
            h.assertTrue(spreader.fuel().insert(ItemResource.of(LogosMod.MANA_SHARD.get()), 1, tx) == 1, "the drawer refused a mana shard");
            tx.commit();
        }
        h.succeedWhen(() -> {
            h.assertTrue(ManaField.spread(h.getLevel(), h.absolutePos(spreaderAt)) > 20, "the chunk's air holds " + ManaField.spread(h.getLevel(), h.absolutePos(spreaderAt)));
            h.assertTrue(h.getBlockState(spreaderAt).getValue(ManaSpreaderBlock.ACTIVE), "the spreader is not lit");
            h.assertTrue(tableEntity.mana() > 1, "the table got " + tableEntity.mana() + " mana");
        });
    }
}
