package dev.logos.port;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Stands in for 26.1's {@code Shapes.rotateHorizontal}: a shape drawn facing north, turned about the block's centre to face each way. */
public final class ShapeRotations {
    private ShapeRotations() {}

    public static Map<Direction, VoxelShape> rotateHorizontal(VoxelShape north) {
        Map<Direction, VoxelShape> out = new EnumMap<>(Direction.class);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape turned = Shapes.empty();
            for (AABB b : north.toAabbs()) turned = Shapes.or(turned, Shapes.create(turn(b, d)));
            out.put(d, turned.optimize());
        }
        return out;
    }

    /** {@code b} turned from facing north to facing {@code d}, clockwise seen from above. */
    private static AABB turn(AABB b, Direction d) {
        return switch (d) {
            case EAST -> new AABB(1 - b.maxZ, b.minY, b.minX, 1 - b.minZ, b.maxY, b.maxX);
            case SOUTH -> new AABB(1 - b.maxX, b.minY, 1 - b.maxZ, 1 - b.minX, b.maxY, 1 - b.minZ);
            case WEST -> new AABB(b.minZ, b.minY, 1 - b.maxX, b.maxZ, b.maxY, 1 - b.minX);
            default -> b;
        };
    }
}
