package dev.logos.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
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
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        p.saveWithoutId(out);
        Entity c = p.getType().create(level, EntitySpawnReason.TRIGGERED);
        if (c == null) return null;
        c.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult()));
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
