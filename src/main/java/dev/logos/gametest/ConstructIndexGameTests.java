package dev.logos.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.logos.LogosMod;
import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.ConstructIndex;
import dev.logos.runtime.Constructs;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The index behind construct collisions ({@link ConstructIndex}). Each test works at its own site far from
 * the others, with enough walls in the level that queries go by column. Counters are read around a single
 * synchronous call, so tests running alongside cannot touch them.
 */
final class ConstructIndexGameTests {
    private static final String WALL = "(construct :shape wall :at here :width 5 :height 3 :life 20)";
    /** More than a query's columns: fewer than this and the index looks at each construct instead. */
    private static final int WALLS = 6;

    private ConstructIndexGameTests() {}

    /** Forces and loads the chunks around the one {@code away} chunks east of the test, so nothing need wait on them. */
    private static BlockPos site(GameTestHelper h, int away, int reach) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int cx = (origin.getX() >> 4) + away, cz = origin.getZ() >> 4;
        for (int x = -reach; x <= reach; x++) for (int z = -reach; z <= reach; z++) {
            h.getLevel().setChunkForced(cx + x, cz + z, true);
            h.getLevel().getChunk(cx + x, cz + z);
        }
        return new BlockPos(cx * 16 + 8, 100, cz * 16 + 8);
    }

    private static void free(GameTestHelper h, BlockPos center, int reach) {
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        for (int x = -reach; x <= reach; x++) for (int z = -reach; z <= reach; z++) h.getLevel().setChunkForced(cx + x, cz + z, false);
    }

    private static ConstructEntity raise(GameTestHelper h, ServerPlayer p, String src, BlockPos at) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, Vec3.atCenterOf(at));
        h.assertTrue(inst != null && !inst.dead, "cast failed: " + src + (inst == null ? "" : " — " + inst.endReason()));
        return newest(h, new AABB(at).inflate(0.5));
    }

    private static ConstructEntity newest(GameTestHelper h, AABB around) {
        List<ConstructEntity> found = ConstructEntity.near(h.getLevel(), around);
        h.assertTrue(!found.isEmpty(), "no construct around " + around);
        return found.getLast();
    }

    /** Enough walls in the level that queries go by column, close together in one spot. */
    private static List<ConstructEntity> walls(GameTestHelper h, ServerPlayer p, BlockPos at) {
        List<ConstructEntity> walls = new ArrayList<>();
        for (int i = 0; i < WALLS; i++) walls.add(raise(h, p, WALL, at.south(i * 2)));
        return walls;
    }

    private static Husk husk(GameTestHelper h, ServerLevel level, Vec3 at) {
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        husk.snapTo(at.x, at.y, at.z, 0, 0);
        h.assertTrue(level.addFreshEntity(husk), "the husk was not added");
        return husk;
    }

    /** Whether a husk at {@code from} moving {@code by} gets all the way there. */
    private static boolean walksFree(GameTestHelper h, Vec3 from, Vec3 by) {
        Husk husk = husk(h, h.getLevel(), from);
        husk.move(MoverType.SELF, by);
        boolean free = husk.position().distanceTo(from.add(by)) < 1e-6;
        husk.discard();
        return free;
    }

    /**
     * A creature moving far from every construct, or in a level with none near, looks at none of them, so no
     * shape is reached. One walking into a wall does look, and is stopped.
     */
    static void skipsFarEntities(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ConstructGameTests.caster(h);
        BlockPos at = site(h, 760, 0);
        BlockPos away = site(h, 764, 0);
        walls(h, p, at);

        Husk far = husk(h, level, Vec3.atCenterOf(away));
        Vec3 start = far.position();
        long before = ConstructIndex.looked();
        far.move(MoverType.SELF, new Vec3(2, 0, 0));
        h.assertTrue(ConstructIndex.looked() == before, "a husk 64 blocks off looked at " + (ConstructIndex.looked() - before) + " constructs");
        h.assertTrue(far.position().distanceTo(start.add(2, 0, 0)) < 1e-6, "the far husk did not move freely");
        far.discard();

        // The same place in another level: the walls here are not there.
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "the test server has no Nether");
        Husk elsewhere = new Husk(EntityType.HUSK, nether);
        AABB onWall = new AABB(at).inflate(1);
        List<VoxelShape> none = List.of();
        before = ConstructIndex.looked();
        h.assertTrue(Constructs.withCollisions(none, nether, elsewhere, onWall) == none, "the walls collide in another dimension");
        h.assertTrue(ConstructEntity.near(nether, onWall).isEmpty(), "the walls are found in another dimension");
        h.assertTrue(ConstructIndex.looked() == before, "a query in another dimension looked at " + (ConstructIndex.looked() - before) + " constructs");

        // Right at the wall the same move does look, and stops short.
        Vec3 front = Vec3.atCenterOf(at).add(0, -1, 3);
        Husk near = husk(h, level, front);
        before = ConstructIndex.looked();
        near.move(MoverType.SELF, new Vec3(0, 0, -6));
        h.assertTrue(ConstructIndex.looked() > before, "a husk at the wall looked at nothing");
        h.assertTrue(near.getZ() > at.getZ(), "the wall did not stop the husk: z=" + near.getZ() + ", wall at " + at.getZ());
        near.discard();
        free(h, at, 0);
        free(h, away, 0);
        h.succeed();
    }

    /** A projectile never asks for collisions; a creature in the same place does. Walls stop arrows anyway (construct_one_way_stops_arrows). */
    static void collisionSkipsProjectiles(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ConstructGameTests.caster(h);
        BlockPos at = site(h, 780, 0);
        walls(h, p, at);
        Arrow arrow = new Arrow(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, new ItemStack(Items.ARROW), null);
        AABB onWall = new AABB(at).inflate(1);
        List<VoxelShape> none = List.of();
        long before = ConstructIndex.looked();
        h.assertTrue(Constructs.withCollisions(none, level, arrow, onWall) == none, "an arrow got collisions");
        h.assertTrue(ConstructIndex.looked() == before, "an arrow's collisions looked at " + (ConstructIndex.looked() - before) + " constructs");
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.snapTo(at.getX() + 0.5, at.getY() - 1, at.getZ() + 3.5, 0, 0);
        h.assertTrue(!Constructs.withCollisions(none, level, husk, onWall).isEmpty(), "a husk got no collisions");
        h.assertTrue(ConstructIndex.looked() > before, "a husk's collisions looked at nothing");
        free(h, at, 0);
        h.succeed();
    }

    /**
     * Each cell's shape is built once, and built again where the construct's geometry changes: moved, turned,
     * resized. The shapes are the cells themselves.
     */
    static void shapesCached(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ConstructGameTests.caster(h);
        BlockPos at = site(h, 800, 0);
        ConstructEntity wall = raise(h, p, WALL, at);
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.snapTo(at.getX() + 0.5, at.getY() - 1, at.getZ() + 3.5, 0, 0);
        AABB onWall = wall.geometry().bounds;
        List<VoxelShape> first = Constructs.withCollisions(List.of(), level, husk, onWall);
        h.assertTrue(first.size() == wall.geometry().cells.size(), "asked for the whole wall, got " + first.size() + " shapes of " + wall.geometry().cells.size());
        for (int i = 0; i < first.size(); i++) {
            AABB shape = first.get(i).bounds(), cell = wall.geometry().cells.get(i);
            h.assertTrue(shape.equals(cell), "shape " + shape + " is not cell " + cell);
        }
        List<VoxelShape> again = Constructs.withCollisions(List.of(), level, husk, onWall);
        for (int i = 0; i < first.size(); i++) h.assertTrue(first.get(i) == again.get(i), "shape " + i + " was built again for the same geometry");

        wall.setPos(wall.position().add(1, 0, 0));
        List<VoxelShape> moved = Constructs.withCollisions(List.of(), level, husk, wall.geometry().bounds);
        h.assertTrue(moved.size() == first.size() && moved.getFirst() != first.getFirst(), "moving the wall kept its shapes");
        h.assertTrue(Math.abs(moved.getFirst().bounds().minX - first.getFirst().bounds().minX - 1) < 1e-6, "the moved shape did not move with it");

        wall.face(new Vec3(1, 0, 0));
        List<VoxelShape> turned = Constructs.withCollisions(List.of(), level, husk, wall.geometry().bounds);
        h.assertTrue(!turned.isEmpty() && turned.getFirst().bounds().minX != moved.getFirst().bounds().minX, "turning the wall kept its shapes");

        wall.setup(ConstructShape.WALL, Vec3.atCenterOf(at), new Vec3(0, 0, 1), 9, 3, 1, 1, false, false, Element.ARCANE, null, p.getId(), null);
        List<VoxelShape> resized = Constructs.withCollisions(List.of(), level, husk, wall.geometry().bounds);
        h.assertTrue(resized.size() > first.size(), "resizing the wall left " + resized.size() + " shapes, had " + first.size());
        free(h, at, 0);
        h.succeed();
    }

    /** A construct is found where it has gone and not where it was, whether it travelled, was turned or was resized. Queries answer oldest first. */
    static void followsMoved(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ConstructGameTests.caster(h);
        BlockPos at = site(h, 820, 0);
        BlockPos dest = site(h, 823, 0);
        List<ConstructEntity> walls = walls(h, p, at);
        ConstructEntity traveller = walls.getFirst(), turner = walls.get(1), grower = walls.get(2);
        AABB was = new AABB(at).inflate(0.5), there = new AABB(dest).inflate(0.5);
        h.assertTrue(ConstructEntity.near(level, there).isEmpty(), "something already stands where the wall will go");
        // The six stand across two columns, so this one goes by column; the wide one looks at each construct.
        AABB allSix = new AABB(at).inflate(1).expandTowards(0, 0, 12);
        h.assertTrue(ConstructEntity.near(level, allSix).equals(walls), "by column, queries did not answer each wall once, oldest first");
        List<ConstructEntity> wide = new ArrayList<>(ConstructEntity.near(level, allSix.inflate(200)));
        wide.retainAll(walls);
        h.assertTrue(wide.equals(walls), "looking at each, queries did not answer each wall once, oldest first");
        traveller.owner().moveTo(Vec3.atCenterOf(dest), 2);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(ConstructEntity.near(level, there).contains(traveller), "the index has not followed the wall there"))
                .thenExecute(() -> {
                    h.assertTrue(!ConstructEntity.near(level, was).contains(traveller), "the index still has the wall where it was");
                    h.assertTrue(!walksFree(h, Vec3.atCenterOf(dest).add(0, -1, 3), new Vec3(0, 0, -6)), "the wall did not stop a husk where it now stands");
                    h.assertTrue(walksFree(h, Vec3.atCenterOf(at).add(0, -1, -3), new Vec3(0, 0, 4)), "a husk is still stopped where the wall stood");

                    // Turned to face east, a wide wall lies north to south: found 2 blocks south of its centre and not 2 east.
                    AABB east = new AABB(turner.geometry().center.add(2, 0, 0), turner.geometry().center.add(2, 0, 0)).inflate(0.1);
                    AABB south = new AABB(turner.geometry().center.add(0, 0, 2), turner.geometry().center.add(0, 0, 2)).inflate(0.1);
                    h.assertTrue(ConstructEntity.near(level, east).contains(turner), "a wall facing south should reach 2 blocks east of its centre");
                    turner.owner().turn(new Vec3(1, 0, 0));
                    h.assertTrue(!ConstructEntity.near(level, east).contains(turner), "the index kept the wall's old bounds after it turned");
                    h.assertTrue(ConstructEntity.near(level, south).contains(turner), "the index did not take the wall's new bounds after it turned");

                    // Grown to 16 wide it reaches into the next column, and must be filed there too.
                    Vec3 centre = grower.geometry().center;
                    AABB farEnd = new AABB(centre.add(7.5, 0, 0), centre.add(7.5, 0, 0)).inflate(0.1);
                    h.assertTrue(!ConstructEntity.near(level, farEnd).contains(grower), "the small wall reaches too far already");
                    grower.setup(ConstructShape.WALL, centre, new Vec3(0, 0, 1), 16, 3, 1, 1, false, false, Element.ARCANE, null, p.getId(), null);
                    h.assertTrue(ConstructEntity.near(level, farEnd).contains(grower), "the index kept the wall's old bounds after it grew");
                    free(h, at, 0);
                    free(h, dest, 0);
                })
                .thenSucceed();
    }

    /** A wall held up by its caster is found around them wherever they go, and not where they were. */
    static void followsCaster(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ConstructGameTests.caster(h);
        BlockPos at = site(h, 840, 0);
        walls(h, p, at);
        SpellInstance inst = Casting.cast(p, Casting.compile("(construct :shape wall :at (ahead 3) :follow :life 20)"), null, p.position());
        h.assertTrue(inst != null && !inst.dead, "the cast failed");
        ConstructEntity wall = newest(h, p.getBoundingBox().inflate(8));
        h.assertTrue(wall.follows(), "the wall found does not follow");
        Vec3 from = p.position(), to = from.add(48, 0, 0);
        AABB wasThere = p.getBoundingBox().inflate(8);
        p.setPos(to);
        AABB nowThere = new AABB(to, to).inflate(8);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(ConstructEntity.near(level, nowThere).contains(wall), "the index has not followed the wall to its caster"))
                .thenExecute(() -> {
                    h.assertTrue(!ConstructEntity.near(level, wasThere).contains(wall), "the index still has the wall where its caster was");
                    free(h, at, 0);
                })
                .thenSucceed();
    }

    /**
     * What leaves a level leaves its index: discarded, ended with its spell, sent to another dimension (a new
     * construct there, in that level's index), or never tracked at all. A query where it stood looks at nothing,
     * so no entry is left behind.
     */
    static void forgetsRemoved(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ConstructGameTests.caster(h);
        BlockPos at = site(h, 860, 0);
        walls(h, p, at);

        BlockPos spot = site(h, 864, 0);
        AABB on = new AABB(spot).inflate(0.5);
        ConstructEntity discarded = raise(h, p, WALL, spot);
        discarded.discard();
        assertGone(h, level, on, "discarded");

        // In a chunk nothing has loaded: added, never tracked. Dimension travel below lands the same way, so nothing waits on the Nether generating.
        BlockPos unready = at.east(16 * 40);
        ConstructEntity untracked = LogosMod.CONSTRUCT.get().create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        untracked.setup(ConstructShape.WALL, Vec3.atCenterOf(unready), new Vec3(0, 0, 1), 5, 3, 1, 1, false, false, Element.ARCANE, null, p.getId(), null);
        h.assertTrue(level.addFreshEntity(untracked), "the untracked wall was not added");
        AABB onUnready = new AABB(unready).inflate(0.5);
        h.assertTrue(ConstructEntity.near(level, onUnready).contains(untracked), "the index did not take a wall in an unready chunk");
        untracked.discard();
        assertGone(h, level, onUnready, "never tracked");

        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "the test server has no Nether");
        ConstructEntity traveller = raise(h, p, WALL, spot);
        var arrived = traveller.teleport(new TeleportTransition(nether, Vec3.atCenterOf(spot), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        h.assertTrue(arrived instanceof ConstructEntity, "the wall did not change dimension: " + arrived);
        h.assertTrue(traveller.isRemoved(), "the old wall stayed behind");
        assertGone(h, level, on, "gone to another dimension");
        h.assertTrue(ConstructEntity.near(nether, on).contains(arrived), "the wall is not in the new dimension's index");
        arrived.discard();
        assertGone(h, nether, on, "gone from the new dimension");

        ConstructEntity expiring = raise(h, p, "(construct :shape wall :at here :width 5 :height 3 :life 0.5)", spot);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(expiring.isRemoved(), "the wall outlived its spell"))
                .thenExecute(() -> {
                    assertGone(h, level, on, "ended with its spell");
                    free(h, at, 0);
                    free(h, spot, 0);
                })
                .thenSucceed();
    }

    private static void assertGone(GameTestHelper h, ServerLevel level, AABB on, String how) {
        long before = ConstructIndex.looked();
        h.assertTrue(ConstructEntity.near(level, on).isEmpty(), "a wall " + how + " is still found");
        if (level == h.getLevel()) h.assertTrue(ConstructIndex.looked() == before, "a wall " + how + " stayed in the index");
    }
}
