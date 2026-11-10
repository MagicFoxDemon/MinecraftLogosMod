package dev.logos.gametest;

import dev.logos.runtime.Familiars;
import dev.logos.runtime.LastingCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Lasting companions from other mods: one per player, no time limit, kept by their own mod rather than by Logos. */
final class CompanionGameTests {
    private CompanionGameTests() {}

    /** A stand-in for another mod's companion: a pig without a mind of its own, that counts being sent home. */
    static final class TestCompanion extends Pig implements LastingCompanion {
        int sentHome;

        TestCompanion(Level level) {
            super(EntityType.PIG, level);
            setNoAi(true);
        }

        @Override
        public void sendHome() {
            sentHome++;
            discard();
        }
    }

    private static TestCompanion companion(GameTestHelper h, ServerPlayer p, Vec3 at) {
        TestCompanion c = new TestCompanion(h.getLevel());
        Vec3 pos = h.absoluteVec(at);
        c.snapTo(pos.x, pos.y, pos.z, 0, 0);
        Familiars.bindCompanion(c, p);
        h.getLevel().addFreshEntity(c);
        return c;
    }

    /** Bound for good, it is its master's familiar; a second companion sends the first home, and dismiss goes through its mod. */
    static void onePerMasterNoTimeLimit(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        TestCompanion a = companion(h, p, new Vec3(4.5, 2, 4.5));
        var bond = Familiars.bondOf(a);
        h.assertTrue(bond != null && bond.expiresAt() == Long.MAX_VALUE, "a companion's bond should have no end");
        h.assertTrue(Familiars.isFamiliar(a) && Familiars.companionOf(p) == a, "it should be its master's familiar and companion");
        TestCompanion b = companion(h, p, new Vec3(6.5, 2, 6.5));
        h.assertTrue(a.sentHome == 1 && a.isRemoved(), "binding a second companion should send the first one home");
        h.assertTrue(Familiars.companionOf(p) == b && b.sentHome == 0, "the second one should be the companion now");
        Familiars.dismiss(b);
        h.assertTrue(b.sentHome == 1, "dismissing a companion should send it home through its own mod");
        h.succeed();
    }

    /** It keeps the target it picked, and is not fetched when its master walks past the leash: its own mind does that. */
    static void keepsItsOwnMind(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        p.setGameMode(GameType.CREATIVE); // the husk leaves a creative master alone
        FamiliarGameTests.forceAround(h, 2);
        TestCompanion c = companion(h, p, new Vec3(4.5, 2, 4.5));
        Zombie z = h.spawn(EntityType.HUSK, new BlockPos(7, 2, 7)); // a husk does not burn at noon
        z.setNoAi(true);
        c.setTarget(z);
        h.assertTrue(c.getTarget() == z, "a companion may pick a target its master is not fighting");
        // Twenty blocks off: an ordinary familiar is fetched past twelve.
        Vec3 far = h.absoluteVec(new Vec3(1.5, 2, 24.5));
        p.snapTo(far.x, far.y, far.z, 0, 0);
        Vec3 start = c.position();
        int born = c.tickCount;
        h.succeedWhen(() -> {
            h.assertTrue(c.tickCount - born > 40, "waiting for the companion to tick");
            h.assertTrue(c.position().distanceTo(start) < 1, "a companion should not be fetched to its master");
            h.assertTrue(c.getTarget() == z, "its own target should not be taken over");
        });
    }
}
