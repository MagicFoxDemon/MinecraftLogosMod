package dev.logos.runtime;

import dev.logos.lang.Node;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A spell entity that exists in the world (Salt): it has a position, a lifetime and emits events. */
public abstract class SpellObject {
    private static int nextId = 1;

    /** Stable id so clients can keep one persistent effect per spell body. */
    protected final int id = nextId++;
    protected final SpellInstance inst;
    /** The world this body exists in (can differ from the caster's, via in-dimension). */
    protected final ServerLevel level;
    protected final Node node;
    protected final Ctx ctx;
    protected final int life;
    protected Vec3 pos;
    protected int age;
    private boolean ended;

    protected SpellObject(Node node, Ctx ctx, Vec3 pos, int life) {
        this.inst = ctx.inst();
        this.level = ctx.level();
        this.node = node;
        // Its events know it as form (此形).
        this.ctx = ctx.withVar("form", this);
        this.pos = pos;
        this.life = life;
    }

    public abstract void tick();

    /** Called once when this object stops existing, for any reason. */
    protected void onEnded() {}

    protected Vec3 heading() {
        return ctx.aim();
    }

    /** Ends normally, emitting {@code on-expire} at the final position. */
    protected void end() {
        if (ended) return;
        ended = true;
        inst.touch(level, pos);
        onEnded();
        Interp.fire(node, "on-expire", ctx.at(pos).aiming(heading()));
        inst.objects--;
        inst.release();
    }

    /** Ends silently because the whole spell was extinguished. */
    void dissolve() {
        if (ended) return;
        ended = true;
        onEnded();
        inst.objects--;
        inst.release();
    }

    /** Where it is now. */
    public Vec3 pos() {
        return pos;
    }

    public ServerLevel level() {
        return level;
    }

    /** How far through its life it is, 0 at birth to 1 at its end (1 once ended, by whatever means). */
    public double progress() {
        return ended || life <= 0 ? 1 : Math.min(1, (double) age / life);
    }

    boolean isEnded() {
        return ended;
    }
}
