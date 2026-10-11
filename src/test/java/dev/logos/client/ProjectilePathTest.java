package dev.logos.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectilePathTest {
    @Test
    void aWakeNeverRunsAheadOfTheInterpolatedHead() {
        ProjectilePath path = new ProjectilePath(Vec3.ZERO);
        path.move(new Vec3(1, 0, 0));
        path.move(new Vec3(2, 0, 0));
        List<Vec3> samples = path.samples(new Vec3(.5, 0, 0), new Vec3(1, 0, 0), 1.6, 0);
        assertEquals(new Vec3(.5, 0, 0), samples.getFirst());
        assertTrue(samples.stream().allMatch(p -> p.x <= .5));
        assertEquals(-1.1, samples.getLast().x, 1e-9);
    }

    @Test
    void aCurvedWakeFollowsHistoryAndClipsItsLength() {
        ProjectilePath path = new ProjectilePath(Vec3.ZERO);
        path.move(new Vec3(1, 0, 0));
        path.move(new Vec3(1, 1, 0));
        List<Vec3> samples = path.samples(new Vec3(1, 1, 0), new Vec3(0, 1, 0), 1.5, 0);
        assertEquals(List.of(new Vec3(1, 1, 0), new Vec3(1, 0, 0), new Vec3(.5, 0, 0)), samples);
        assertEquals(new Vec3(1, .25, 0), ProjectilePath.point(samples, .5f));
        assertEquals(samples.getLast(), ProjectilePath.point(samples, 1));
    }

    @Test
    void stationaryReportsStillProduceAFiniteWake() {
        ProjectilePath path = new ProjectilePath(Vec3.ZERO);
        for (int i = 0; i < 100; i++) path.move(Vec3.ZERO);
        List<Vec3> samples = path.samples(Vec3.ZERO, new Vec3(0, 0, 1), 1.8, .12);
        assertEquals(List.of(new Vec3(0, 0, .12), new Vec3(0, 0, -1.8)), samples);
        assertEquals(new Vec3(0, 0, -.84), ProjectilePath.point(samples, .5f));
    }
}
