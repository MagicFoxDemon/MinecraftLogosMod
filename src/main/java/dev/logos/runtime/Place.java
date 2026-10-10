package dev.logos.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A ◇ position value: coordinates in a particular dimension. Positions carry their world so a
 * spell can reach into another dimension ({@code in-dimension}) and every form or block effect
 * acts in the right one.
 */
public record Place(ServerLevel level, Vec3 pos) {
    public BlockPos block() {
        return BlockPos.containing(pos);
    }

    public Place moved(Vec3 to) {
        return new Place(level, to);
    }

    public static Place center(ServerLevel level, BlockPos pos) {
        return new Place(level, Vec3.atCenterOf(pos));
    }
}
