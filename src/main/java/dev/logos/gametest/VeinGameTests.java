package dev.logos.gametest;

import java.util.Map;

import dev.logos.lang.Status;
import dev.logos.lang.Motion;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.VeinObj;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The vein: it crawls over the ground, steps up and stops at walls, strikes what it meets, sways, spreads as a ring, and obeys halt and turn. */
final class VeinGameTests {
    private VeinGameTests() {}

    /** A caster standing at relative ({@code x}, 2, {@code z}) on a 9×9 stone floor, facing south (+z). */
    private static ServerPlayer caster(GameTestHelper h, double x, double z) {
        for (int bx = 0; bx < 9; bx++) for (int bz = 0; bz < 9; bz++) h.setBlock(bx, 1, bz, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    /** A vein made by hand from {@code src}, setting out from relative {@code at} towards {@code dir}. */
    private static VeinObj vein(GameTestHelper h, ServerPlayer p, String src, Vec3 at, Vec3 dir, Motion motion, double speed, double range) {
        return vein(h, p, src, h.absoluteVec(at), dir, motion, speed, range, VeinObj.Arc.whole(h.absoluteVec(at)));
    }

    private static VeinObj vein(GameTestHelper h, ServerPlayer p, String src, Vec3 ground, Vec3 dir, Motion motion, double speed, double range,
            VeinObj.Arc arc) {
        var inst = new SpellInstance(Casting.compile(src), p, h.getLevel(), 1000, 1);
        inst.objects = 1;
        inst.hold();
        Ctx c = new Ctx(inst, h.getLevel(), ground, dir, null, Map.of(), 0, 0);
        return new VeinObj(Casting.compile(src).body().getFirst(), c, ground, dir, motion, speed, range, 1, 1, 0, arc);
    }

    /**
     * A sweep keeps to its arc round the centre, half a round sent south: clockwise seen from above it goes from east,
     * through south, to west (from the caster's left to their right), counter-clockwise the other way; then it ends.
     */
    static void sweep(GameTestHelper h) {
        ServerPlayer p = caster(h, 4.5, 4.5);
        Vec3 centre = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        for (boolean ccw : new boolean[]{false, true}) {
            var arc = new VeinObj.Arc(centre, 180, 3, ccw);
            Vec3 south = new Vec3(0, 0, 1);
            var vein = vein(h, p, "(vein)", VeinObj.sweepStart(south, arc), south, Motion.SWEEP, 0.3, 64, arc);
            String way = ccw ? "counter-clockwise" : "clockwise";
            Vec3 first = h.absoluteVec(new Vec3(ccw ? 1.5 : 7.5, 2, 4.5)), last = h.absoluteVec(new Vec3(ccw ? 7.5 : 1.5, 2, 4.5));
            h.assertTrue(vein.position().distanceTo(first) < 1e-6, way + " should start at " + first + ", starts at " + vein.position());
            boolean passedSouth = false;
            for (int k = 0; k < 60 && vein.progress() < 1; k++) {
                vein.tick();
                Vec3 at = vein.position();
                double off = Math.hypot(at.x - centre.x, at.z - centre.z);
                h.assertTrue(Math.abs(off - 3) < 0.3, way + ": it should keep to the arc, 3 from the centre, is " + off);
                h.assertTrue(at.z > centre.z - 0.3, way + ": it should stay on the south half");
                passedSouth |= at.z - centre.z > 2.8;
            }
            h.assertTrue(passedSouth, way + ": it should pass the south of the centre");
            h.assertTrue(vein.progress() == 1, way + ": it should end once round its arc");
            h.assertTrue(vein.position().distanceTo(last) < 0.6, way + " should end at " + last + ", ends at " + vein.position());
        }
        h.succeed();
    }

    /** Part of a ring spreads only over its angle: the zombie it faces is struck, the one behind it is not. */
    static void ringSector(GameTestHelper h) {
        ServerPlayer p = caster(h, 4.5, 4.5);
        Zombie ahead = still(h, 4.5, 8.2);
        Zombie behind = still(h, 4.5, 0.8);
        Casting.cast(p, Casting.compile("(spell :mana 200 (vein :motion ring :spread 90 :range 4.5 (on-hit (mark target #struck))))"), null, null);
        FamiliarGameTests.forceAround(h, 1);
        Status struck = new Status("#struck");
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.stacks(ahead, struck) == 1, "the zombie it faces should be struck");
            h.assertTrue(h.getTick() > 30, "waiting until it has spread out");
            h.assertTrue(StatusData.stacks(behind, struck) == 0, "the zombie behind it is outside its angle");
        });
    }

    private static Zombie still(GameTestHelper h, double x, double z) {
        Zombie zombie = h.spawn(EntityType.ZOMBIE, new Vec3(x, 2, z));
        zombie.setNoAi(true);
        return zombie;
    }

    /**
     * It steps up a block onto a platform, stepping once on every block it crosses (the one it starts on too), and stops
     * short of a wall two high there, running on-impact then on-expire. Every stride lands on a block's edge.
     */
    static void terrain(GameTestHelper h) {
        ServerPlayer p = caster(h, 0.5, 0.5);
        for (int x = 3; x < 9; x++) h.setBlock(x, 2, 4, Blocks.STONE);
        h.setBlock(7, 3, 4, Blocks.STONE);
        h.setBlock(7, 4, 4, Blocks.STONE);
        var vein = vein(h, p, "(vein (on-step (mark caster #step)) (on-impact (mark caster #wall)) (on-expire (mark caster #gone)))",
                // Every stride lands on a block's edge, where a ray down could report either block.
                new Vec3(0.5, 2, 4.5), new Vec3(1, 0, 0), Motion.FORWARD, 0.5, 16);
        for (int k = 0; k < 40 && vein.progress() < 1; k++) vein.tick();
        Vec3 end = vein.position();
        h.assertTrue(Math.abs(end.y - h.absoluteVec(new Vec3(0, 3, 0)).y) < 1e-6, "it should have stepped up onto the platform, ended at " + end);
        double x = end.x - h.absoluteVec(Vec3.ZERO).x;
        h.assertTrue(x > 6.5 && x < 7, "it should stop against the wall at x 7, stopped at x " + x);
        h.assertTrue(StatusData.stacks(p, new Status("#wall")) == 1, "the wall should run on-impact once");
        h.assertTrue(StatusData.stacks(p, new Status("#gone")) == 1, "stopping at the wall should run on-expire");
        double steps = StatusData.stacks(p, new Status("#step"));
        h.assertTrue(steps == 7, "it should step once on each of the seven blocks it crossed, starting block included, stepped " + steps);
        h.succeed();
    }

    /** Cast for real: it finds the ground under the caster's eyes, strikes the first zombie in its way and stops there. */
    static void strikes(GameTestHelper h) {
        ServerPlayer p = caster(h, 4.5, 0.5);
        Zombie first = still(h, 4.5, 4.5);
        Zombie behind = still(h, 4.5, 7.5);
        Casting.cast(p, Casting.compile("(spell :mana 200 (vein (on-hit (mark target #struck))))"), null, null);
        FamiliarGameTests.forceAround(h, 1);
        Status struck = new Status("#struck");
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.stacks(first, struck) == 1, "the first zombie in its way should be struck");
            // Long enough for a vein that had gone on to reach the second.
            h.assertTrue(h.getTick() > 40, "waiting to see that it stopped");
            h.assertTrue(StatusData.stacks(behind, struck) == 0, "without :pierce it should stop at the first");
        });
    }

    /** A ring spreads every way at once and strikes on both sides of its caster. */
    static void ring(GameTestHelper h) {
        ServerPlayer p = caster(h, 4.5, 4.5);
        Zombie west = still(h, 0.8, 4.5);
        Zombie south = still(h, 4.5, 8.2);
        Casting.cast(p, Casting.compile("(spell :mana 200 (vein :motion ring :range 4.5 (on-hit (mark target #struck))))"), null, null);
        FamiliarGameTests.forceAround(h, 1);
        Status struck = new Status("#struck");
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.stacks(west, struck) == 1, "the ring should reach the zombie to the west");
            h.assertTrue(StatusData.stacks(south, struck) == 1, "the ring should reach the zombie to the south");
        });
    }

    /** A snake sways to both sides of its course while it keeps going forward. */
    static void snake(GameTestHelper h) {
        ServerPlayer p = caster(h, 4.5, 0.5);
        var vein = vein(h, p, "(vein)", new Vec3(4.5, 2, 0.5), new Vec3(0, 0, 1), Motion.SNAKE, 0.25, 9);
        double origin = h.absoluteVec(new Vec3(4.5, 0, 0)).x, left = 0, right = 0, z = vein.position().z;
        for (int k = 0; k < 30 && vein.progress() < 1; k++) {
            vein.tick();
            double side = vein.position().x - origin;
            left = Math.min(left, side);
            right = Math.max(right, side);
            h.assertTrue(vein.position().z > z, "it should keep going forward");
            z = vein.position().z;
        }
        h.assertTrue(left < -0.5 && right > 0.5, "it should sway to both sides, swayed " + left + " to " + right);
        h.succeed();
    }

    /** halt holds it where it is; turn sends it along the new course at its own speed. */
    static void formOps(GameTestHelper h) {
        ServerPlayer p = caster(h, 0.5, 0.5);
        var vein = vein(h, p, "(vein)", new Vec3(0.5, 2, 0.5), new Vec3(0, 0, 1), Motion.FORWARD, 0.25, 16);
        for (int k = 0; k < 4; k++) vein.tick();
        Vec3 crawled = vein.position();
        h.assertTrue(crawled.distanceTo(h.absoluteVec(new Vec3(0.5, 2, 1.5))) < 1e-6, "four ticks at 0.25 a tick: a block south, got " + crawled);
        vein.halt();
        for (int k = 0; k < 4; k++) vein.tick();
        h.assertTrue(vein.position().distanceTo(crawled) < 1e-9, "halted, it stays");
        vein.turn(new Vec3(1, 0.5, 0));
        h.assertTrue(vein.facing().distanceTo(new Vec3(1, 0, 0)) < 1e-9, "turned, it should face east along the ground, faces " + vein.facing());
        for (int k = 0; k < 4; k++) vein.tick();
        h.assertTrue(vein.position().distanceTo(crawled.add(1, 0, 0)) < 1e-6, "turned, it crawls east at its own speed, got " + vein.position());
        // Dispersed by its own first step, it crawls no further and runs on-expire once.
        var brief = vein(h, p, "(vein (on-step (disperse form)) (on-expire (mark caster #gone)))", new Vec3(4.5, 2, 4.5), new Vec3(0, 0, 1),
                Motion.FORWARD, 0.5, 16);
        for (int k = 0; k < 4; k++) brief.tick();
        h.assertTrue(brief.progress() == 1 && brief.position().distanceTo(h.absoluteVec(new Vec3(4.5, 2, 4.5))) < 1e-9,
                "dispersed on its first step, it should not crawl on, at " + brief.position());
        h.assertTrue(StatusData.stacks(p, new Status("#gone")) == 1, "its on-expire should run once");
        h.succeed();
    }
}
