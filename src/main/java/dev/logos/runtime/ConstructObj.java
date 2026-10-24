package dev.logos.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.logos.lang.Element;
import dev.logos.lang.Node;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Salt form "construct": alchemical matter shaped into a box, wall, platform, shield or dome.
 * It stops what its ⊙ {@code :solid} filter admits — creatures walking into it, projectiles, spell
 * bodies, explosions — and can be stood on, or built against. One-way constructs only stop what
 * comes from the front (from above, for a platform; from outside, for a dome).
 * <p>It holds twice the mana raised into it as health: every hit it stops takes the damage that hit
 * would have dealt, and at zero it shatters. Creatures pushing against it cost nothing. A void body
 * that would deal more than the health left is not stopped at all: it passes straight through.
 */
public final class ConstructObj extends SpellObject {
    /** Mana each time it stops a projectile or a spell body. */
    public static final double BLOCK_COST = 0.5;
    /** Health per mana raised into it. */
    public static final double HEALTH_PER_MANA = 2;
    private static final int BUMP_COOLDOWN = 20;

    private final ConstructEntity entity;
    private final Filter solid;
    private final boolean deflect;
    private final int every;
    private final double upkeep;
    /** What it can still take before it shatters. */
    private double health;
    private final Set<UUID> standing = new HashSet<>();
    private final Map<UUID, Integer> bumped = new HashMap<>();
    /** Creatures inside it now (on-pass fires on the way in), and projectiles that already passed. */
    private final Set<UUID> inside = new HashSet<>();
    private final Set<UUID> passed = new HashSet<>();

    /** Where it is travelling ({@code :to} or {@code move}), and how fast in blocks per tick; null when still. */
    private @org.jspecify.annotations.Nullable Vec3 target;
    private double speed;

    public ConstructObj(Node node, Ctx ctx, ConstructEntity entity, Filter solid, boolean deflect, int life, int every) {
        // Inside its events, {@code self} is the construct itself.
        super(node, ctx.withVar("self", entity), entity.position(), life);
        this.entity = entity;
        this.solid = solid;
        this.deflect = deflect;
        this.every = Math.max(1, every);
        this.upkeep = 0.2 + 0.02 * entity.geometry().measure();
        this.health = HEALTH_PER_MANA * cost(entity.geometry());
        entity.owner = this;
        judgePlayers();
    }

    /** Mana to raise a construct of this size. */
    public static double cost(ConstructGeometry g) {
        return 4 + 0.3 * g.measure();
    }

    boolean admits(Entity e) {
        return solid.test(inst, e);
    }

    public double health() {
        return health;
    }

    /** Takes {@code damage} from something it stopped. At zero it stops nothing more and shatters on its next tick, outside whatever struck it. */
    void absorb(double damage) {
        if (damage > 0) health -= damage;
    }

    boolean broken() {
        return health <= 0 || isEnded();
    }

    @Override
    protected void onEnded() {
        entity.discard();
        Vfx.burst(level, entity.geometry().center, node.element, 30);
        Vfx.sound(level, entity.geometry().center, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.8f, 1.2f);
    }

    /** Its shape where it stands now. */
    public ConstructGeometry geometry() {
        return entity.geometry();
    }

    @Override
    public void tick() {
        if (++age > life || entity.isRemoved() || health <= 0) {
            end();
            return;
        }
        // The spell ticks even where entities do not, so follow from here too (the entity's own tick does it on the client).
        entity.updateFollow();
        travel();
        if (inst.dead || isEnded()) return;
        ConstructGeometry g = entity.geometry();
        if (age % 20 == 0) {
            if (!inst.spend(upkeep, "炼成体维持")) return;
            judgePlayers();
        }
        if (age % every == 0) {
            Interp.fire(node, "on-tick", ctx.at(g.center).aiming(g.normal()));
            if (inst.dead) return;
        }
        touch(g);
    }

    /** Sets it travelling to {@code dest} (its centre), {@code speed} blocks per tick. */
    @Override
    public void moveTo(Vec3 dest, double speed) {
        this.target = dest;
        this.speed = Math.max(0.01, speed);
    }

    @Override
    public void halt() {
        target = null;
    }

    /** Turns a wall or shield (or any shape's front) to face {@code dir}; one held up by its caster keeps following their gaze. */
    @Override
    public void turn(Vec3 dir) {
        entity.face(dir);
    }

    @Override
    public Vec3 facing() {
        return entity.geometry().normal();
    }

    /**
     * One step towards the target. Whatever stands on it rides along: creatures here on the server,
     * players on their own clients (see {@code dev.logos.client.ConstructRiding}), so nobody is left
     * behind or sinks into a rising floor. Arriving fires {@code on-arrive}.
     */
    private void travel() {
        if (target == null) return;
        ConstructGeometry g = entity.geometry();
        Vec3 to = target.subtract(g.center);
        boolean arrives = to.length() <= speed;
        Vec3 step = arrives ? to : to.normalize().scale(speed);
        List<Entity> riders = level.getEntities(entity, g.bounds.inflate(0.5),
                e -> !(e instanceof ConstructEntity) && !(e instanceof Player) && !e.isPassenger() && ConstructEntity.standsOn(e, g));
        entity.setPos(entity.position().add(step));
        for (Entity e : riders) e.setPos(e.position().add(step));
        pos = entity.geometry().center;
        if (arrives) {
            target = null;
            Interp.fire(node, "on-arrive", ctx.at(pos).aiming(entity.geometry().normal()));
        }
    }

    /** Players move on their own clients, which can only be told yes or no for the caster and for everyone else. */
    private void judgePlayers() {
        boolean others = true;
        for (ServerPlayer p : level.players()) {
            if (p != inst.caster && p.distanceToSqr(pos) < 64 * 64) {
                others = solid.test(inst, p);
                break;
            }
        }
        entity.setSolidFor(solid.test(inst, inst.caster), others);
    }

    /** {@code on-stand} for whatever steps onto it, {@code on-block} for what walks into it, {@code on-pass} for what walks through. */
    private void touch(ConstructGeometry g) {
        boolean wantStand = node.events.containsKey("on-stand");
        boolean wantBlock = node.events.containsKey("on-block");
        boolean wantPass = node.events.containsKey("on-pass");
        if (!wantStand && !wantBlock && !wantPass) return;
        Set<UUID> nowStanding = new HashSet<>();
        Set<UUID> nowInside = new HashSet<>();
        for (Entity e : level.getEntities(entity, g.bounds.inflate(0.3), x -> !(x instanceof ConstructEntity) && !(x instanceof Projectile))) {
            AABB box = e.getBoundingBox();
            if (wantPass && g.intersects(box)) {
                nowInside.add(e.getUUID());
                if (inside.add(e.getUUID())) {
                    Interp.fire(node, "on-pass", ctx.on(e, Targeting.center(e)).aiming(heading(e)));
                    if (inst.dead) return;
                }
                continue;
            }
            if (!entity.solidFor(e)) continue;
            AABB feet = new AABB(box.minX, box.minY - 0.08, box.minZ, box.maxX, box.minY, box.maxZ);
            if (g.intersects(feet)) {
                nowStanding.add(e.getUUID());
                if (wantStand && standing.add(e.getUUID())) {
                    Interp.fire(node, "on-stand", ctx.on(e, e.position()).aiming(g.normal()));
                    if (inst.dead) return;
                }
            } else if (wantBlock && g.intersects(box.inflate(0.08))) {
                Integer last = bumped.get(e.getUUID());
                if (last == null || age - last >= BUMP_COOLDOWN) {
                    bumped.put(e.getUUID(), age);
                    Interp.fire(node, "on-block", ctx.on(e, Targeting.center(e)).aiming(g.normal()));
                    if (inst.dead) return;
                }
            }
        }
        standing.retainAll(nowStanding);
        inside.retainAll(nowInside);
    }

    private static Vec3 heading(Entity e) {
        Vec3 v = e.getDeltaMovement();
        return v.lengthSqr() < 1e-6 ? e.getLookAngle() : v.normalize();
    }

    /**
     * A vanilla projectile this construct did not stop flies {@code from → to}: {@code on-pass}
     * the first time it goes through. Copies made while passing count as having passed already,
     * so a duplicating wall cannot feed on its own copies.
     */
    void pass(Projectile p, Vec3 from, Vec3 to) {
        if (isEnded() || !node.events.containsKey("on-pass") || passed.contains(p.getUUID())) return;
        UUID origin = Copies.originOf(p);
        if (origin != null && passed.contains(origin)) {
            passed.add(p.getUUID());
            return;
        }
        ConstructGeometry g = entity.geometry();
        BlockHitResult hit = g.clip(from, to);
        if (hit == null && !g.intersects(p.getBoundingBox())) return;
        passed.add(p.getUUID());
        Interp.fire(node, "on-pass", ctx.on(p, hit != null ? hit.getLocation() : p.position()).aiming(heading(p)));
    }

    /**
     * A vanilla projectile is about to fly from {@code from} to {@code to}: stop it at the first
     * cell it would cross, if this construct stops it. Returns whether it was stopped.
     */
    boolean intercept(Projectile p, Vec3 from, Vec3 to) {
        if (broken() || !admits(p)) return false;
        ConstructGeometry g = entity.geometry();
        if (entity.oneWay() && !g.inFront(from)) return false;
        BlockHitResult hit = g.clip(from, to);
        if (hit == null) return false;
        Vec3 at = hit.getLocation();
        Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
        Vec3 v = p.getDeltaMovement();
        Vec3 reflected = v.subtract(n.scale(2 * v.dot(n)));
        p.setPos(at.add(n.scale(0.05)));
        if (deflect) {
            p.setDeltaMovement(reflected);
            p.hurtMarked = true;
        } else if (p instanceof AbstractArrow) {
            // Glances off and drops, like an arrow hitting glass.
            p.setDeltaMovement(reflected.scale(0.15));
            p.hurtMarked = true;
        } else {
            p.discard();
        }
        Vfx.burst(level, at, node.element, 6);
        Vfx.sound(level, at, SoundEvents.AMETHYST_BLOCK_HIT, 0.8f, 1.4f);
        if (!inst.spend(BLOCK_COST, "炼成体拦截")) return true;
        absorb(Constructs.damageOf(p));
        Interp.fire(node, "on-block", ctx.on(p, at).aiming(n));
        return true;
    }

    /**
     * Where it would stop a body of another spell crossing {@code from → to}, or null. A construct
     * never stops bodies of the spell that raised it, so a caster can shoot out of their own shield;
     * nor a void body dealing more than the health it has left.
     */
    @org.jspecify.annotations.Nullable Vec3 wouldStop(@org.jspecify.annotations.Nullable SpellInstance other, Vec3 from, Vec3 to, Element element, double damage) {
        if (broken() || other == inst) return null;
        if (element == Element.VOID && damage > health) return null;
        ConstructGeometry g = entity.geometry();
        if (entity.oneWay() && !g.inFront(from)) return null;
        BlockHitResult hit = g.clip(from, to);
        return hit == null ? null : hit.getLocation();
    }

    /** Stops a spell body at {@code at}: pays {@code cost} and takes {@code damage}. False if it could not pay. */
    boolean stop(Vec3 at, double cost, double damage) {
        if (!inst.spend(cost, "炼成体拦截")) return false;
        if (cost >= BLOCK_COST) Vfx.burst(level, at, node.element, 6);
        absorb(damage);
        return true;
    }
}
