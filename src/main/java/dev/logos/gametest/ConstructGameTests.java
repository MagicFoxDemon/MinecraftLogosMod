package dev.logos.gametest;

import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Constructs in the world. The caster stands at relative (4.5, 2, 1.5) facing +Z; walls are raised
 * across the lane at z = 6.5, so the caster's side is z &lt; 6 and the far side z &gt; 7.
 */
final class ConstructGameTests {
    private static final String WALL = "(construct :shape wall :at here :width 5 :height 3 :life 8";

    private ConstructGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0); // yaw 0 faces +Z
        p.setYHeadRot(0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    /**
     * The test structure is a single block, so only its own chunk is sure to tick entities. Forces the
     * lane's chunks, waits until creatures out there would tick, then runs {@code body}.
     */
    private static void inTickingLane(GameTestHelper h, Runnable body) {
        for (int x = -1; x <= 11; x += 4) for (int z = -1; z <= 19; z += 4) {
            BlockPos b = h.absolutePos(new BlockPos(x, 0, z));
            h.getLevel().setChunkForced(b.getX() >> 4, b.getZ() >> 4, true);
        }
        awaitTicking(h, body, 40);
    }

    private static void awaitTicking(GameTestHelper h, Runnable body, int tries) {
        boolean ready = true;
        for (int x = 0; x <= 8; x += 8) for (int z = 0; z <= 16; z += 8) ready &= h.getLevel().isPositionEntityTicking(h.absolutePos(new BlockPos(x, 2, z)));
        if (ready) body.run();
        else if (tries > 0) h.runAfterDelay(1, () -> awaitTicking(h, body, tries - 1));
        else h.assertTrue(false, "the test lane never became entity-ticking");
    }

    private static SpellInstance castAt(GameTestHelper h, ServerPlayer p, String src, BlockPos at) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, Vec3.atCenterOf(h.absolutePos(at)));
        h.assertTrue(inst != null && !inst.dead, "cast failed: " + src + (inst == null ? "" : " — " + inst.endReason()));
        return inst;
    }

    private static Husk husk(GameTestHelper h, double x, double y, double z) {
        Husk e = h.spawn(EntityType.HUSK, new Vec3(x, y, z));
        e.setNoAi(true);
        return e;
    }

    private static Arrow arrow(GameTestHelper h, double x, double y, double z, double vz) {
        Vec3 at = h.absoluteVec(new Vec3(x, y, z));
        Arrow a = new Arrow(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);
        a.setNoGravity(true);
        a.setDeltaMovement(0, 0, vz);
        h.getLevel().addFreshEntity(a);
        return a;
    }

    private static double relZ(GameTestHelper h, Vec3 abs) {
        return abs.z - h.absoluteVec(Vec3.ZERO).z;
    }

    /** A creature walking into a wall stops at it: the cells join the game's own entity collisions. */
    static void wallStopsMovement(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, WALL + ")", new BlockPos(4, 3, 6));
        Husk husk = husk(h, 4.5, 2, 10.5);
        husk.move(MoverType.SELF, new Vec3(0, 0, -6));
        double z = relZ(h, husk.position());
        h.assertTrue(z >= 7.0 + husk.getBbWidth() / 2 - 0.01, "the husk walked through the wall: z=" + z);
        // Without the wall in the way, the same move goes all the way.
        Husk free = husk(h, 0.5, 2, 10.5);
        free.move(MoverType.SELF, new Vec3(0, 0, -6));
        h.assertTrue(relZ(h, free.position()) < 5, "control husk should pass, z=" + relZ(h, free.position()));
        h.succeed();
    }

    /** A wall stops arrows; a one-way wall only those coming from its front, and on-block sees each one. */
    static void oneWayStopsArrows(GameTestHelper h) {
        ServerPlayer p = caster(h);
        inTickingLane(h, () -> {
            castAt(h, p, WALL + " :one-way (on-block (glow target 5)))", new BlockPos(4, 3, 6));
            Arrow incoming = arrow(h, 3.5, 3.5, 12.5, -1.2); // from the front (far side), toward the caster
            Arrow outgoing = arrow(h, 5.5, 3.5, 2.5, 1.2);   // from behind, the caster's own side
            h.runAfterDelay(15, () -> {
                h.assertTrue(relZ(h, incoming.position()) > 6.9, "the incoming arrow passed: z=" + relZ(h, incoming.position()));
                h.assertTrue(incoming.hasGlowingTag(), "on-block did not fire for the incoming arrow");
                h.assertTrue(relZ(h, outgoing.position()) > 8, "the outgoing arrow should pass a one-way wall from behind: z=" + relZ(h, outgoing.position()));
                h.succeed();
            });
        });
    }

    /** Nothing behind a construct, as seen from the blast, is broken. */
    static void wallShieldsExplosion(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, "(construct :shape wall :at here :width 2 :height 3 :life 8)", new BlockPos(4, 3, 6));
        BlockPos shielded = new BlockPos(4, 2, 5);
        BlockPos exposed = new BlockPos(7, 2, 6);
        h.setBlock(shielded, Blocks.GLASS);
        h.setBlock(exposed, Blocks.GLASS);
        Vec3 blast = h.absoluteVec(new Vec3(4.5, 2.5, 9.5));
        h.getLevel().explode(null, blast.x, blast.y, blast.z, 6, Level.ExplosionInteraction.TNT);
        h.assertTrue(h.getBlockState(shielded).is(Blocks.GLASS), "the glass behind the wall broke");
        h.assertTrue(!h.getBlockState(exposed).is(Blocks.GLASS), "control: the exposed glass should break");
        h.succeed();
    }

    /** A platform holds what falls on it, and on-stand fires once it lands. */
    static void platformHoldsAndStands(GameTestHelper h) {
        ServerPlayer p = caster(h);
        inTickingLane(h, () -> {
            castAt(h, p, "(construct :shape plane :at here :width 3 :depth 3 :life 8 (on-stand (glow target 5)))", new BlockPos(4, 4, 10));
            // With AI on: a no-AI mob does not fall at all.
            Husk husk = h.spawn(EntityType.HUSK, new Vec3(4.5, 7, 10.5));
            h.succeedWhen(() -> {
                double y = husk.getY() - h.absoluteVec(Vec3.ZERO).y;
                h.assertTrue(husk.onGround() && y > 4.9 && y < 5.1, "the husk should rest on the platform top (y=5), y=" + y);
                h.assertTrue(husk.hasEffect(MobEffects.GLOWING), "on-stand did not fire");
            });
        });
    }

    /**
     * An arrow passing through a wall that stops nothing is set alight and copied twice; the copies,
     * born inside the wall, are not copied again (three arrows, not an avalanche) and cannot be picked up.
     */
    static void passIgnitesAndCopies(GameTestHelper h) {
        ServerPlayer p = caster(h);
        inTickingLane(h, () -> {
            castAt(h, p, "(construct :shape wall :at here :width 5 :height 3 :life 8 :solid (kind none)"
                    + " (on-pass (if (is target (kind projectiles)) (do (ignite target 5) (copy target 2 :spread 10)))))", new BlockPos(4, 3, 6));
            Arrow a = arrow(h, 4.5, 3.5, 2.5, 1.2);
            h.runAfterDelay(12, () -> {
                h.assertTrue(a.isOnFire(), "the arrow should be burning after passing");
                var arrows = h.getLevel().getEntitiesOfClass(Arrow.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(30));
                h.assertTrue(arrows.size() == 3, "expected the arrow and exactly two copies, got " + arrows.size());
                h.assertTrue(arrows.stream().filter(x -> x != a).allMatch(x -> x.pickup == net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.CREATIVE_ONLY),
                        "copies must not be pickable");
                h.assertTrue(arrows.stream().allMatch(x -> relZ(h, x.position()) > 8), "all three should fly on through the wall");
                h.succeed();
            });
        });
    }

    /** on-stand → (move self …): the platform rises and carries the husk standing on it; on-arrive fires at the top. */
    static void platformCarriesRider(GameTestHelper h) {
        ServerPlayer p = caster(h);
        inTickingLane(h, () -> {
            castAt(h, p, "(construct :shape plane :at here :width 3 :depth 3 :life 9"
                    + " (on-stand (move self (above (pos-of self) 3) :speed 0.15))"
                    + " (on-arrive (glow (nearest :radius 3) 6)))", new BlockPos(4, 4, 10));
            Husk husk = h.spawn(EntityType.HUSK, new Vec3(4.5, 5.2, 10.5));
            h.succeedWhen(() -> {
                double y = husk.getY() - h.absoluteVec(Vec3.ZERO).y;
                h.assertTrue(y > 7.8 && y < 8.2, "the husk should have ridden up to y=8, y=" + y);
                h.assertTrue(husk.hasEffect(MobEffects.GLOWING), "on-arrive did not fire");
            });
        });
    }

    /** A :follow wall turns with the caster's gaze, server side, the same way the caster's client computes it. */
    static void followWallTurns(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, "(construct :shape wall :at (ahead 3) :follow :life 8)", new BlockPos(4, 3, 1));
        var walls = dev.logos.runtime.ConstructEntity.near(h.getLevel(), p.getBoundingBox().inflate(8));
        h.assertTrue(walls.size() == 1, "expected one construct, got " + walls.size());
        var wall = walls.getFirst();
        Vec3 before = wall.geometry().center.subtract(p.getEyePosition());
        h.assertTrue(before.z > 2.5, "the wall should start in front (+z): " + before);
        p.setYRot(-90); // face +X
        p.setYHeadRot(-90);
        h.runAfterDelay(2, () -> {
            Vec3 after = wall.geometry().center.subtract(p.getEyePosition());
            h.assertTrue(after.x > 2.5 && Math.abs(after.z) < 0.5, "the wall should now be at +x: " + after);
            h.assertTrue(Math.abs(wall.geometry().forward.x) > 0.99, "and face +x: " + wall.geometry().forward);
            h.succeed();
        });
    }

    /** A creature walking into a fire wall (stops nobody) catches fire. */
    static void fireWallIgnitesWalkers(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, "(construct :shape wall :at here :width 5 :height 3 :life 8 :solid (kind none) (on-pass (ignite target 4)))", new BlockPos(4, 3, 6));
        Husk husk = husk(h, 4.5, 2, 9.5);
        husk.move(MoverType.SELF, new Vec3(0, 0, -3)); // into the wall: nothing stops it
        h.succeedWhen(() -> h.assertTrue(husk.isOnFire(), "the husk inside the fire wall is not burning"));
    }

    private static dev.logos.runtime.ConstructObj wallAt(GameTestHelper h) {
        var walls = dev.logos.runtime.ConstructEntity.near(h.getLevel(), new net.minecraft.world.phys.AABB(h.absolutePos(new BlockPos(4, 3, 6))).inflate(3));
        h.assertTrue(walls.size() == 1 && walls.getFirst().owner() != null, "expected one construct, got " + walls.size());
        return walls.getFirst().owner();
    }

    private static void shoot(GameTestHelper h, ServerPlayer p, String onHit) {
        SpellInstance shot = Casting.cast(p, Casting.compile("(projectile :speed 1 (on-hit " + onHit + "))"), null, null);
        h.assertTrue(shot != null && !shot.dead, "the shot was refused");
    }

    /**
     * A wall holds twice its raising mana (5×3: 4 + 0.3×15 = 8.5 → 17) and loses what each stopped shot
     * would have dealt: 10 leaves 7, the next 10 is still stopped but shatters it, and the third gets through.
     */
    static void wallWearsDownAndBreaks(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, WALL + ")", new BlockPos(4, 3, 6));
        var wall = wallAt(h);
        h.assertTrue(Math.abs(wall.health() - 17) < 1e-6, "a 5×3 wall should hold 17, holds " + wall.health());
        Husk husk = husk(h, 4.5, 2, 10.5);
        shoot(h, p, "(damage target 10)");
        h.runAfterDelay(15, () -> {
            h.assertTrue(Math.abs(wall.health() - 7) < 1e-6, "the first shot should leave 7, left " + wall.health());
            h.assertTrue(husk.getHealth() == husk.getMaxHealth(), "the first shot went through the wall");
            shoot(h, p, "(damage target 10)");
            h.runAfterDelay(15, () -> {
                h.assertTrue(husk.getHealth() == husk.getMaxHealth(), "the shot that breaks the wall is still stopped");
                h.assertTrue(wall.health() <= 0, "the wall should have shattered, holds " + wall.health());
                shoot(h, p, "(damage target 3)");
                h.succeedWhen(() -> h.assertTrue(husk.getHealth() < husk.getMaxHealth(), "with the wall gone, the shot should hit"));
            });
        });
    }

    /** A void shot weaker than the wall is stopped and wears it; one stronger than what is left passes straight through, untaken. */
    static void voidPiercesWeakWall(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, WALL + ")", new BlockPos(4, 3, 6));
        var wall = wallAt(h);
        Husk husk = husk(h, 4.5, 2, 10.5);
        shoot(h, p, "(damage target 5 void)");
        h.runAfterDelay(15, () -> {
            h.assertTrue(Math.abs(wall.health() - 12) < 1e-6, "the weak void shot should be stopped and leave 12, left " + wall.health());
            h.assertTrue(husk.getHealth() == husk.getMaxHealth(), "the weak void shot went through");
            shoot(h, p, "(damage target 13 void)");
            h.succeedWhen(() -> {
                h.assertTrue(husk.getHealth() < husk.getMaxHealth(), "the strong void shot should pass the wall and hit");
                h.assertTrue(Math.abs(wall.health() - 12) < 1e-6, "passing through takes nothing from the wall, left " + wall.health());
            });
        });
    }

    /** Another spell's projectile dies at the wall; the husk behind it is untouched. */
    static void wallStopsOtherSpells(GameTestHelper h) {
        ServerPlayer p = caster(h);
        castAt(h, p, WALL + ")", new BlockPos(4, 3, 6));
        Husk husk = husk(h, 4.5, 2, 10.5);
        SpellInstance shot = Casting.cast(p, Casting.compile("(projectile :speed 1 (on-hit (damage target 5)))"), null, null);
        h.assertTrue(shot != null, "the shot was refused");
        h.runAfterDelay(30, () -> {
            h.assertTrue(husk.getHealth() == husk.getMaxHealth(), "the projectile went through the wall");
            h.succeed();
        });
    }
}
