package dev.logos.gametest;

import java.util.concurrent.atomic.AtomicInteger;

import dev.logos.runtime.CastPose;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Held cast poses: a cast that leads with a beam holds the beam pose on its caster until the beam ends. */
final class CastPoseGameTests {
    private CastPoseGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        return p;
    }

    private static SpellInstance cast(GameTestHelper h, ServerPlayer p, String src) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, null);
        h.assertTrue(inst != null, "cast was refused");
        return inst;
    }

    /**
     * Casts {@code src} and passes once the spell is over, having seen the caster hold the beam pose at
     * most {@code expected} times at once (and exactly that many at some point), and holding none at the end.
     */
    private static void holds(GameTestHelper h, String src, int expected) {
        ServerPlayer p = caster(h);
        SpellInstance inst = cast(h, p, src);
        AtomicInteger most = new AtomicInteger();
        h.succeedWhen(() -> {
            var held = CastPose.holding(p);
            for (var pose : held) h.assertTrue(pose.equals(CastPose.BEAM), "unexpected pose " + pose);
            most.accumulateAndGet(held.size(), Math::max);
            h.assertTrue(most.get() <= expected, "held " + most.get() + " poses at once, expected " + expected);
            h.assertTrue(inst.finished(), "spell still running");
            h.assertTrue(held.isEmpty(), "the pose outlasted the spell: " + held);
            h.assertTrue(most.get() == expected, "held " + most.get() + " poses at most, expected " + expected);
        });
    }

    /** Leading with a beam holds its pose while it lasts and lets go when it ends. */
    static void beamHolds(GameTestHelper h) {
        holds(h, "(spell :mana 100 (beam :radius 0.3 :life 1 :every 0.5))", 1);
    }

    /** split is looked through: each copy of the leading beam holds, and each lets go. */
    static void splitBeamsHold(GameTestHelper h) {
        holds(h, "(spell :mana 100 (split 2 (beam :radius 0.3 :life 1 :every 0.5)))", 2);
    }

    /** A beam that is not the first statement holds nothing. */
    static void laterBeamHoldsNothing(GameTestHelper h) {
        holds(h, "(spell :mana 100 (ray :range 4) (beam :radius 0.3 :life 1 :every 0.5))", 0);
    }
}
