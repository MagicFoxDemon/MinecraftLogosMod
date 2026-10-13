package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Node;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Salt form "beam": a lasting tube of light. Every tick it re-forms from its start along its
 * direction until a block stops it. Unwritten {@code :from}/{@code :dir} mean the caster's eye and
 * gaze <em>now</em>, so the beam swings with the caster; written ones are re-evaluated every tick,
 * so {@code :dir (toward … (pos-of (nearest)))} tracks a moving target.
 */
public final class BeamObj extends SpellObject {
    private final double radius;
    private final double range;
    private final int every;
    private final @Nullable Node fromNode;
    private final @Nullable Node dirNode;
    private final int trail;
    private final Set<UUID> inside = new HashSet<>();
    /** Whether its hits only heal or ward, so it may touch its own side. */
    private final boolean helps = Targeting.helpsOnHit(node);
    /** What one on-hit deals, so a construct that stops it takes as much (and a void one may pass it). */
    private final double hitDamage = Constructs.hitDamage(node);
    private Vec3 dir;

    public BeamObj(Node node, Ctx ctx, double radius, double range, int life, int every, @Nullable Node fromNode, @Nullable Node dirNode, int trail) {
        super(node, ctx, ctx.here(), life);
        this.radius = radius;
        this.range = range;
        this.every = Math.max(1, every);
        this.fromNode = fromNode;
        this.dirNode = dirNode;
        this.trail = trail;
        this.dir = ctx.aim();
    }

    /** Mana per pulse: a wider, longer tube costs more to hold. */
    public static double upkeep(double radius, double range) {
        return 0.3 + 0.05 * radius * range;
    }

    private boolean anchored() {
        return fromNode == null && dirNode == null;
    }

    @Override
    protected Vec3 heading() {
        return dir;
    }

    @Override
    protected void onEnded() {
        Vfx.beamEnd(level, id, pos, node.element);
    }

    @Override
    public void tick() {
        if (++age > life || inst.caster.isRemoved() || (fromNode == null && inst.caster.level() != level)) {
            end();
            return;
        }
        Vec3 from = fromNode == null ? inst.caster.getEyePosition() : Interp.vec(fromNode, ctx);
        Vec3 d = dirNode == null ? inst.caster.getLookAngle() : Interp.vec(dirNode, ctx);
        if (inst.dead) return;
        if (d.lengthSqr() > 1e-6) dir = d.normalize();

        Vec3 end = from.add(dir.scale(range));
        BlockPos blocked = null;
        BlockHitResult bh = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, inst.caster));
        if (bh.getType() != HitResult.Type.MISS) {
            end = bh.getLocation();
            blocked = bh.getBlockPos();
        }
        // Another spell's construct cuts the beam short, like a shield held into it.
        Vec3 wall = Constructs.stopSpell(level, inst, from, end, ConstructObj.BLOCK_COST / 10, node.element, hitDamage, age % every == 0);
        if (wall != null) {
            end = wall;
            blocked = null;
        }
        pos = end;
        Vec3 a = from, b = end;
        List<Entity> touching = new ArrayList<>(level.getEntities(inst.caster, new AABB(a, b).inflate(radius + 1), e -> {
            if (!Targeting.mayHit(inst, e, helps)) return false;
            AABB box = e.getBoundingBox().inflate(radius);
            return box.contains(a) || box.clip(a, b).isPresent();
        }));
        touching.sort(Comparator.comparingDouble(e -> e.distanceToSqr(a)));

        Vfx.beam(level, id, from, end, node.element, radius, anchored() ? inst.caster.getId() : -1, trail);

        Set<UUID> ids = new HashSet<>();
        for (Entity e : touching) {
            ids.add(e.getUUID());
            if (inside.add(e.getUUID())) {
                Interp.fire(node, "on-enter", ctx.on(e, Targeting.center(e)).aiming(dir));
                if (inst.dead) return;
            }
        }
        inside.retainAll(ids);

        if (age % every == 0) {
            if (!inst.spend(upkeep(radius, range), "光柱维持")) return;
            for (Entity e : touching) {
                if (!e.isAlive()) continue;
                Interp.fire(node, "on-hit", ctx.on(e, Targeting.center(e)).aiming(dir));
                if (inst.dead) return;
            }
            Interp.fire(node, "on-tick", ctx.at(end).aiming(dir));
            if (inst.dead) return;
            if (blocked != null) Interp.fire(node, "on-impact", ctx.at(Place.center(level, blocked)).aiming(dir));
        }
    }
}
