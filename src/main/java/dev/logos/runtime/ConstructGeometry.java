package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.ConstructShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a {@code construct} is solid. The shape is sampled in its own frame and voxelised into
 * axis-aligned cells, so a tilted wall or a dome collides as a fine staircase of boxes (half-block
 * cells; whole blocks when snapped to the grid). Server and client build it from the same synced
 * parameters, so both agree on every collision.
 */
public final class ConstructGeometry {
    public static final int MAX_CELLS = 2048;
    public static final double MAX_SIZE = 16;
    public static final double MAX_RADIUS = 8;

    public final ConstructShape shape;
    public final Vec3 center;
    /** The local frame: {@code forward} is where the shape faces, {@code up} its height. */
    public final Vec3 right, up, forward;
    public final double width, height, depth, radius;
    /** Edge length of one collision cell. */
    public final double cell;
    public final List<AABB> cells;
    public final AABB bounds;

    private ConstructGeometry(ConstructShape shape, Vec3 center, Vec3 right, Vec3 up, Vec3 forward,
            double width, double height, double depth, double radius, double cell) {
        this.shape = shape;
        this.center = center;
        this.right = right;
        this.up = up;
        this.forward = forward;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.radius = radius;
        this.cell = cell;
        this.cells = voxelise();
        AABB b = cells.isEmpty() ? new AABB(center, center).inflate(0.5) : cells.getFirst();
        for (AABB c : cells) b = b.minmax(c);
        this.bounds = b;
    }

    private ConstructGeometry(ConstructGeometry base, Vec3 offset) {
        this.shape = base.shape;
        this.center = base.center.add(offset);
        this.right = base.right;
        this.up = base.up;
        this.forward = base.forward;
        this.width = base.width;
        this.height = base.height;
        this.depth = base.depth;
        this.radius = base.radius;
        this.cell = base.cell;
        this.cells = base.cells.stream().map(c -> c.move(offset)).toList();
        this.bounds = base.bounds.move(offset);
    }

    /** The same shape moved by {@code offset}: cells shift with it rather than being sampled again. */
    public ConstructGeometry translated(Vec3 offset) {
        return offset.lengthSqr() < 1e-12 ? this : new ConstructGeometry(this, offset);
    }

    public static ConstructGeometry of(ConstructShape shape, Vec3 at, Vec3 facing, double width, double height, double depth,
            double radius, boolean snap) {
        width = clamp(width, 0.5, MAX_SIZE);
        height = clamp(height, 0.5, MAX_SIZE);
        depth = clamp(depth, 0.5, MAX_SIZE);
        radius = clamp(radius, 0.5, MAX_RADIUS);
        Vec3 f = facing.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : facing.normalize();
        Vec3 flat = new Vec3(f.x, 0, f.z);
        flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        if (snap) {
            at = Vec3.atCenterOf(BlockPos.containing(at));
            flat = Vec3.atLowerCornerOf(Direction.getApproximateNearest(flat.x, 0, flat.z).getUnitVec3i());
            f = Vec3.atLowerCornerOf(Direction.getApproximateNearest(f.x, f.y, f.z).getUnitVec3i());
        }
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 forward, up;
        switch (shape) {
            case DISK -> {
                forward = f;
                Vec3 u = worldUp.subtract(f.scale(f.dot(worldUp)));
                up = u.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : u.normalize();
            }
            case DOME -> {
                forward = new Vec3(0, 0, 1);
                up = worldUp;
            }
            default -> {
                forward = flat;
                up = worldUp;
            }
        }
        Vec3 right = forward.cross(up).normalize();
        return new ConstructGeometry(shape, at, right, up, forward, width, height, depth, radius, snap ? 1 : 0.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The side that one-way constructs block: in front of a wall or shield, above a platform, outside a dome. */
    public Vec3 normal() {
        return shape == ConstructShape.PLANE ? up : forward;
    }

    /** Whether {@code p} lies on the blocking side (beyond the shape's own thickness). */
    public boolean inFront(Vec3 p) {
        Vec3 d = p.subtract(center);
        if (shape == ConstructShape.DOME) return d.length() > radius;
        double half = shape == ConstructShape.BOX ? depth / 2 : cell / 2;
        return d.dot(normal()) > half;
    }

    /** How much there is of it, for mana: an area, or a volume for a solid box. */
    public double measure() {
        return switch (shape) {
            case BOX -> width * height * depth;
            case WALL -> width * height;
            case PLANE -> width * depth;
            case DISK -> Math.PI * radius * radius;
            case DOME -> 2 * Math.PI * radius * radius;
        };
    }

    public boolean intersects(AABB box) {
        if (!bounds.intersects(box)) return false;
        for (AABB c : cells) if (c.intersects(box)) return true;
        return false;
    }

    /** The first cell face the segment crosses, or null. */
    public @Nullable BlockHitResult clip(Vec3 from, Vec3 to) {
        if (!bounds.inflate(0.01).intersects(new AABB(from, to))) return null;
        return AABB.clip(cells, from, to, BlockPos.ZERO);
    }

    // ---- voxelisation ------------------------------------------------------------------------

    private List<AABB> voxelise() {
        Set<Cell> keys = new LinkedHashSet<>();
        double s = cell / 2;
        switch (shape) {
            case BOX -> sample(keys, width, height, depth, s, (x, y, z) -> true);
            case WALL -> sample(keys, width, height, cell, s, (x, y, z) -> true);
            case PLANE -> sample(keys, width, cell, depth, s, (x, y, z) -> true);
            case DISK -> sample(keys, radius * 2, radius * 2, cell, s, (x, y, z) -> x * x + y * y <= radius * radius);
            case DOME -> {
                double inner = Math.max(0, radius - cell);
                // Only the upper half: shift samples up so the dome stands on its centre.
                sampleUpper(keys, s, inner);
            }
        }
        List<AABB> out = new ArrayList<>(keys.size());
        for (Cell k : keys) {
            out.add(new AABB(k.x * cell, k.y * cell, k.z * cell, (k.x + 1) * cell, (k.y + 1) * cell, (k.z + 1) * cell));
        }
        return out;
    }

    private interface Inside {
        boolean test(double x, double y, double z);
    }

    /** Samples a w × h × d block of the local frame (centred on {@link #center}) every {@code s}. */
    private void sample(Set<Cell> keys, double w, double h, double d, double s, Inside inside) {
        for (double x : steps(w, s)) for (double y : steps(h, s)) for (double z : steps(d, s)) {
            if (keys.size() >= MAX_CELLS) return;
            if (inside.test(x, y, z)) keys.add(key(center.add(right.scale(x)).add(up.scale(y)).add(forward.scale(z))));
        }
    }

    private void sampleUpper(Set<Cell> keys, double s, double inner) {
        for (double x : steps(radius * 2, s)) for (double y : steps(radius, s)) for (double z : steps(radius * 2, s)) {
            if (keys.size() >= MAX_CELLS) return;
            double yy = y + radius / 2;
            double r2 = x * x + yy * yy + z * z;
            if (r2 <= radius * radius && r2 >= inner * inner) keys.add(key(center.add(x, yy, z)));
        }
    }

    private static double[] steps(double extent, double s) {
        int n = Math.max(1, (int) Math.round(extent / s));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = -extent / 2 + extent * (i + 0.5) / n;
        return out;
    }

    private record Cell(int x, int y, int z) {}

    private Cell key(Vec3 p) {
        return new Cell((int) Math.floor(p.x / cell), (int) Math.floor(p.y / cell), (int) Math.floor(p.z / cell));
    }
}
