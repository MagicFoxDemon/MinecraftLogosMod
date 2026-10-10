package dev.logos.runtime;

import java.util.Iterator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** How constructs stop the rest of the world: entity collisions, projectiles, explosions and spell bodies. */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Constructs {
    private Constructs() {}

    /** Adds the cells of every construct solid for {@code source} to the game's entity collisions in {@code area}. */
    public static List<VoxelShape> withCollisions(List<VoxelShape> shapes, Level level, @Nullable Entity source, AABB area) {
        if (source == null || !ConstructEntity.any()) return shapes;
        List<ConstructEntity> near = ConstructEntity.near(level, area);
        if (near.isEmpty()) return shapes;
        com.google.common.collect.ImmutableList.Builder<VoxelShape> out = com.google.common.collect.ImmutableList.builder();
        out.addAll(shapes);
        for (ConstructEntity c : near) {
            if (!c.solidFor(source)) continue;
            for (AABB cell : c.geometry().cells) if (cell.intersects(area)) out.add(Shapes.create(cell));
        }
        return out.build();
    }

    /** Whether a construct is right under or around {@code e}, the way a block would be for the server's floating check. */
    public static boolean supports(Entity e) {
        if (!ConstructEntity.any()) return false;
        AABB around = e.getBoundingBox().inflate(0.0625).expandTowards(0, -0.55, 0);
        for (ConstructEntity c : ConstructEntity.near(e.level(), around)) if (c.geometry().intersects(around)) return true;
        return false;
    }

    /** The first point where a construct stops a body of {@code inst} flying {@code from → to}, or null; the construct pays {@code cost}. */
    public static @Nullable Vec3 stopSpell(ServerLevel level, SpellInstance inst, Vec3 from, Vec3 to, double cost) {
        Vec3 best = null;
        for (ConstructEntity c : ConstructEntity.near(level, new AABB(from, to))) {
            if (c.owner == null) continue;
            Vec3 at = c.owner.stopSpell(inst, from, to, cost);
            if (at != null && (best == null || at.distanceToSqr(from) < best.distanceToSqr(from))) best = at;
        }
        return best;
    }

    /** Checks each vanilla projectile's next step before it moves, so even fast arrows cannot skip a thin wall. */
    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Pre event) {
        if (!ConstructEntity.any() || !(event.getEntity() instanceof Projectile p) || p.level().isClientSide()) return;
        Vec3 from = p.position();
        Vec3 to = from.add(p.getDeltaMovement());
        AABB path = new AABB(from, to).inflate(p.getBbWidth());
        List<ConstructEntity> near = ConstructEntity.near(p.level(), path);
        for (ConstructEntity c : near) {
            if (c.owner != null && c.owner.intercept(p, from, to)) return;
        }
        // Not stopped by any: it goes through each of them.
        for (ConstructEntity c : near) {
            if (c.owner != null && !p.isRemoved()) c.owner.pass(p, from, to);
        }
    }

    /** Nothing behind a construct (as seen from the blast) is hurt or broken. */
    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        if (!ConstructEntity.any()) return;
        Vec3 center = event.getExplosion().center();
        List<ConstructEntity> near = ConstructEntity.near(event.getLevel(), new AABB(center, center).inflate(16));
        if (near.isEmpty()) return;
        for (Iterator<BlockPos> it = event.getAffectedBlocks().iterator(); it.hasNext(); ) {
            if (shields(near, center, Vec3.atCenterOf(it.next()))) it.remove();
        }
        for (Iterator<Entity> it = event.getAffectedEntities().iterator(); it.hasNext(); ) {
            if (shields(near, center, it.next().getBoundingBox().getCenter())) it.remove();
        }
    }

    private static boolean shields(List<ConstructEntity> near, Vec3 blast, Vec3 target) {
        for (ConstructEntity c : near) {
            ConstructGeometry g = c.geometry();
            if (c.oneWay() && !g.inFront(blast)) continue;
            BlockHitResult hit = g.clip(blast, target);
            if (hit != null) return true;
        }
        return false;
    }
}
