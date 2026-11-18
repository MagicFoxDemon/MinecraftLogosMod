package dev.logos.runtime;

import javax.annotation.Nullable;

import dev.logos.runtime.AlchemicalColossus.Act;
import dev.logos.runtime.AlchemicalColossus.Hold;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Who the colossus carries, and where they are: its two shoulder seats and whatever its hand is
 * holding ({@link Hold}). Each is a synced entity id on the colossus, and whoever is held rides it.
 */
final class ColossusHolds {
    private final AlchemicalColossus c;

    ColossusHolds(AlchemicalColossus c) {
        this.c = c;
    }

    @Nullable Hold holdOf(Entity e) {
        int id = e.getId();
        if (c.getEntityData().get(AlchemicalColossus.SEAT_LEFT) == id) return Hold.SEAT_LEFT;
        if (c.getEntityData().get(AlchemicalColossus.SEAT_RIGHT) == id) return Hold.SEAT_RIGHT;
        if (c.getEntityData().get(AlchemicalColossus.GRIP) == id) return Hold.GRIP;
        return null;
    }

    @Nullable Entity held(Hold h) {
        int id = c.getEntityData().get(switch (h) {
            case SEAT_LEFT -> AlchemicalColossus.SEAT_LEFT;
            case SEAT_RIGHT -> AlchemicalColossus.SEAT_RIGHT;
            case GRIP -> AlchemicalColossus.GRIP;
        });
        return id < 0 ? null : c.level().getEntity(id);
    }

    /** Puts {@code e} at {@code h} (riding it), or clears {@code h} when {@code e} is null. */
    void setHold(Hold h, @Nullable Entity e) {
        EntityDataAccessor<Integer> key = switch (h) {
            case SEAT_LEFT -> AlchemicalColossus.SEAT_LEFT;
            case SEAT_RIGHT -> AlchemicalColossus.SEAT_RIGHT;
            case GRIP -> AlchemicalColossus.GRIP;
        };
        // An entity is in one place at a time.
        if (e != null) for (Hold other : Hold.values()) if (other != h && held(other) == e) setHold(other, null);
        c.getEntityData().set(key, e == null ? -1 : e.getId());
        if (e != null && e.getVehicle() != c) {
            e.stopRiding();
            e.startRiding(c, true);
        }
    }

    /** Lets go of {@code e} wherever it is, placing it at {@code at} if given. */
    void release(Entity e, @Nullable Vec3 at) {
        Hold h = holdOf(e);
        if (h != null) c.getEntityData().set(switch (h) {
            case SEAT_LEFT -> AlchemicalColossus.SEAT_LEFT;
            case SEAT_RIGHT -> AlchemicalColossus.SEAT_RIGHT;
            case GRIP -> AlchemicalColossus.GRIP;
        }, -1);
        Colossi.letGo(e);
        if (e.getVehicle() == c) e.stopRiding();
        if (at != null) e.teleportTo(at.x, at.y, at.z);
        Colossi.stopHolding(e);
    }

    /** Throws off everyone it carries or holds. */
    void releaseAll() {
        for (Entity p : c.getPassengers().toArray(Entity[]::new)) release(p, null);
        c.getEntityData().set(AlchemicalColossus.GRIP, -1);
        c.getEntityData().set(AlchemicalColossus.SEAT_LEFT, -1);
        c.getEntityData().set(AlchemicalColossus.SEAT_RIGHT, -1);
    }

    /**
     * Where {@code p} sits or hangs. On the client, the model's own bones (reported by the renderer)
     * give the exact spot; on the server — and before the model has been drawn — a close estimate
     * from the act and its progress.
     */
    Vec3 holdPoint(Hold h, Entity p) {
        if (h == Hold.SEAT_LEFT || h == Hold.SEAT_RIGHT) {
            Vec3 bone = c.drawnBone(h == Hold.SEAT_LEFT ? "attachment_seat_left" : "attachment_seat_right");
            if (bone != null) return bone.subtract(0, 0.6, 0); // a sitting rider's hips are about 0.6 above its feet
            return c.local(h == Hold.SEAT_LEFT ? -0.8 : 0.8, 2.55, -0.05);
        }
        Vec3 l = c.drawnBone("left_hand"), r = c.drawnBone("right_hand");
        Vec3 centre = p.position().add(0, p.getBbHeight() / 2, 0);
        Vec3 hand = l == null ? r : r == null ? l : l.distanceToSqr(centre) < r.distanceToSqr(centre) ? l : r;
        if (hand != null) return hand.subtract(0, p.getBbHeight() * 0.5, 0);
        double t = c.actTicks();
        Vec3 hold = c.local(0.45, 3.0, 1.3);
        Vec3 spot = switch (c.act()) {
            case GRAB_LIFT -> c.local(0.5, 1.2, 1.6).lerp(hold, Mth.clamp(t / 22, 0, 1));
            case GRAB_SLAM -> hold.lerp(c.local(0.45, 0.3, 2.0), Mth.clamp((t - 6) / 12, 0, 1));
            case PICKUP_LEFT, PICKUP_RIGHT -> c.local(0.5, 0.6, 1.4).lerp(c.local(c.act() == Act.PICKUP_LEFT ? -0.8 : 0.8, 2.55, -0.05), Mth.clamp((t - 33) / 46, 0, 1));
            case PUTDOWN_LEFT, PUTDOWN_RIGHT -> c.local(c.act() == Act.PUTDOWN_LEFT ? -0.8 : 0.8, 2.55, -0.05).lerp(c.local(0.5, 0.4, 1.5), Mth.clamp((t - 28) / 52, 0, 1));
            default -> hold;
        };
        return spot.subtract(0, p.getBbHeight() * 0.5, 0);
    }
}
