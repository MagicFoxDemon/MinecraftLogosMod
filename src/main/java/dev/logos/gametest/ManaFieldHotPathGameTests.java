package dev.logos.gametest;

import java.util.Map;

import dev.logos.LogosMod;
import dev.logos.block.CaputMortuumBlock;
import dev.logos.block.ManaCollectorBlock;
import dev.logos.block.ManaCollectorBlockEntity;
import dev.logos.block.ManaSpreaderBlockEntity;
import dev.logos.runtime.ManaField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The field's hot paths: caput mortuum read from a count kept by its markers, chunks saved only when
 * their mana has really moved, and devices acting in turn. Tests that call {@link ManaField#work} many
 * times do it in the End, because in the overworld it would also drain the chunks of tests running alongside.
 */
final class ManaFieldHotPathGameTests {
    private ManaFieldHotPathGameTests() {}

    /** Forces the chunks around the one {@code away} chunks east of the test and loads them now, so the test need not wait on them. */
    private static BlockPos load(GameTestHelper h, ServerLevel level, int away, int reach) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int cx = (origin.getX() >> 4) + away, cz = origin.getZ() >> 4;
        for (int x = -reach; x <= reach; x++) for (int z = -reach; z <= reach; z++) {
            level.setChunkForced(cx + x, cz + z, true);
            level.getChunk(cx + x, cz + z);
        }
        return new BlockPos(cx * 16 + 8, 100, cz * 16 + 8);
    }

    private static void free(ServerLevel level, BlockPos center, int reach) {
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        for (int x = -reach; x <= reach; x++) for (int z = -reach; z <= reach; z++) level.setChunkForced(cx + x, cz + z, false);
    }

    private static ServerLevel end(GameTestHelper h) {
        ServerLevel end = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(end != null, "the test server has no End");
        return end;
    }

    /** The count itself, not capped at MAX_INSULATION; 0 where there is no entry. */
    private static int counted(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> counts = level.getExistingDataOrNull(LogosMod.CAPUT_MORTUUM_COUNTS);
        return counts == null ? 0 : counts.getOrDefault(ChunkPos.pack(pos), 0);
    }

    /** The truth the count must match: the markers actually among the chunk's block entities. */
    private static int markers(LevelChunk chunk) {
        int n = 0;
        for (var be : chunk.getBlockEntities().values()) if (be instanceof CaputMortuumBlock.Marker) n++;
        return n;
    }

    private static void assertCount(GameTestHelper h, ServerLevel level, BlockPos pos, int expected, String when) {
        int counted = counted(level, pos), truth = markers(level.getChunkAt(pos));
        h.assertTrue(counted == expected && truth == expected, when + ": the count says " + counted + ", the chunk holds " + truth + ", expected " + expected);
    }

    /**
     * The count stays right however a block comes or goes: placed, broken, replaced, blown up, or its block
     * entity swapped or dropped the way another mod might. A piston cannot move it (block entities do not
     * move). Insulation reads the count and not the chunk's block entities.
     */
    static void countFollowsBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = load(h, level, 500, 0);
        BlockState ash = LogosMod.CAPUT_MORTUUM_BLOCK.get().defaultBlockState();
        // Stone under each, so the ash stays where it is put.
        BlockPos a = center, b = center.east(), c = center.east(2), d = center.east(3), e = center.south(4);
        for (BlockPos p : new BlockPos[] {a, b, c, d, e}) level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
        LevelChunk chunk = level.getChunkAt(center);
        h.startSequence()
                .thenExecute(() -> {
                    assertCount(h, level, center, 0, "an empty chunk");
                    level.setBlock(a, ash, 3);
                    assertCount(h, level, center, 1, "after placing one");
                    level.setBlock(b, ash, 3);
                    level.setBlock(c, ash, 3);
                    level.setBlock(d, ash, 3);
                    assertCount(h, level, center, 4, "after placing four");
                    h.assertTrue(ManaField.insulation(chunk) == ManaField.MAX_INSULATION, "four blocks insulate as " + ManaField.insulation(chunk));
                    h.assertTrue(counted(level, center.east(16)) == 0, "the next chunk was counted too");

                    level.destroyBlock(a, false);
                    assertCount(h, level, center, 3, "after breaking one");
                    h.assertTrue(ManaField.insulation(chunk) == 3, "three blocks insulate as " + ManaField.insulation(chunk));
                    level.setBlock(b, Blocks.STONE.defaultBlockState(), 3);
                    assertCount(h, level, center, 2, "after replacing one");
                    h.assertTrue(ManaField.insulation(chunk) == 2, "two blocks insulate as " + ManaField.insulation(chunk));

                    // Another mod swapping or dropping the block entity under a block that stays.
                    level.setBlockEntity(new CaputMortuumBlock.Marker(c, ash));
                    assertCount(h, level, center, 2, "after a marker replaced another");
                    level.removeBlockEntity(c);
                    assertCount(h, level, center, 1, "after a marker was dropped");
                    level.setBlockEntity(new CaputMortuumBlock.Marker(c, ash));
                    assertCount(h, level, center, 2, "after a marker was set again");

                    // A marker the chunk holds but was never told of: walking the block entities would count it.
                    BlockPos behind = center.west(3);
                    chunk.getBlockEntities().put(behind, new CaputMortuumBlock.Marker(behind, ash));
                    int insulation = ManaField.insulation(chunk);
                    chunk.getBlockEntities().remove(behind);
                    h.assertTrue(insulation == 2, "insulation walked the chunk's block entities: it reads " + insulation);

                    level.explode(null, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 3f, Level.ExplosionInteraction.BLOCK);
                    h.assertTrue(level.getBlockState(c).isAir() && level.getBlockState(d).isAir(), "the blast left the ash standing");
                    assertCount(h, level, center, 0, "after the blast");

                    level.setBlock(e, ash, 3);
                    level.setBlock(e.north(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH), 3);
                    level.setBlock(e.north(2), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(e).is(ash.getBlock()), "a piston moved the ash");
                    assertCount(h, level, center, 1, "after a piston tried it");
                    level.setBlock(e, Blocks.AIR.defaultBlockState(), 3);
                    assertCount(h, level, center, 0, "with all of them gone");
                    h.assertTrue(level.getData(LogosMod.CAPUT_MORTUUM_COUNTS).get(ChunkPos.pack(center)) == null, "an empty chunk keeps its entry");
                    free(level, center, 0);
                })
                .thenSucceed();
    }

    /**
     * A difference within the plain threshold never asks for insulation; only one past it does. Past it the
     * count still decides: two blocks in the next chunk keep the mana out.
     */
    static void workSkipsInsulation(GameTestHelper h) {
        ServerLevel end = end(h);
        BlockPos center = load(h, end, 600, 1);
        BlockPos east = center.east(16), west = center.west(16);
        BlockState ash = LogosMod.CAPUT_MORTUUM_BLOCK.get().defaultBlockState();
        h.startSequence()
                .thenExecute(() -> {
                    double plain = dev.logos.LogosConfig.manaSpreadThreshold();
                    ManaField.pour(end, center, plain / 2);
                    long before = ManaField.insulationAsked();
                    ManaField.work(end);
                    h.assertTrue(ManaField.insulationAsked() == before, "a small difference asked for insulation " + (ManaField.insulationAsked() - before) + " times");
                    h.assertTrue(ManaField.spread(end, east) == 0, "a small difference still flowed");

                    end.setBlock(east, ash, 3);
                    end.setBlock(east.east(2), ash, 3);
                    ManaField.pour(end, center, ManaField.CAP);
                    before = ManaField.insulationAsked();
                    ManaField.work(end);
                    h.assertTrue(ManaField.insulationAsked() > before, "a large difference never asked for insulation");
                    h.assertTrue(ManaField.spread(end, west) > 0, "a large difference did not flow to the bare chunk");
                    h.assertTrue(ManaField.spread(end, east) == 0, "two blocks of ash let in " + ManaField.spread(end, east));

                    end.setBlock(east, Blocks.AIR.defaultBlockState(), 3);
                    end.setBlock(east.east(2), Blocks.AIR.defaultBlockState(), 3);
                    free(end, center, 1);
                })
                .thenSucceed();
    }

    /**
     * Small moves do not mark a chunk for saving, but they add up: about SAVE_STEP of leaking does. A real move does at
     * once. What older worlds saved still reads, and what is written has not changed shape.
     */
    static void smallMovesStayUnsaved(GameTestHelper h) {
        ServerLevel end = end(h);
        BlockPos center = load(h, end, 640, 0);
        LevelChunk chunk = end.getChunkAt(center);
        h.startSequence()
                .thenExecute(() -> {
                    double start = 60;
                    ManaField.pour(end, center, start);
                    h.assertTrue(chunk.isUnsaved(), "pouring 60 did not mark the chunk for saving");
                    chunk.tryMarkSaved();

                    ManaField.work(end);
                    h.assertTrue(ManaField.spread(end, center) < start, "the chunk did not leak");
                    h.assertTrue(!chunk.isUnsaved(), "a second's leak marked the chunk for saving");

                    int seconds = 1;
                    while (!chunk.isUnsaved() && seconds < 1000) {
                        ManaField.work(end);
                        seconds++;
                    }
                    double perSecond = start * ManaField.LEAK;
                    h.assertTrue(Math.abs(seconds - ManaField.SAVE_STEP / perSecond) < 5, "leaking took " + seconds + " seconds to mark the chunk, not about " + ManaField.SAVE_STEP / perSecond);
                    h.assertTrue(Math.abs(ManaField.spread(end, center) - (start - ManaField.SAVE_STEP)) < 0.1, "the chunk holds " + ManaField.spread(end, center) + " once it asks to be saved");

                    chunk.tryMarkSaved();
                    ManaField.pour(end, center, ManaField.SAVE_STEP * 2);
                    h.assertTrue(chunk.isUnsaved(), "a real move did not mark the chunk for saving");
                    chunk.tryMarkSaved();

                    CompoundTag old = new CompoundTag();
                    old.putDouble("mana", 42.5);
                    var read = ManaField.Air.CODEC.codec().parse(NbtOps.INSTANCE, old).getOrThrow();
                    h.assertTrue(read.mana() == 42.5, "an old save reads as " + read.mana());
                    h.assertTrue(old.equals(ManaField.Air.CODEC.codec().encodeStart(NbtOps.INSTANCE, read).getOrThrow()), "the saved shape changed");
                    free(end, center, 0);
                })
                .thenSucceed();
    }

    /**
     * Unloading takes a chunk's count with it and loading brings it back from the markers. Leaking that never
     * marked the chunk is lost with the unload, but no more than SAVE_STEP of it.
     */
    static void reloadKeepsCountAndValue(GameTestHelper h) {
        ServerLevel end = end(h);
        BlockPos center = load(h, end, 680, 0);
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        BlockState ash = LogosMod.CAPUT_MORTUUM_BLOCK.get().defaultBlockState();
        double[] live = new double[1];
        Runnable unload = () -> end.setChunkForced(cx, cz, false);
        Runnable awaitGone = () -> {
            // Up to the moment it goes, the field keeps working it.
            if (end.getChunkSource().getChunkNow(cx, cz) != null) live[0] = ManaField.spread(end, center);
            h.assertTrue(end.getChunkSource().getChunkNow(cx, cz) == null, "the chunk is still loaded");
            h.assertTrue(counted(end, center) == 0, "the unloaded chunk's count stayed behind");
        };
        h.startSequence()
                .thenExecute(() -> {
                    end.setBlock(center.below(), Blocks.STONE.defaultBlockState(), 3);
                    end.setBlock(center.below().east(), Blocks.STONE.defaultBlockState(), 3);
                    end.setBlock(center, ash, 3);
                    end.setBlock(center.east(), ash, 3);
                    ManaField.pour(end, center, 60);
                    assertCount(h, end, center, 2, "as placed");
                    unload.run();
                })
                .thenWaitUntil(awaitGone)
                .thenExecute(() -> {
                    end.setChunkForced(cx, cz, true);
                    LevelChunk back = end.getChunk(cx, cz);
                    assertCount(h, end, center, 2, "after the first reload");
                    h.assertTrue(ManaField.insulation(back) == 2, "the reloaded chunk insulates as " + ManaField.insulation(back));
                    h.assertTrue(Math.abs(ManaField.spread(end, center) - live[0]) < 1e-9, "a chunk marked for saving came back with " + ManaField.spread(end, center) + ", not " + live[0]);
                    back.tryMarkSaved();
                    for (int i = 0; i < 50; i++) ManaField.work(end);
                    h.assertTrue(!back.isUnsaved(), "fifty seconds of leak marked the chunk for saving");
                    unload.run();
                })
                .thenWaitUntil(awaitGone)
                .thenExecute(() -> {
                    end.setChunkForced(cx, cz, true);
                    LevelChunk back = end.getChunk(cx, cz);
                    assertCount(h, end, center, 2, "after the second reload");
                    double value = ManaField.spread(end, center);
                    h.assertTrue(value > 0, "the field did not come back");
                    h.assertTrue(Math.abs(value - live[0]) <= ManaField.SAVE_STEP, "the chunk came back with " + value + ", more than a step from " + live[0]);
                    h.assertTrue(value > live[0], "the leak was written after all, so this did not test the unwritten case");
                    back.getLevel().setBlock(center, Blocks.AIR.defaultBlockState(), 3);
                    back.getLevel().setBlock(center.east(), Blocks.AIR.defaultBlockState(), 3);
                    assertCount(h, end, center, 0, "with the blocks gone");
                    free(end, center, 0);
                })
                .thenSucceed();
    }

    /**
     * Collectors and spreaders act in their own tick of the second, as storage tanks do, and take or pour just
     * what they did when they all acted together.
     */
    static void devicesTakeTurns(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = load(h, level, 720, 0);
        BlockPos spreaderAt = load(h, level, 723, 0);
        // Two floors apart, which alone puts them in different ticks; each faces empty air, so they keep what they take.
        BlockPos[] at = {center, center.above(2), spreaderAt};
        BlockState collector = LogosMod.MANA_COLLECTOR.get().defaultBlockState();
        level.setBlock(at[0], collector.setValue(ManaCollectorBlock.FACING, Direction.DOWN), 3);
        level.setBlock(at[1], collector.setValue(ManaCollectorBlock.FACING, Direction.UP), 3);
        level.setBlock(at[2], LogosMod.MANA_SPREADER.get().defaultBlockState(), 3);
        ManaCollectorBlockEntity a = (ManaCollectorBlockEntity) level.getBlockEntity(at[0]), b = (ManaCollectorBlockEntity) level.getBlockEntity(at[1]);
        ManaSpreaderBlockEntity spreader = (ManaSpreaderBlockEntity) level.getBlockEntity(at[2]);
        spreader.receiveMana(ManaSpreaderBlockEntity.MAX_MANA);

        double share = Math.min(ManaField.COLLECTOR_AMBIENT, ManaField.ambientRate(level, center) / 2);
        double[] seen = {a.mana(), b.mana(), spreader.mana()}, last = new double[3];
        int[] turns = new int[3];
        String[] wrong = new String[1];
        h.startSequence()
                .thenWaitUntil(() -> {
                    double[] now = {a.mana(), b.mana(), spreader.mana()};
                    long tick = level.getGameTime() % ManaField.PERIOD;
                    for (int i = 0; i < 3; i++) {
                        if (now[i] == seen[i]) continue;
                        long own = Math.floorMod(at[i].asLong(), ManaField.PERIOD);
                        if (tick != own && wrong[0] == null) wrong[0] = "device " + i + " acted in tick " + tick + " of the second, not " + own;
                        last[i] = Math.abs(now[i] - seen[i]);
                        seen[i] = now[i];
                        turns[i]++;
                    }
                    h.assertTrue(wrong[0] == null, String.valueOf(wrong[0]));
                    h.assertTrue(turns[0] >= 3 && turns[1] >= 3 && turns[2] >= 3, "the devices have acted " + java.util.Arrays.toString(turns) + " times");
                })
                .thenExecute(() -> {
                    h.assertTrue(Math.abs(last[0] - share) < 1e-9 && Math.abs(last[1] - share) < 1e-9, "the collectors took " + last[0] + " and " + last[1] + " in a second, not " + share);
                    h.assertTrue(last[2] == ManaSpreaderBlockEntity.POUR, "the spreader poured " + last[2] + " in a second");
                    for (BlockPos p : at) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    free(level, center, 0);
                    free(level, spreaderAt, 0);
                })
                .thenSucceed();
    }
}
