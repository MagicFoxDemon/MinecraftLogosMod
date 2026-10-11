package dev.logos.client;

import net.minecraft.world.phys.Vec3;

/** Retargets from the currently drawn position on a continuous client clock, never a repeating tick fraction. */
final class ProjectileMotion {
    private Vec3 from, target;
    private double started, duration = 1;

    ProjectileMotion(Vec3 position, double now) {
        from = target = position;
        started = now;
    }

    void move(Vec3 position, double now) {
        Vec3 drawn = sample(now);
        duration = Math.clamp(now - started, 1, 3);
        started = now;
        from = drawn;
        target = position;
    }

    Vec3 sample(double now) {
        return from.lerp(target, Math.clamp((now - started) / duration, 0, 1));
    }
}
