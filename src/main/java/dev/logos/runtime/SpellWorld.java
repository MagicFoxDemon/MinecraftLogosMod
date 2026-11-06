package dev.logos.runtime;

import dev.logos.LogosConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a spell may look at the world without forcing a chunk in. Reading a block in an unloaded chunk
 * loads it on the server thread, and generates it if it is new, so every spell body and block rune asks
 * here first. With {@code spellsLoadChunks} on, everything is within reach, as it always was.
 */
public final class SpellWorld {
    /** How far short of an unloaded chunk a cut-off look stops: well past the rounding {@code Level.clip} adds to its ends. */
    private static final double SHORT_OF = 1e-3;

    private SpellWorld() {}

    /** Whether a spell may read the block at {@code pos}. */
    public static boolean loaded(ServerLevel level, BlockPos pos) {
        return LogosConfig.spellsLoadChunks() || column(level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Whether a spell may read the block at {@code pos}. */
    public static boolean loaded(ServerLevel level, Vec3 pos) {
        return LogosConfig.spellsLoadChunks() || column(level, chunk(pos.x), chunk(pos.z));
    }

    /** Whether a spell may read every block within {@code radius} of {@code pos}, measured across and at any height. */
    public static boolean loaded(ServerLevel level, Vec3 pos, double radius) {
        if (LogosConfig.spellsLoadChunks()) return true;
        int x1 = chunk(pos.x + radius), z0 = chunk(pos.z - radius), z1 = chunk(pos.z + radius);
        for (int x = chunk(pos.x - radius); x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!column(level, x, z)) return false;
            }
        }
        return true;
    }

    /**
     * How far along the line from {@code from} to {@code to} a spell may look: {@code to} itself (this very object) if it
     * may look all the way, otherwise the last point before the first unloaded chunk, which is {@code from} if that is
     * where it starts.
     */
    public static Vec3 reach(ServerLevel level, Vec3 from, Vec3 to) {
        if (LogosConfig.spellsLoadChunks()) return to;
        int x = chunk(from.x), z = chunk(from.z);
        if (!column(level, x, z)) return from;
        int endX = chunk(to.x), endZ = chunk(to.z);
        if (x == endX && z == endZ) return to;
        // Chunk by chunk along the line: t is how far along it, from 0 to 1, each next chunk border lies.
        double dx = to.x - from.x, dz = to.z - from.z;
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double nextX = border(from.x, dx, x, stepX), nextZ = border(from.z, dz, z, stepZ);
        double perX = dx == 0 ? Double.POSITIVE_INFINITY : SectionPos.SECTION_SIZE / Math.abs(dx);
        double perZ = dz == 0 ? Double.POSITIVE_INFINITY : SectionPos.SECTION_SIZE / Math.abs(dz);
        double shortOf = SHORT_OF / from.distanceTo(to);
        while (x != endX || z != endZ) {
            double t;
            if (nextX < nextZ) {
                t = nextX;
                nextX += perX;
                x += stepX;
            } else {
                t = nextZ;
                nextZ += perZ;
                z += stepZ;
            }
            if (!column(level, x, z)) return from.lerp(to, Math.max(0, t - shortOf));
        }
        return to;
    }

    /** {@code level.clip} over as much of the line as {@link #reach} allows: a look cut off reads as meeting nothing. */
    public static BlockHitResult clip(ServerLevel level, Vec3 from, Vec3 to, ClipContext.Block block, ClipContext.Fluid fluid, Entity entity) {
        return level.clip(new ClipContext(from, reach(level, from, to), block, fluid, entity));
    }

    /** How far along a line, 0 to 1, the border of chunk {@code at} lies in the direction of {@code step}. */
    private static double border(double start, double delta, int at, int step) {
        if (delta == 0) return Double.POSITIVE_INFINITY;
        return (SectionPos.sectionToBlockCoord(step > 0 ? at + 1 : at) - start) / delta;
    }

    private static int chunk(double coordinate) {
        return SectionPos.blockToSectionCoord(Mth.floor(coordinate));
    }

    /** {@code getChunkNow} rather than {@code hasChunk}: that is also true of a chunk still on its way, and reading one waits for it. */
    private static boolean column(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }
}
