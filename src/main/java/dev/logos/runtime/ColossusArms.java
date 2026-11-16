package dev.logos.runtime;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import dev.logos.runtime.AlchemicalColossus.Act;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Where the colossus's arms are guided: its hand onto what it reaches for (to grab it, or to strike
 * it), and its cannon arm onto what it aims at. The server sets the reach and the aim, which are
 * synced; the client eases the arm onto them tick by tick, and the renderer draws that over the
 * animation's own pose.
 */
final class ColossusArms {
    /**
     * Its cannon arm, in its own frame (right, up, forward), as the model draws it while firing: the
     * shoulder it turns at, and the palm with the arm held straight ahead.
     */
    static final Vec3 SHOULDER = new Vec3(-0.76, 2.69, 0.04), PALM = new Vec3(-0.86, 2.76, 1.83);
    /** How far the arm may turn from straight ahead toward its aim, sideways and up or down. */
    static final float AIM_YAW = 30 * Mth.DEG_TO_RAD, AIM_PITCH = 45 * Mth.DEG_TO_RAD;

    private final AlchemicalColossus c;
    /** Client only: how its cannon arm is turned toward its aim (radians), and how far that turn has blended in. */
    private float armYaw, armPitch, armWeight, armYawO, armPitchO, armWeightO;
    /** Client only: what its hand reaches for, and how fully the reach is drawn (rising as it reaches, easing off after). */
    private @Nullable Vec3 reachGoal;
    private float reachWeight, reachWeightO;

    ColossusArms(AlchemicalColossus c) {
        this.c = c;
    }

    // ---- reaching with its hand ---------------------------------------------------------------------

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Its hand reaches for {@code e} — to grab it, or its fist to strike it (null: for nothing). */
    void reachFor(@Nullable Entity e) {
        int id = e == null ? -1 : e.getId();
        if (c.getEntityData().get(AlchemicalColossus.REACH) != id) c.getEntityData().set(AlchemicalColossus.REACH, id);
    }

    /**
     * Where its palm should meet what it reaches for: a blow lands on the middle; a grab closes on the
     * middle of anything a block or more tall, and takes a small creature from above, just under its
     * top. Null when it reaches for nothing.
     */
    @Nullable Vec3 reachPoint() {
        int id = c.getEntityData().get(AlchemicalColossus.REACH);
        Entity e = id < 0 ? null : c.level().getEntity(id);
        if (e == null) return null;
        Vec3 mid = e.getBoundingBox().getCenter();
        return c.act() == Act.PUNCH || e.getBbHeight() >= 1 ? mid : new Vec3(mid.x, e.getBoundingBox().maxY - 0.15, mid.z);
    }

    /** Client: what its hand is reaching for (held a moment after the catch, while the arm eases back), or null. */
    @Nullable Vec3 reachGoal() {
        return reachWeight > 0 ? reachGoal : null;
    }

    /** Client: how fully the reach is drawn over the animation, 0 to 1. */
    float reachWeight(float partialTick) {
        return Mth.lerp(partialTick, reachWeightO, reachWeight);
    }

    /** Client, every tick: the reach is drawn in as its hand goes out, and eases off after. */
    void easeReach() {
        reachWeightO = reachWeight;
        Act a = c.act();
        Vec3 reaching = a == Act.GRAB_REACH || a == Act.PUNCH ? reachPoint() : null;
        if (reaching != null) {
            reachGoal = reaching;
            float progress = (c.tickCount - c.actStartedAt) / (float) a.ticks;
            reachWeight = a == Act.GRAB_REACH
                    // The hand follows the animation down at first, and is drawn onto the point as the reach goes on.
                    ? smooth((progress - 0.15f) / 0.5f)
                    // The wind-up is the animation's own; the thrust is drawn onto the foe, and the recovery is its own again.
                    : Math.min(smooth((progress - 0.33f) / 0.15f), 1 - smooth((progress - 0.6f) / 0.2f));
        } else {
            // Caught (or given up): the arm eases back to the animation over half a second.
            reachWeight = Math.max(0, reachWeight - 0.1f);
        }
    }

    // ---- aiming its cannon arm --------------------------------------------------------------------

    /** Points its cannon arm at {@code p}, or lets it go back to the animation's own pose (null). */
    void aimAt(@Nullable Vec3 p) {
        Vector3f rel = p == null ? new Vector3f() : p.subtract(c.position()).toVector3f();
        if (!rel.equals(c.getEntityData().get(AlchemicalColossus.AIM))) c.getEntityData().set(AlchemicalColossus.AIM, rel);
    }

    /** What its cannon arm points at, or null. */
    @Nullable Vec3 aimPoint() {
        Vector3fc rel = c.getEntityData().get(AlchemicalColossus.AIM);
        return rel.lengthSquared() == 0 ? null : c.position().add(rel.x(), rel.y(), rel.z());
    }

    /** How its cannon arm turns from straight ahead to point its palm at {@code p}: {to its right, upward}, in radians, within its reach. */
    float[] armTurn(Vec3 p) {
        float yaw = c.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 r = new Vec3(-f.z, 0, f.x);
        Vec3 d = p.subtract(c.local(SHOULDER.x, SHOULDER.y, SHOULDER.z));
        double right = d.dot(r), ahead = d.dot(f);
        Vec3 arm = PALM.subtract(SHOULDER);
        double turnYaw = Math.atan2(right, ahead) - Math.atan2(arm.x, arm.z);
        double turnPitch = Math.atan2(d.y, Math.hypot(right, ahead)) - Math.atan2(arm.y, Math.hypot(arm.x, arm.z));
        return new float[] {Mth.clamp((float) Mth.wrapDegrees(turnYaw * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD, -AIM_YAW, AIM_YAW),
                Mth.clamp((float) turnPitch, -AIM_PITCH, AIM_PITCH)};
    }

    /** Where its palm is with its cannon arm turned toward its aim (straight ahead when it has none). */
    Vec3 palm() {
        Vec3 aim = aimPoint();
        return aim == null ? c.local(PALM.x, PALM.y, PALM.z) : palmToward(aim);
    }

    /** Where its palm would be with its cannon arm turned toward {@code p}. */
    Vec3 palmToward(Vec3 p) {
        float[] turn = armTurn(p);
        Vec3 arm = PALM.subtract(SHOULDER);
        double len = arm.length();
        double yaw = Math.atan2(arm.x, arm.z) + turn[0], pitch = Math.atan2(arm.y, Math.hypot(arm.x, arm.z)) + turn[1];
        return c.local(SHOULDER.x + len * Math.cos(pitch) * Math.sin(yaw), SHOULDER.y + len * Math.sin(pitch), SHOULDER.z + len * Math.cos(pitch) * Math.cos(yaw));
    }

    /** Client: the cannon arm's turn to draw this frame, {to its right, upward}, already scaled by how far it has blended in. */
    float[] drawnArmTurn(float partialTick) {
        float w = Mth.lerp(partialTick, armWeightO, armWeight);
        return new float[] {Mth.lerp(partialTick, armYawO, armYaw) * w, Mth.lerp(partialTick, armPitchO, armPitch) * w};
    }

    /** Client, every tick: the arm eases onto its aim as the cannon is raised, follows it smoothly, and eases off after. */
    void easeAim() {
        armYawO = armYaw;
        armPitchO = armPitch;
        armWeightO = armWeight;
        Vec3 aim = aimPoint();
        if (aim != null) {
            float[] turn = armTurn(aim);
            armYaw = Mth.lerp(0.35f, armYaw, turn[0]);
            armPitch = Mth.lerp(0.35f, armPitch, turn[1]);
        }
        armWeight = Mth.approach(armWeight, aim != null ? 1 : 0, 0.1f);
    }
}
