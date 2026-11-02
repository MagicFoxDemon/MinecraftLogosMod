package dev.logos.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Node;
import dev.logos.lang.Motion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Salt form "vein": a line that crawls over the ground from where it was cast, the way a vine grows or ground fire
 * spreads. It steps up {@link #STEP} and follows the ground down {@link #DROP}, until it has gone its range, struck
 * what it may strike, or run into a wall. A {@link Motion#RING} sets out every way at once (or every way over part of
 * a round), as many heads; a {@link Motion#SWEEP} goes round an arc about a centre. One that {@link #climb}s keeps to
 * whatever surface it is on instead, walls and ceilings too. One that {@link #linger}s leaves what it crawled over
 * behind for a while, striking what touches it as its head would.
 */
public final class VeinObj extends SpellObject {
    /** The highest step it climbs, and how far down it follows the ground, in blocks. */
    public static final double STEP = 1, DROP = 4;
    /** How far below :from it looks for ground to start on. */
    public static final double FIND_GROUND = 6;
    /** The longest stretch it crawls before looking at the ground again. */
    private static final double STRIDE = 0.5;
    private static final double SNAKE_SWAY = 1.5, SNAKE_WAVELENGTH = 6;
    private static final double WANDER_TURN = 0.25, WANDER_LIMIT = Math.PI / 3;
    private static final double SEEK_RADIUS = 20, SEEK_RATE = 0.25;
    /** Above the ground, so a ray along it does not graze the surface it stands on. */
    private static final double LIFT = 0.05;
    /** How far short of a wall it stops, and how far past a stride it looks for one. */
    private static final double SKIN = 0.05;
    /** How far below the surface it has stepped off a climbing head looks back for the face round the edge. */
    private static final double EDGE = 0.1;
    /** The most faces a climbing head turns onto in one stride (into a corner and out of it again, say). */
    private static final int TURNS = 4;
    /** What lingering costs, per block crawled and second lingered, at a width and height of one. */
    public static final double LINGER_COST = 0.02;
    /** The longest it may linger, in seconds. */
    public static final double LINGER_MAX = 30;

    private final Motion motion;
    private final double speed, range, width, height;
    private final Vec3 origin;
    private final List<Head> heads = new ArrayList<>();
    /** Which way it was sent; snake and wander sway and wander about it, and turn and homing change it. */
    private Vec3 course;
    private boolean seeking;
    private boolean halted;
    /** Whether it crawls over every surface (up walls, along ceilings, round edges) instead of keeping to the ground. */
    private boolean climbs;
    /** How long what it crawled over stays and strikes, in ticks; 0 if it goes as it crawls on. */
    private int lingerTicks;
    /** Whether its heads have all stopped, so only what it left behind (if anything) is still there. */
    private boolean settled;
    /** The stretches it crawled over that are still there, laid longest ago first. */
    private final ArrayDeque<Laid> trail = new ArrayDeque<>();

    /** A stretch it crawled over: what touches {@code box} is struck until tick {@code until}, as a head heading {@code dir} on that {@code face}, {@code y} high, would strike it. */
    private record Laid(AABB box, int until, Vec3 dir, Direction face, double y) {}
    private double travelled;
    private final Set<UUID> struck = new HashSet<>();
    /** Whether its hits only heal or ward, so it may land on its own side. */
    private final boolean helps = Targeting.helpsOnHit(node);
    /** What one on-hit deals, so a construct that stops it takes as much (and a void one may pass it). */
    private final double hitDamage = Constructs.hitDamage(node);

    /**
     * How far round a ring or a sweep goes: a ring spreads over {@code spread} degrees about its course (360 is a whole
     * ring); a sweep goes {@code spread} degrees round {@code center} at {@code radius}, clockwise seen from above unless
     * {@code ccw}, its middle on the course. Other motions ignore it.
     */
    public record Arc(Vec3 center, double spread, double radius, boolean ccw) {
        /** A whole ring; no sweep. */
        public static Arc whole(Vec3 at) {
            return new Arc(at, 360, 3, false);
        }

        double radians() {
            return Math.toRadians(spread);
        }

        /** +1 counter-clockwise seen from above, the way {@link Vec3#yRot} turns; -1 clockwise. */
        double sign() {
            return ccw ? 1 : -1;
        }
    }

    private final Arc arc;
    /** Whether a sweep still keeps to its arc; turn and move send it straight on instead. */
    private boolean bending;

    /** A ring or line with a whole round; see {@link #VeinObj(Node, Ctx, Vec3, Vec3, Motion, double, double, double, double, int, Arc)}. */
    public VeinObj(Node node, Ctx ctx, Vec3 ground, Vec3 dir, Motion motion, double speed, double range, double width, double height, int pierce) {
        this(node, ctx, ground, dir, motion, speed, range, width, height, pierce, Arc.whole(ground));
    }

    /**
     * {@code ground} is the top of the surface it starts on (a sweep's, on its arc: see {@link #sweepStart}); {@code dir} is
     * levelled here. A sweep goes as far as its arc is long, whatever {@code range}.
     */
    public VeinObj(Node node, Ctx ctx, Vec3 ground, Vec3 dir, Motion motion, double speed, double range, double width, double height, int pierce,
            Arc arc) {
        super(node, ctx, ground, (int) Math.ceil(length(motion, range, arc) / speed) + 1);
        this.motion = motion;
        this.speed = speed;
        this.range = length(motion, range, arc);
        this.width = width;
        this.height = height;
        this.origin = ground;
        this.course = level(dir);
        this.seeking = motion == Motion.HOMING;
        this.arc = arc;
        this.bending = motion == Motion.SWEEP;
        boolean whole = arc.spread() >= 360 - 1e-6;
        int count = motion == Motion.RING ? ringHeads(range, arc.spread()) : 1;
        for (int k = 0; k < count; k++) {
            // A whole ring spaces its heads evenly round; part of one, from edge to edge.
            double a = count == 1 ? 0 : whole ? Math.PI * 2 * k / count : arc.radians() * ((double) k / (count - 1) - 0.5);
            Head h = new Head(k, ground, course.yRot((float) a), pierce);
            // The block it starts on is stepped on once, on its first tick, however many heads set out from it.
            h.lastStep = under(ground);
            heads.add(h);
        }
    }

    /** How far it crawls in all: a sweep, its arc's length; the rest, {@code range}. */
    private static double length(Motion motion, double range, Arc arc) {
        return motion == Motion.SWEEP ? Math.max(0.5, arc.radius() * arc.radians()) : range;
    }

    /** Where a sweep round {@code arc} sent along {@code dir} sets out: on the arc, at the end it starts from. */
    public static Vec3 sweepStart(Vec3 dir, Arc arc) {
        return arc.center().add(level(dir).yRot((float) (-arc.sign() * arc.radians() / 2)).scale(arc.radius()));
    }

    /** The block standing at {@code at} is on, by position: a ray down a block's very edge may report either neighbour. */
    private static BlockPos under(Vec3 at) {
        return under(at, Direction.UP);
    }

    /** The block whose {@code face} {@code at} lies on. */
    private static BlockPos under(Vec3 at, Direction face) {
        return BlockPos.containing(at.subtract(normal(face).scale(1e-3)));
    }

    private static Vec3 normal(Direction face) {
        return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
    }

    /**
     * From now on it crawls over every surface: up a wall it meets, along a ceiling, round the edge of a roof or a cliff and
     * down the other side, never falling and never stopped by a block. Off the ground it goes straight on; its motion
     * sways, wanders, seeks or bends it again once it is back on a floor.
     */
    public void climb() {
        climbs = true;
    }

    /**
     * What it costs to set out: 4, the motion's surcharge, and 0.05 × width × height × how far it crawls, and for what it
     * leaves behind {@link #LINGER_COST} × width × height × that length × the seconds it lingers. A ring's round is π times
     * as long (less for part of a ring, never under one line); a sweep crawls its arc, radius × angle.
     */
    public static double cost(Motion motion, double range, double width, double height, double spread, double radius, double linger) {
        double length = switch (motion) {
            case RING -> range * Math.max(1, Math.PI * Math.min(spread, 360) / 360);
            case SWEEP -> radius * Math.toRadians(spread);
            default -> range;
        };
        return 4 + motion.mana + 0.05 * width * height * length + LINGER_COST * width * height * length * linger;
    }

    /** The heads a ring sets out with: about one for every two blocks of its widest round, fewer for part of one. */
    public static int ringHeads(double range, double spread) {
        int whole = (int) Math.max(8, Math.min(32, Math.ceil(Math.PI * range)));
        return spread >= 360 - 1e-6 ? whole : (int) Math.max(3, Math.ceil(whole * spread / 360) + 1);
    }

    /** {@code dir} flattened onto the ground, or straight ahead (+z) when it points up or down. */
    public static Vec3 level(Vec3 dir) {
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        return flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** The top of the first surface (a liquid's too) at most {@link #FIND_GROUND} below {@code at}, or null. */
    public static @Nullable Vec3 groundBelow(net.minecraft.server.level.ServerLevel level, Vec3 at, Entity caster) {
        BlockHitResult hit = level.clip(new ClipContext(at, at.add(0, -FIND_GROUND, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, caster));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    public Motion motion() {
        return motion;
    }

    /** Where each head that still crawls is now. */
    public List<Vec3> heads() {
        List<Vec3> out = new ArrayList<>();
        for (Head h : heads) if (h.alive) out.add(h.pos);
        return out;
    }

    /** A ring is where it spreads from; a single line, where its head is. */
    @Override
    public Vec3 position() {
        return pos;
    }

    @Override
    protected Vec3 heading() {
        return motion == Motion.RING ? course : heads.getFirst().dir;
    }

    /** Heads for {@code to} at its own speed (out of a halt as well); a ring has nowhere to head. */
    @Override
    public void moveTo(Vec3 to, double ignored) {
        if (motion != Motion.RING) turn(to.subtract(heads.getFirst().pos));
    }

    /**
     * Crawls {@code dir} from now on, swaying or wandering about it if its motion does; a seeking one stops seeking, and a
     * sweep leaves its arc. Climbing a wall or a ceiling, it goes the way {@code dir} lies along that surface.
     */
    @Override
    public void turn(Vec3 dir) {
        if (motion == Motion.RING || new Vec3(dir.x, 0, dir.z).lengthSqr() < 1e-6) return;
        Head h = heads.getFirst();
        Vec3 along = h.face == Direction.UP ? level(dir) : dir.subtract(normal(h.face).scale(dir.dot(normal(h.face))));
        if (along.lengthSqr() < 1e-6) return;
        course = level(dir);
        seeking = false;
        bending = false;
        halted = false;
        h.dir = along.normalize();
        h.wander = 0;
    }

    /** Stays where it is for the rest of its life, still striking what walks into it. */
    @Override
    public void halt() {
        halted = true;
    }

    /**
     * From now on what it crawls over stays for {@code ticks} after, striking what touches it as its head would (once each,
     * the same as the head), and it lasts until the last of that has gone.
     */
    public void linger(int ticks) {
        lingerTicks = Math.max(0, ticks);
    }

    @Override
    public void tick() {
        if (isEnded()) return;
        if (++age > life + lingerTicks) {
            end();
            return;
        }
        if (age == 1 && !step(under(origin), heading())) return;
        if (!settled && !crawl()) return;
        if (!touch()) return;
        if (settled && trail.isEmpty()) {
            end();
            return;
        }
        show();
    }

    /** One tick of its heads crawling (or, halted, striking where they stand); false if it is gone meanwhile. */
    private boolean crawl() {
        if (seeking && age > 2 && heads.getFirst().face == Direction.UP) seek();
        if (halted) {
            for (Head h : heads) if (h.alive && !strike(h, h.pos, h.pos)) return false;
            settled = heads.stream().noneMatch(h -> h.alive);
            return true;
        }
        for (double left = speed; left > 1e-9; ) {
            double ds = Math.min(STRIDE, left);
            left -= ds;
            for (Head h : heads) {
                if (h.alive && !advance(h, ds)) return false;
            }
            travelled += ds;
            if (heads.stream().noneMatch(h -> h.alive) || travelled >= range) {
                show();
                for (Head h : heads) if (h.alive) stop(h, false);
                settled = true;
                return true;
            }
        }
        return true;
    }

    /**
     * Strikes what touches what it left behind, as its head would, once the stretches laid longest ago have gone; false if
     * it is gone meanwhile.
     */
    private boolean touch() {
        while (!trail.isEmpty() && trail.peekFirst().until() < age) trail.pollFirst();
        if (trail.isEmpty()) return true;
        AABB all = trail.peekFirst().box();
        for (Laid l : trail) all = all.minmax(l.box());
        for (Entity e : level.getEntities(inst.caster, all, e -> !struck.contains(e.getUUID()) && Targeting.mayHit(inst, e, helps))) {
            AABB body = e.getBoundingBox();
            for (Laid l : trail) {
                if (!l.box().intersects(body)) continue;
                struck.add(e.getUUID());
                Vec3 at = l.face() == Direction.UP ? new Vec3(e.getX(), l.y(), e.getZ()) : e.position();
                Interp.fire(node, "on-hit", ctx.on(e, at).aiming(l.dir()));
                if (gone()) return false;
                break;
            }
        }
        return true;
    }

    private void show() {
        for (Head h : heads) if (h.alive) Vfx.vein(level, id, h.index, h.pos, h.dir, h.face, node.element, width, height, lingerTicks);
    }

    /** Crawls {@code h} on by {@code ds}; false if it is gone meanwhile. */
    private boolean advance(Head h, double ds) {
        Vec3 dir = steer(h, ds);
        if (climbs) return climb(h, dir, ds);
        Vec3 from = h.pos;
        // Rise to the highest step it may take (or just under a ceiling), go forward, then fall to the ground.
        Vec3 up = clip(from.add(0, LIFT, 0), from.add(0, STEP + LIFT, 0), ClipContext.Fluid.NONE);
        up = up == null ? from.add(0, STEP + LIFT, 0) : up.add(0, -LIFT, 0);
        Vec3 ahead = up.add(dir.scale(ds));
        // Looking a little past the stride: a ray that ends right on a wall's face does not see the wall.
        BlockHitResult wall = level.clip(new ClipContext(up, ahead.add(dir.scale(SKIN)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, inst.caster));
        boolean blocked = wall.getType() != HitResult.Type.MISS;
        Vec3 reach = blocked ? wall.getLocation().subtract(dir.scale(SKIN)) : ahead;
        // Another spell's construct stops it like a wall of its own.
        Vec3 stopped = Constructs.stopSpell(level, inst, up, reach, ConstructObj.BLOCK_COST, node.element, hitDamage, true);
        if (stopped != null) reach = stopped.subtract(dir.scale(SKIN));
        BlockHitResult ground = level.clip(new ClipContext(reach, reach.add(0, -(STEP + DROP + LIFT), 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, inst.caster));
        if (ground.isInside()) {
            // Still inside a block where it would go on: that block is a wall.
            stop(h, true);
            Interp.fire(node, "on-impact", ctx.at(Place.center(level, ground.getBlockPos())).aiming(dir));
            return !gone();
        }
        if (ground.getType() == HitResult.Type.MISS) {
            // The ground falls away further than it follows: it ends at the edge.
            stop(h, false);
            return true;
        }
        Vec3 next = ground.getLocation();
        if (!strike(h, from, next)) return false;
        if (!h.alive) return true;
        h.pos = next;
        h.dir = dir;
        h.crawled += ds;
        if (motion != Motion.RING) pos = next;
        BlockPos block = under(next);
        if (!block.equals(h.lastStep)) {
            h.lastStep = block;
            if (!step(block, dir)) return false;
        }
        if (stopped != null) {
            stop(h, true);
        } else if (blocked) {
            stop(h, true);
            Interp.fire(node, "on-impact", ctx.at(Place.center(level, wall.getBlockPos())).aiming(dir));
            if (gone()) return false;
        }
        return true;
    }

    /**
     * Crawls climbing {@code h} on by {@code ds} along the face it is on, heading {@code dir}: into a wall, it turns up onto
     * that wall; past the end of its face, it turns round the edge onto the face beyond. False if it is gone meanwhile.
     */
    private boolean climb(Head h, Vec3 dir, double ds) {
        double left = ds;
        for (int turns = 0; left > 1e-9; turns++) {
            if (turns > TURNS) {
                // Wedged somewhere it cannot get out of within a stride.
                stop(h, false);
                return true;
            }
            Vec3 n = normal(h.face), from = h.pos, lifted = from.add(n.scale(LIFT)), ahead = lifted.add(dir.scale(left));
            BlockHitResult wall = level.clip(new ClipContext(lifted, ahead.add(dir.scale(SKIN)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, inst.caster));
            boolean blocked = wall.getType() != HitResult.Type.MISS;
            Vec3 stopped = Constructs.stopSpell(level, inst, lifted, blocked ? wall.getLocation() : ahead, ConstructObj.BLOCK_COST, node.element, hitDamage, true);
            if (stopped != null) {
                Vec3 to = stopped.subtract(dir.scale(SKIN)).subtract(n.scale(LIFT));
                if (!crawl(h, from, to, dir, h.face)) return false;
                if (h.alive) stop(h, true);
                return true;
            }
            Vec3 to;
            Direction next;
            if (blocked) {
                // Into a wall: onto it, the way that leads up from the face it was on.
                to = wall.getLocation();
                next = wall.getDirection();
                left -= lifted.distanceTo(to);
            } else {
                BlockHitResult ground = level.clip(new ClipContext(ahead, ahead.subtract(n.scale(LIFT + EDGE)), ClipContext.Block.COLLIDER,
                        h.face == Direction.UP ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, inst.caster));
                if (ground.getType() != HitResult.Type.MISS && !ground.isInside()) {
                    if (!crawl(h, from, ground.getLocation(), dir, h.face)) return false;
                    break;
                }
                // Past the end of its face: back round the edge onto the face beyond, just below the corner.
                Vec3 below = ahead.subtract(n.scale(LIFT + EDGE));
                BlockHitResult edge = level.clip(new ClipContext(below, below.subtract(dir.scale(left + SKIN)), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, inst.caster));
                if (edge.getType() == HitResult.Type.MISS || edge.isInside() || normal(edge.getDirection()).dot(dir) <= 0) {
                    stop(h, false);
                    return true;
                }
                to = edge.getLocation();
                next = edge.getDirection();
                left = Math.max(0, below.distanceTo(to) - EDGE);
            }
            if (!crawl(h, from, to, dir, next)) return false;
            if (!h.alive) return true;
            dir = round(dir, n, normal(next));
            h.dir = dir;
        }
        if (!h.alive) return true;
        h.dir = dir;
        h.crawled += ds;
        return true;
    }

    /**
     * Moves {@code h} from {@code a} to {@code b} onto that face of the blocks, striking on the way and stepping on a new block
     * it reaches. False if it is gone meanwhile; it may also have stopped on something it struck.
     */
    private boolean crawl(Head h, Vec3 a, Vec3 b, Vec3 dir, Direction onto) {
        if (!strike(h, a, b)) return false;
        if (!h.alive) return true;
        h.pos = b;
        h.face = onto;
        if (motion != Motion.RING) pos = b;
        BlockPos block = under(b, h.face);
        if (block.equals(h.lastStep)) return true;
        h.lastStep = block;
        return step(block, dir);
    }

    /**
     * {@code dir} turned round the edge between a face with normal {@code from} and one with normal {@code to}: what it had
     * across the edge now goes along the new face, what it had along the edge it keeps.
     */
    private static Vec3 round(Vec3 dir, Vec3 from, Vec3 to) {
        Vec3 turned = dir.subtract(to.add(from).scale(dir.dot(to)));
        return turned.lengthSqr() < 1e-9 ? from : turned.normalize();
    }

    /** Runs on-step on {@code block}; false if it is gone meanwhile. */
    private boolean step(BlockPos block, Vec3 dir) {
        Interp.fire(node, "on-step", ctx.at(Place.center(level, block)).aiming(dir));
        return !gone();
    }

    /** Whether its own events ended it ({@code disperse}) or the whole spell was extinguished: then it does nothing more. */
    private boolean gone() {
        return inst.dead || isEnded();
    }

    /** Where a ray from {@code a} to {@code b} first meets a block, or null. */
    private @Nullable Vec3 clip(Vec3 a, Vec3 b, ClipContext.Fluid fluid) {
        BlockHitResult hit = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, fluid, inst.caster));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    /** The way {@code h} goes for its next {@code ds}, by its motion. */
    private Vec3 steer(Head h, double ds) {
        // Up a wall or along a ceiling it goes straight on.
        if (h.face != Direction.UP) return h.dir;
        return switch (motion) {
            case SNAKE -> {
                // Its sideways offset follows sway·sin(k·s): the heading leans by the slope of that wave.
                double k = Math.PI * 2 / SNAKE_WAVELENGTH;
                yield course.yRot((float) -Math.atan(SNAKE_SWAY * k * Math.cos(k * (h.crawled + ds / 2))));
            }
            case WANDER -> {
                h.wander = Math.max(-WANDER_LIMIT, Math.min(WANDER_LIMIT, h.wander + level.getRandom().nextGaussian() * WANDER_TURN));
                yield course.yRot((float) h.wander);
            }
            case SWEEP -> {
                if (!bending) yield h.dir;
                // At where the arc has got to by the end of this stride, so it keeps to the arc over rough ground too.
                double a = arc.sign() * ((h.crawled + ds) / arc.radius() - arc.radians() / 2);
                Vec3 on = arc.center().add(course.yRot((float) a).scale(arc.radius()));
                Vec3 want = new Vec3(on.x - h.pos.x, 0, on.z - h.pos.z);
                yield want.lengthSqr() < 1e-9 ? h.dir : want.normalize();
            }
            // A seeking head is leaned once a tick by seek; the rest keep their heading.
            default -> h.dir;
        };
    }

    /** Leans the seeking head towards the nearest enemy it has not struck yet. */
    private void seek() {
        Head h = heads.getFirst();
        LivingEntity t = Targeting.enemiesNear(inst, level, h.pos, SEEK_RADIUS, false).stream()
                .filter(e -> !struck.contains(e.getUUID()))
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(h.pos)))
                .orElse(null);
        if (t == null) return;
        Vec3 want = new Vec3(t.getX() - h.pos.x, 0, t.getZ() - h.pos.z);
        if (want.lengthSqr() < 1e-6) return;
        Vec3 d = h.dir.lerp(want.normalize(), SEEK_RATE);
        if (d.lengthSqr() < 1e-6) d = want.normalize();
        h.dir = d.normalize();
        course = h.dir;
    }

    /**
     * Strikes whatever stands where {@code h} crawled from {@code a} to {@code b}: as wide as the vein (or, in a ring, as the
     * gap to the next head, so the ring has no holes) and as high off the face it is on. False if it is gone meanwhile.
     */
    private boolean strike(Head h, Vec3 a, Vec3 b) {
        double between = arc.spread() >= 360 - 1e-6 ? Math.PI * 2 / heads.size() : arc.radians() / Math.max(1, heads.size() - 1);
        double gap = motion == Motion.RING ? between * travelled : 0;
        double half = Math.max(width, gap) / 2;
        Vec3 n = normal(h.face);
        AABB box = new AABB(a, b).inflate(n.x == 0 ? half : 0, n.y == 0 ? half : 0, n.z == 0 ? half : 0).expandTowards(n.scale(height));
        if (lingerTicks > 0 && !a.equals(b)) trail.addLast(new Laid(box, age + lingerTicks, h.dir, h.face, b.y));
        List<Entity> found = level.getEntities(inst.caster, box, e -> !struck.contains(e.getUUID()) && Targeting.mayHit(inst, e, helps));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(a)));
        for (Entity e : found) {
            struck.add(e.getUUID());
            Vec3 at = h.face == Direction.UP ? new Vec3(e.getX(), b.y, e.getZ()) : b;
            Interp.fire(node, "on-hit", ctx.on(e, at).aiming(h.dir));
            if (gone()) return false;
            if (h.pierceLeft-- <= 0) {
                h.pos = b;
                if (motion != Motion.RING) pos = b;
                stop(h, true);
                return true;
            }
        }
        return true;
    }

    /** {@code h} crawls no further; {@code hit} if it ended on something rather than at an edge. */
    private void stop(Head h, boolean hit) {
        h.alive = false;
        Vfx.veinEnd(level, id, h.index, h.pos, node.element, hit);
    }

    @Override
    protected void onEnded() {
        for (Head h : heads) if (h.alive) Vfx.veinEnd(level, id, h.index, h.pos, node.element, false);
        // Clients keep showing what it left behind for as long as it lingers; it has gone sooner.
        if (lingerTicks > 0) Vfx.veinFade(level, id, node.element, pos);
    }

    /**
     * {@link #progress} counts how far it has crawled as well as how long it has lasted, whichever is further; once it
     * lingers, how long it has lasted of its crawl and its lingering together.
     */
    @Override
    public double progress() {
        if (isEnded()) return 1;
        if (lingerTicks > 0) return Math.min(1, (double) age / (life + lingerTicks));
        return Math.max(super.progress(), Math.min(1, travelled / range));
    }

    /** One end that crawls: a line has one, a ring many. */
    private static final class Head {
        final int index;
        Vec3 pos;
        Vec3 dir;
        int pierceLeft;
        boolean alive = true;
        /** How far this head has crawled, which the snake's sway follows. */
        double crawled;
        /** A meandering head's angle off its course. */
        double wander;
        /** The face of the blocks it crawls on: the top of the ground unless it climbs. */
        Direction face = Direction.UP;
        @Nullable BlockPos lastStep;

        Head(int index, Vec3 pos, Vec3 dir, int pierce) {
            this.index = index;
            this.pos = pos;
            this.dir = dir;
            this.pierceLeft = pierce;
        }
    }
}
