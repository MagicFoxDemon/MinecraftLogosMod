package dev.logos.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Positions given as forward, up and right of a starting point (the {@code offset} rune, the
 * dividers). Forward is level: the horizontal part of a heading. Up is always up, and right is to
 * the right of forward. A heading straight up or down has no horizontal part; then the caster's
 * yaw says where forward is (south for a spell emitter facing up or down).
 */
public final class Offsets {
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private Offsets() {}

    /** The level forward of {@code heading}, or of {@code yaw} when the heading is vertical. */
    public static Vec3 forward(Vec3 heading, float yaw) {
        Vec3 level = new Vec3(heading.x, 0, heading.z);
        if (level.lengthSqr() < 1e-8) level = Vec3.directionFromRotation(0, yaw);
        return level.normalize();
    }

    /** {@code from} moved {@code f} forward, {@code u} up and {@code r} right. */
    public static Vec3 apply(Vec3 from, Vec3 forward, double f, double u, double r) {
        return from.add(forward.scale(f)).add(0, u, 0).add(forward.cross(UP).scale(r));
    }

    /** Forward of a block facing {@code facing}: itself when level, south when it faces up or down. */
    public static Direction forward(Direction facing) {
        return facing.getAxis().isHorizontal() ? facing : Direction.SOUTH;
    }

    /** How far {@code to} lies from {@code from} as forward, up, right, with {@code forward} level. */
    public static int[] measure(BlockPos from, Direction forward, BlockPos to) {
        BlockPos d = to.subtract(from);
        Direction right = forward.getClockWise();
        return new int[] {
                d.getX() * forward.getStepX() + d.getZ() * forward.getStepZ(),
                d.getY(),
                d.getX() * right.getStepX() + d.getZ() * right.getStepZ()};
    }
}
