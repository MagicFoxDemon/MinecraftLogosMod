package dev.logos.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.logos.lang.Motion;
import dev.logos.lang.Node;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Salt form "projectile", moved each tick by its Mercury motion rune. */
public final class ProjectileObj extends SpellObject {
    private static final int ORBIT_REHIT_TICKS = 10;

    private final Motion motion;
    private final double speed;
    private final double size;
    private final Vfx.Guise guise;
    private int pierceLeft;
    private Vec3 vel;
    private double orbitAngle;
    private boolean impactEnd;
    private final Map<UUID, Integer> hits = new HashMap<>();

    public ProjectileObj(Node node, Ctx ctx, Vec3 from, Vec3 dir, double speed, int life, Motion motion, int pierce, double size,
            Vfx.Guise guise) {
        super(node, ctx, from, life);
        this.motion = motion;
        this.speed = speed;
        this.size = size;
        this.guise = guise;
        this.pierceLeft = pierce;
        Vec3 d = dir.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : dir.normalize();
        this.vel = d.scale(speed);
        this.orbitAngle = Math.atan2(d.z, d.x);
    }

    @Override
    protected void onEnded() {
        Vfx.trailEnd(level, id, pos, node.element, impactEnd);
    }

    @Override
    protected Vec3 heading() {
        return vel.lengthSqr() < 1e-6 ? ctx.aim() : vel.normalize();
    }

    @Override
    public void tick() {
        if (++age > life) {
            end();
            return;
        }
        Vec3 prev = pos;
        Vec3 next;
        switch (motion) {
            case ARC -> vel = vel.add(0, -0.05, 0);
            case HOMING -> {
                LivingEntity t = age > 2 ? seek() : null;
                if (t != null) vel = steer(vel, Targeting.center(t).subtract(pos), 0.25);
            }
            case WANDER -> {
                var r = level.getRandom();
                vel = steer(vel, vel.add((r.nextDouble() - 0.5) * 1.2, (r.nextDouble() - 0.5) * 0.6, (r.nextDouble() - 0.5) * 1.2), 1);
            }
            case BOOMERANG -> {
                if (age > life / 2) {
                    Vec3 back = inst.caster.getEyePosition().subtract(pos);
                    if (back.length() < 1.2) {
                        end();
                        return;
                    }
                    vel = steer(vel, back, 0.35);
                }
            }
            default -> {}
        }
        if (motion == Motion.ORBIT) {
            orbitAngle += speed * 0.2;
            Vec3 c = inst.caster.position().add(0, 1.1, 0);
            next = c.add(Math.cos(orbitAngle) * 2.5, 0, Math.sin(orbitAngle) * 2.5);
            vel = next.subtract(prev);
        } else {
            next = pos.add(vel);
        }

        Vec3 blockHit = null;
        Vec3 outward = heading().scale(-1);
        net.minecraft.core.BlockPos impactBlock = null;
        if (motion != Motion.ORBIT) {
            BlockHitResult bh = level.clip(new ClipContext(prev, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, inst.caster));
            if (bh.getType() != HitResult.Type.MISS) {
                blockHit = bh.getLocation();
                impactBlock = bh.getBlockPos();
                outward = Vec3.atLowerCornerOf(bh.getDirection().getUnitVec3i());
            }
        }
        // Another spell's construct stops it like a wall of its own.
        Vec3 wall = Constructs.stopSpell(level, inst, prev, blockHit != null ? blockHit : next, ConstructObj.BLOCK_COST);
        if (wall != null) {
            blockHit = wall;
            impactBlock = null;
            outward = heading().scale(-1);
        }
        Vec3 segEnd = blockHit != null ? blockHit : next;

        List<Entity> candidates = level.getEntities(inst.caster, new AABB(prev, segEnd).inflate(size + 1),
                e -> Targeting.isEnemy(inst, e) && canHit(e));
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(prev)));
        for (Entity e : candidates) {
            AABB box = e.getBoundingBox().inflate(size);
            Optional<Vec3> hit = box.contains(prev) ? Optional.of(prev) : box.clip(prev, segEnd);
            if (hit.isEmpty()) continue;
            hits.put(e.getUUID(), age);
            pos = hit.get();
            impactEnd = pierceLeft <= 0;
            Vfx.projectileImpact(level, id, pos, heading().scale(-1), node.element, size);
            Interp.fire(node, "on-hit", ctx.on(e, pos).aiming(heading()));
            if (inst.dead) return;
            if (pierceLeft-- <= 0) {
                end();
                return;
            }
        }
        pos = segEnd;
        Interp.fire(node, "on-tick", ctx.at(pos).aiming(heading()));
        Vfx.trail(level, id, pos, heading(), node.element, motion, size, guise);
        if (impactBlock != null) {
            impactEnd = true;
            Vfx.projectileImpact(level, id, pos, outward, node.element, size);
            Interp.fire(node, "on-impact", ctx.at(Place.center(level, impactBlock)).aiming(heading()));
            end();
        } else if (wall != null) {
            impactEnd = true;
            Vfx.projectileImpact(level, id, pos, outward, node.element, size);
            end();
        }
    }

    private boolean canHit(Entity e) {
        Integer at = hits.get(e.getUUID());
        if (at == null) return true;
        return motion == Motion.ORBIT && age - at >= ORBIT_REHIT_TICKS;
    }

    private LivingEntity seek() {
        return Targeting.enemiesNear(inst, level, pos, 20, false).stream()
                .filter(e -> !hits.containsKey(e.getUUID()))
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElse(null);
    }

    private Vec3 steer(Vec3 v, Vec3 desired, double rate) {
        if (desired.lengthSqr() < 1e-6) return v;
        Vec3 d = v.normalize().lerp(desired.normalize(), rate);
        if (d.lengthSqr() < 1e-6) d = desired.normalize();
        return d.normalize().scale(speed);
    }
}
