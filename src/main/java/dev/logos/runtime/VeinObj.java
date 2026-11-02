package dev.logos.runtime;

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
 * a round), as many heads; a {@link Motion#SWEEP} goes round an arc about a centre.
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

    private final Motion motion;
    private final double speed, range, width, height;
    private final Vec3 origin;
    private final List<Head> heads = new ArrayList<>();
    /** Which way it was sent; snake and wander sway and wander about it, and turn and homing change it. */
    private Vec3 course;
    private boolean seeking;
    private boolean halted;
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
        return BlockPos.containing(at.x, at.y - 1e-3, at.z);
    }

    /**
     * What it costs to set out: 4, the motion's surcharge, and 0.05 × width × height × how far it crawls. A ring's round is
     * π times as long (less for part of a ring, never under one line); a sweep crawls its arc, radius × angle.
     */
    public static double cost(Motion motion, double range, double width, double height, double spread, double radius) {
        double length = switch (motion) {
            case RING -> range * Math.max(1, Math.PI * Math.min(spread, 360) / 360);
            case SWEEP -> radius * Math.toRadians(spread);
            default -> range;
        };
        return 4 + motion.mana + 0.05 * width * height * length;
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
     * sweep leaves its arc.
     */
    @Override
    public void turn(Vec3 dir) {
        if (motion == Motion.RING || new Vec3(dir.x, 0, dir.z).lengthSqr() < 1e-6) return;
        course = level(dir);
        seeking = false;
        bending = false;
        halted = false;
        Head h = heads.getFirst();
        h.dir = course;
        h.wander = 0;
    }

    /** Stays where it is for the rest of its life, still striking what walks into it. */
    @Override
    public void halt() {
        halted = true;
    }

    @Override
    public void tick() {
        if (isEnded()) return;
        if (++age > life) {
            end();
            return;
        }
        if (age == 1 && !step(under(origin), heading())) return;
        if (seeking && age > 2) seek();
        if (halted) {
            for (Head h : heads) if (h.alive && !strike(h, h.pos, h.pos)) return;
            if (heads.stream().noneMatch(h -> h.alive)) {
                end();
                return;
            }
        } else {
            for (double left = speed; left > 1e-9; ) {
                double ds = Math.min(STRIDE, left);
                left -= ds;
                for (Head h : heads) {
                    if (h.alive && !advance(h, ds)) return;
                }
                travelled += ds;
                if (heads.stream().noneMatch(h -> h.alive) || travelled >= range) {
                    show();
                    end();
                    return;
                }
            }
        }
        show();
    }

    private void show() {
        for (Head h : heads) if (h.alive) Vfx.vein(level, id, h.index, h.pos, h.dir, node.element, width, height);
    }

    /** Crawls {@code h} on by {@code ds}; false if it is gone meanwhile. */
    private boolean advance(Head h, double ds) {
        Vec3 dir = steer(h, ds);
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
     * gap to the next head, so the ring has no holes) and as high. False if it is gone meanwhile.
     */
    private boolean strike(Head h, Vec3 a, Vec3 b) {
        double between = arc.spread() >= 360 - 1e-6 ? Math.PI * 2 / heads.size() : arc.radians() / Math.max(1, heads.size() - 1);
        double gap = motion == Motion.RING ? between * travelled : 0;
        double half = Math.max(width, gap) / 2;
        AABB box = new AABB(Math.min(a.x, b.x) - half, Math.min(a.y, b.y), Math.min(a.z, b.z) - half,
                Math.max(a.x, b.x) + half, Math.max(a.y, b.y) + height, Math.max(a.z, b.z) + half);
        List<Entity> found = level.getEntities(inst.caster, box, e -> !struck.contains(e.getUUID()) && Targeting.mayHit(inst, e, helps));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(a)));
        for (Entity e : found) {
            struck.add(e.getUUID());
            Vec3 at = new Vec3(e.getX(), b.y, e.getZ());
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
    }

    /** {@link #progress} counts how far it has crawled as well as how long it has lasted, whichever is further. */
    @Override
    public double progress() {
        return Math.max(super.progress(), isEnded() ? 1 : Math.min(1, travelled / range));
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
        @Nullable BlockPos lastStep;

        Head(int index, Vec3 pos, Vec3 dir, int pierce) {
            this.index = index;
            this.pos = pos;
            this.dir = dir;
            this.pierceLeft = pierce;
        }
    }
}
