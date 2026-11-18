package dev.logos.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import dev.logos.port.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.AbstractArrow;
import dev.logos.port.TagValueInput;
import dev.logos.port.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * {@code copy}: duplicates of flying projectiles. Two rules keep it from running away: a copy can
 * never itself be copied (so nothing grows exponentially), and copied arrows or tridents cannot be
 * picked up (so it is not an item duplicator).
 */
public final class Copies {
    /** Copy → the projectile it was made from (server only, short-lived entities). */
    private static final Map<Entity, UUID> ORIGIN = new WeakHashMap<>();

    private Copies() {}

    public static @Nullable UUID originOf(Entity e) {
        return ORIGIN.get(e);
    }

    public static boolean canCopy(Entity e) {
        return e instanceof Projectile && e.isAlive() && !ORIGIN.containsKey(e);
    }

    /** A copy of {@code p} turned {@code yawDegrees} about the vertical axis, already in the world; null if it could not be made. */
    public static @Nullable Entity copy(ServerLevel level, Projectile p, double yawDegrees) {
        net.minecraft.nbt.CompoundTag saved = p.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        Entity c = p.getType().create(level);
        if (c == null) return null;
        c.load(saved);
        c.setUUID(UUID.randomUUID());
        float yaw = (float) Math.toRadians(yawDegrees);
        Vec3 v = p.getDeltaMovement().yRot(yaw);
        c.setDeltaMovement(v);
        c.setYRot(p.getYRot() - (float) yawDegrees);
        if (c instanceof AbstractArrow a) a.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        ORIGIN.put(c, p.getUUID());
        level.addFreshEntity(c);
        return c;
    }
}
