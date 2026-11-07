package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * One level's constructs, filed under the chunk columns their bounds touch, so that whatever moves nowhere
 * near one looks at none of them. It lives on its level as an attachment and is never saved, as constructs are
 * not. {@link ConstructEntity} keeps its entry up to date whenever its bounds change.
 */
public final class ConstructIndex {
    private static final Comparator<Entry> OLDEST_FIRST = Comparator.comparingLong(e -> e.order);

    private static long looked;

    private final List<Entry> all = new ArrayList<>();
    private final Long2ObjectMap<List<Entry>> columns = new Long2ObjectOpenHashMap<>();
    private long added;

    /** How many constructs queries have looked at, in any level: how the tests see that a query far from all of them touches none. */
    public static long looked() {
        return looked;
    }

    private static int column(double coord) {
        return SectionPos.posToSectionCoord(coord);
    }

    /** Where one construct is filed, for as long as it is in the level. */
    final class Entry {
        final ConstructEntity construct;
        /** When it came: queries answer oldest first, because the first construct to stop a projectile takes it. */
        final long order = added++;
        AABB bounds;
        int minX, minZ, maxX, maxZ;

        private Entry(ConstructEntity construct, AABB bounds) {
            this.construct = construct;
            all.add(this);
            file(bounds);
        }

        private void file(AABB bounds) {
            this.bounds = bounds;
            minX = column(bounds.minX);
            minZ = column(bounds.minZ);
            maxX = column(bounds.maxX);
            maxZ = column(bounds.maxZ);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                long key = ChunkPos.pack(x, z);
                List<Entry> column = columns.get(key);
                if (column == null) columns.put(key, column = new ArrayList<>(2));
                column.add(this);
            }
        }

        private void unfile() {
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                long key = ChunkPos.pack(x, z);
                List<Entry> column = columns.get(key);
                column.remove(this);
                if (column.isEmpty()) columns.remove(key);
            }
        }

        /** Its bounds changed: it moved, turned or was resized. */
        void move(AABB to) {
            if (column(to.minX) == minX && column(to.minZ) == minZ && column(to.maxX) == maxX && column(to.maxZ) == maxZ) {
                bounds = to;
            } else {
                unfile();
                file(to);
            }
        }

        void remove() {
            all.remove(this);
            unfile();
        }

        private boolean touches(AABB area) {
            looked++;
            return !construct.isRemoved() && bounds.intersects(area);
        }
    }

    Entry add(ConstructEntity construct, AABB bounds) {
        return new Entry(construct, bounds);
    }

    /** The constructs whose bounds touch {@code area}, oldest first. */
    List<ConstructEntity> near(AABB area) {
        int minX = column(area.minX), minZ = column(area.minZ), maxX = column(area.maxX), maxZ = column(area.maxZ);
        List<Entry> hits = new ArrayList<>(0);
        // With few constructs or a wide area, looking at each costs no more than looking up the columns.
        if ((maxX - (long) minX + 1) * (maxZ - (long) minZ + 1) >= all.size()) {
            for (Entry e : all) if (e.touches(area)) hits.add(e);
        } else {
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                List<Entry> column = columns.get(ChunkPos.pack(x, z));
                if (column == null) continue;
                for (Entry e : column) {
                    // One filed under several of these columns is looked at in the first of them only.
                    if (x == Math.max(minX, e.minX) && z == Math.max(minZ, e.minZ) && e.touches(area)) hits.add(e);
                }
            }
        }
        if (hits.isEmpty()) return List.of();
        if (hits.size() > 1) hits.sort(OLDEST_FIRST);
        List<ConstructEntity> out = new ArrayList<>(hits.size());
        for (Entry e : hits) out.add(e.construct);
        return out;
    }
}
