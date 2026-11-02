package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * One head of a vein as clients know it (a ring has many), kept by {@link VeinVisuals} from the server's {@code VEIN_*}
 * packets: where it stands on the ground now and stood at the last client tick, which way it crawls, how wide and high
 * it is, and whether it has stopped. Its {@link VeinVisual} reads everything from here.
 */
public final class LiveVein {
    final int id;
    final int head;
    final Element element;
    final VeinVisual visual;
    Vec3 pos;
    /** Where it stood at the last client tick: the stretch from here to {@link #pos} is what it crawled since. */
    Vec3 prev;
    Vec3 dir = new Vec3(0, 0, 1);
    Direction face = Direction.UP;
    float width = 1, height = 1;
    /** How many ticks what it crawls over stays; 0 if it goes as it crawls on. */
    int linger;
    long heard;
    boolean ended;
    boolean struck;
    int sinceEnd;

    LiveVein(int id, int head, Element element, VeinVisual visual, Vec3 at, long now) {
        this.id = id;
        this.head = head;
        this.element = element;
        this.visual = visual;
        this.pos = at;
        this.prev = at;
        this.heard = now;
    }

    public int id() { return id; }

    public int head() { return head; }

    public Element element() { return element; }

    /** Where it stands on the ground now. */
    public Vec3 pos() { return pos; }

    /** Where it stood at the last client tick. */
    public Vec3 prev() { return prev; }

    /** Its heading along the surface it is on. */
    public Vec3 dir() { return dir; }

    /** The face of the blocks it is on: up (the top of the ground) unless it climbs. */
    public Direction face() { return face; }

    /** Out of the surface it is on, a unit vector. */
    public Vec3 normal() { return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()); }

    public float width() { return width; }

    public float height() { return height; }

    public boolean ended() { return ended; }

    /** How many ticks what it crawls over stays (the vein's {@code :linger}); 0 if it goes as it crawls on. */
    public int linger() { return linger; }

    /** Whether it stopped on something (a body, a wall) rather than running out or off an edge. */
    public boolean struck() { return struck; }

    /** Client ticks since it stopped, 0 until then. */
    public int sinceEnd() { return sinceEnd; }

    /** Between where it stood and stands, for drawing between ticks. */
    public Vec3 at(float partialTick) {
        return prev.lerp(pos, partialTick);
    }
}
