package dev.logos.runtime;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Category;
import dev.logos.lang.Element;
import dev.logos.lang.Node;
import dev.logos.mixin.AbstractArrowAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
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

    /**
     * The first point where a construct stops a body of {@code inst} flying {@code from → to}, or null.
     * Only that construct pays {@code cost}, and takes {@code damage} when {@code strikes} (a beam holds
     * against it every tick but strikes once a pulse). A void body stronger than a construct's health
     * passes it by, so {@code damage} is also what decides that, struck or not.
     */
    public static @Nullable Vec3 stopSpell(ServerLevel level, @Nullable SpellInstance inst, Vec3 from, Vec3 to, double cost,
                                           Element element, double damage, boolean strikes) {
        ConstructObj best = null;
        Vec3 bestAt = null;
        for (ConstructEntity c : ConstructEntity.near(level, new AABB(from, to))) {
            if (c.owner == null) continue;
            Vec3 at = c.owner.wouldStop(inst, from, to, element, damage);
            if (at != null && (bestAt == null || at.distanceToSqr(from) < bestAt.distanceToSqr(from))) {
                best = c.owner;
                bestAt = at;
            }
        }
        return best != null && best.stop(bestAt, cost, strikes ? damage : 0) ? bestAt : null;
    }

    /** The damage a vanilla projectile would deal on hitting: an arrow's speed times its base damage, a blaze's fireball 5; 0 for the rest. */
    static double damageOf(Projectile p) {
        if (p instanceof AbstractArrow a) return Math.ceil(a.getDeltaMovement().length() * ((AbstractArrowAccessor) a).logos$baseDamage());
        if (p instanceof SmallFireball) return 5;
        return 0;
    }

    /**
     * The damage a spell body's {@code on-hit} deals to one target: the written amounts of its
     * damage, drain and damage-all, not counting forms it launches in turn. Computed amounts are
     * not known before the hit and count as 0.
     */
    public static double hitDamage(Node form) {
        double sum = 0;
        for (Node n : form.events.getOrDefault("on-hit", List.of())) sum += writtenDamage(n);
        return sum;
    }

    private static double writtenDamage(Node n) {
        if (n == null || n.kind != Node.Kind.CALL || n.rune == null || n.rune.category == Category.FORM) return 0;
        double sum = switch (n.rune.name) {
            case "damage", "drain", "damage-all" -> n.arg(1) != null && n.arg(1).value instanceof Number v ? Math.max(0, v.doubleValue()) : 0;
            default -> 0;
        };
        for (Node a : n.args) sum += writtenDamage(a);
        for (Node k : n.kw.values()) sum += writtenDamage(k);
        for (Node b : n.body) sum += writtenDamage(b);
        return sum;
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

    /**
     * Nothing behind a construct (as seen from the blast) is hurt or broken. Each construct that shields
     * something takes the blast as a creature would at its nearest shielding point (vanilla's formula, in full view).
     */
    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        if (!ConstructEntity.any()) return;
        Vec3 center = event.getExplosion().center();
        List<ConstructEntity> near = ConstructEntity.near(event.getLevel(), new AABB(center, center).inflate(16));
        if (near.isEmpty()) return;
        Map<ConstructEntity, Double> struck = new HashMap<>();
        for (Iterator<BlockPos> it = event.getAffectedBlocks().iterator(); it.hasNext(); ) {
            if (shields(near, center, Vec3.atCenterOf(it.next()), struck)) it.remove();
        }
        for (Iterator<Entity> it = event.getAffectedEntities().iterator(); it.hasNext(); ) {
            if (shields(near, center, it.next().getBoundingBox().getCenter(), struck)) it.remove();
        }
        double diameter = event.getExplosion().radius() * 2;
        struck.forEach((c, dist) -> {
            double pow = Math.max(0, 1 - dist / diameter);
            if (c.owner != null && pow > 0) c.owner.absorb((pow * pow + pow) / 2 * 7 * diameter + 1);
        });
    }

    /** Whether the first construct between {@code blast} and {@code target} shields it; notes that construct's nearest shielding point. */
    private static boolean shields(List<ConstructEntity> near, Vec3 blast, Vec3 target, Map<ConstructEntity, Double> struck) {
        ConstructEntity first = null;
        double nearest = Double.MAX_VALUE;
        for (ConstructEntity c : near) {
            ConstructGeometry g = c.geometry();
            if (c.owner != null && c.owner.broken() || c.oneWay() && !g.inFront(blast)) continue;
            BlockHitResult hit = g.clip(blast, target);
            if (hit != null && hit.getLocation().distanceTo(blast) < nearest) {
                first = c;
                nearest = hit.getLocation().distanceTo(blast);
            }
        }
        if (first == null) return false;
        struck.merge(first, nearest, Math::min);
        return true;
    }
}
