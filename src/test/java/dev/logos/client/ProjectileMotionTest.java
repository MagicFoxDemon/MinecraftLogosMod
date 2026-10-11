package dev.logos.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

class ProjectileMotionTest {
    @Test
    void aTickWithoutAReportDoesNotReplayThePreviousSegment() {
        ProjectileMotion motion = new ProjectileMotion(Vec3.ZERO, 0);
        motion.move(new Vec3(1, 0, 0), 1);
        assertEquals(.99, motion.sample(1.99).x, 1e-9);
        assertEquals(1, motion.sample(2).x, 1e-9);
        assertEquals(1, motion.sample(2.5).x, 1e-9);
    }

    @Test
    void retargetingBetweenFramesIsContinuousEvenOnACurve() {
        ProjectileMotion motion = new ProjectileMotion(Vec3.ZERO, 0);
        motion.move(new Vec3(1, 0, 0), 1);
        Vec3 before = motion.sample(1.4);
        motion.move(new Vec3(2, 1, 0), 1.4);
        assertEquals(before, motion.sample(1.4));
        assertEquals(new Vec3(2, 1, 0), motion.sample(2.4));
    }

    @Test
    void regularAndSkippedReportsNeverMoveAStraightShotBackwards() {
        for (int interval : new int[] {1, 2, 3}) {
            ProjectileMotion motion = new ProjectileMotion(Vec3.ZERO, 0);
            double previous = 0;
            for (int frame = 0; frame < 240; frame++) {
                double now = frame / 6.0; // Six rendered frames per game tick.
                if (frame % (interval * 6) == 0) motion.move(new Vec3(now * .5, 0, 0), now);
                double drawn = motion.sample(now).x;
                assertTrue(drawn >= previous - 1e-9, "backwards at " + now);
                if (frame > interval * 12) assertTrue(drawn - previous < .1, "visible step at " + now);
                previous = drawn;
            }
        }
    }
}
