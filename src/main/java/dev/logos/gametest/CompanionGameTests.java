package dev.logos.gametest;

import java.util.List;
import java.util.function.Function;

import dev.logos.LogosConfig;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.LastingCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Lasting companions from other mods: one of each character per player, several characters together up to the familiar
 * limit, no time limit, kept by their own mod rather than by Logos.
 */
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

    /** Other characters, each its own entity type, as two companions from a mod would be. */
    static final class TestCow extends Cow implements LastingCompanion {
        int sentHome;

        TestCow(Level level) {
            super(EntityType.COW, level);
            setNoAi(true);
        }

        @Override
        public void sendHome() {
            sentHome++;
            discard();
        }
    }

    static final class TestSheep extends Sheep implements LastingCompanion {
        TestSheep(Level level) {
            super(EntityType.SHEEP, level);
            setNoAi(true);
        }

        @Override
        public void sendHome() {
            discard();
        }
    }

    static final class TestChicken extends Chicken implements LastingCompanion {
        TestChicken(Level level) {
            super(EntityType.CHICKEN, level);
            setNoAi(true);
        }

        @Override
        public void sendHome() {
            discard();
        }
    }

    static final class TestRabbit extends Rabbit implements LastingCompanion {
        TestRabbit(Level level) {
            super(EntityType.RABBIT, level);
            setNoAi(true);
        }

        @Override
        public void sendHome() {
            discard();
        }
    }

    private static TestCompanion companion(GameTestHelper h, ServerPlayer p, Vec3 at) {
        return companion(h, p, at, TestCompanion::new);
    }

    private static <T extends Mob> T companion(GameTestHelper h, ServerPlayer p, Vec3 at, Function<Level, T> make) {
        T c = make.apply(h.getLevel());
        Vec3 pos = h.absoluteVec(at);
        c.moveTo(pos.x, pos.y, pos.z, 0, 0);
        Familiars.bindCompanion(c, p);
        h.getLevel().addFreshEntity(c);
        return c;
    }

    /**
     * Bound for good, it is its master's familiar; a second one of the same character sends the first home, and dismiss
     * goes through its mod.
     */
    static void onePerKindNoTimeLimit(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        TestCompanion a = companion(h, p, new Vec3(4.5, 2, 4.5));
        var bond = Familiars.bondOf(a);
        h.assertTrue(bond != null && bond.expiresAt() == Long.MAX_VALUE, "a companion's bond should have no end");
        h.assertTrue(Familiars.isFamiliar(a) && Familiars.companionOf(p) == a, "it should be its master's familiar and companion");
        TestCompanion b = companion(h, p, new Vec3(6.5, 2, 6.5));
        h.assertTrue(a.sentHome == 1 && a.isRemoved(), "binding the same character again should send the first one home");
        h.assertTrue(Familiars.companionOf(p) == b && b.sentHome == 0, "the second one should be the companion now");
        h.assertTrue(Familiars.companionsOf(p).equals(List.of(b)), "only the second one should be out");
        Familiars.dismiss(b);
        h.assertTrue(b.sentHome == 1, "dismissing a companion should send it home through its own mod");
        h.succeed();
    }

    /** Two different characters are out together; the latest bound is the one {@code companionOf} names. */
    static void differentKindsTogether(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        TestCompanion a = companion(h, p, new Vec3(4.5, 2, 4.5));
        TestCow b = companion(h, p, new Vec3(6.5, 2, 6.5), TestCow::new);
        h.assertTrue(a.sentHome == 0 && !a.isRemoved(), "another character should not send the first one home");
        h.assertTrue(Familiars.companionsOf(p).equals(List.of(a, b)), "both should be out, oldest first");
        h.assertTrue(Familiars.companionOf(p) == b, "the latest bound should be the companion named");
        TestCow c = companion(h, p, new Vec3(2.5, 2, 6.5), TestCow::new);
        h.assertTrue(b.sentHome == 1 && a.sentHome == 0, "a second cow should send only the first cow home");
        h.assertTrue(Familiars.companionsOf(p).equals(List.of(a, c)), "the pig and the new cow should be out");
        h.succeed();
    }

    /** At most as many companions as the familiar limit: one more sends home the one out longest. */
    static void capAtFamiliarLimit(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        int room = Familiars.companionRoom();
        h.assertTrue(room == Math.max(1, LogosConfig.maxFamiliars()), "the room should be the familiar limit");
        List<Function<Level, ? extends Mob>> kinds = List.of(TestCompanion::new, TestCow::new, TestSheep::new, TestChicken::new, TestRabbit::new);
        h.assertTrue(room < kinds.size(), "the test needs more characters than the limit (" + room + ")");
        TestCompanion first = companion(h, p, new Vec3(1.5, 2, 1.5));
        for (int i = 1; i < room; i++) companion(h, p, new Vec3(1.5 + i, 2, 1.5), kinds.get(i));
        h.assertTrue(Familiars.companionsOf(p).size() == room && first.sentHome == 0, "the first " + room + " should all be out");
        Mob extra = companion(h, p, new Vec3(1.5, 2, 5.5), kinds.get(room));
        h.assertTrue(first.sentHome == 1 && first.isRemoved(), "one more should send home the one out longest");
        List<Mob> out = Familiars.companionsOf(p);
        h.assertTrue(out.size() == room && out.getLast() == extra && !out.contains(first), "still " + room + " out, the newest among them");
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
        p.moveTo(far.x, far.y, far.z, 0, 0);
        Vec3 start = c.position();
        int born = c.tickCount;
        h.succeedWhen(() -> {
            h.assertTrue(c.tickCount - born > 40, "waiting for the companion to tick");
            h.assertTrue(c.position().distanceTo(start) < 1, "a companion should not be fetched to its master");
            h.assertTrue(c.getTarget() == z, "its own target should not be taken over");
        });
    }
}
