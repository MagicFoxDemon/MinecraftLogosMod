package dev.logos.client;

import dev.logos.lang.Element;
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
    float width = 1, height = 1;
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

    /** Its level heading. */
    public Vec3 dir() { return dir; }

    public float width() { return width; }

    public float height() { return height; }

    public boolean ended() { return ended; }

    /** Whether it stopped on something (a body, a wall) rather than running out or off an edge. */
    public boolean struck() { return struck; }

    /** Client ticks since it stopped, 0 until then. */
    public int sinceEnd() { return sinceEnd; }

    /** Between where it stood and stands, for drawing between ticks. */
    public Vec3 at(float partialTick) {
        return prev.lerp(pos, partialTick);
    }
}
