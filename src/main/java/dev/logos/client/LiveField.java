package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One field as {@link FieldVisuals} keeps it for its {@link FieldVisual}: its element, centre and radius,
 * how far through its life it is, the ground it lies on, and the pulses and entries heard since the last
 * tick. Times are client ticks ({@link ClientCharges#ticks()}, which a client scene can freeze for a
 * screenshot); the server reminds clients of a field every few ticks, and in between it is carried on
 * (a following field stays on its caster).
 */
public final class LiveField {
    public enum Phase {
        /** Still there. */
        ACTIVE,
        /** Ended (its life ran out, its spell went out, or it stopped being heard of). */
        ENDED
    }

    /** A body that entered the field: where its centre was, and its network id. */
    public record Entry(Vec3 at, int entityId, long tick) {}

    final int id;
    final Element element;
    final FieldVisual visual;
    float radius;
    int life, every, follow = -1;
    /** Client tick at which the field's age was 0. */
    long born;
    long heard;
    Phase phase = Phase.ACTIVE;
    long endedAt;
    boolean entered = true;
    private Vec3 centre, prevCentre;
    private long movedAt;
    private double ground;
    private @Nullable BlockPos groundFrom;
    private long lastPulse = Long.MIN_VALUE / 2;
    private int pulses;
    final List<Long> newPulses = new ArrayList<>();
    final List<Entry> newEntries = new ArrayList<>();

    LiveField(int id, Element element, FieldVisual visual, Vec3 centre) {
        this.id = id;
        this.element = element;
        this.visual = visual;
        this.centre = this.prevCentre = centre;
        this.movedAt = ClientCharges.ticks();
        this.ground = centre.y - 1;
    }

    void hold(Vec3 at, float radius, int age, int life, int every, int follow) {
        long now = ClientCharges.ticks();
        prevCentre = centre(0);
        centre = at;
        movedAt = now;
        this.radius = radius;
        this.life = Math.max(1, life);
        this.every = Math.max(1, every);
        this.follow = follow;
        this.born = now - age;
        this.heard = now;
    }

    void pulse() {
        long now = ClientCharges.ticks();
        lastPulse = now;
        pulses++;
        newPulses.add(now);
    }

    void enter(Vec3 at, int entityId) {
        newEntries.add(new Entry(at, entityId, ClientCharges.ticks()));
    }

    void end() {
        if (phase == Phase.ENDED) return;
        phase = Phase.ENDED;
        endedAt = ClientCharges.ticks();
    }

    /** Finds the ground under the centre again once it has moved to another block. */
    void settle(Level level) {
        Vec3 c = centre(0);
        BlockPos at = BlockPos.containing(c);
        if (at.equals(groundFrom)) return;
        groundFrom = at;
        // The top of the first solid block at or below the centre, looking a few blocks down.
        for (int dy = 0; dy <= 4; dy++) {
            BlockPos below = at.below(dy);
            BlockState state = level.getBlockState(below);
            VoxelShape shape = state.getCollisionShape(level, below);
            if (!shape.isEmpty()) {
                double top = below.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                if (top <= c.y + 1e-3) {
                    ground = top;
                    return;
                }
            }
        }
        ground = c.y - 1;
    }

    public int id() { return id; }

    public Element element() { return element; }

    public Phase phase() { return phase; }

    /** True on the first {@link FieldVisual#tick} it gets. */
    public boolean entered() { return entered; }

    /** Its radius in blocks: where its effect ends. */
    public float radius() { return radius; }

    /** Its whole life, in ticks. */
    public int life() { return life; }

    /** Ticks between its pulses. */
    public int every() { return every; }

    /** Whether it moves with its caster ({@code :follow}). */
    public boolean follows() { return follow >= 0; }

    /** Ticks it has lasted, held at its whole life once that has run out. */
    public float age(float partialTick) {
        float t = ClientCharges.ticks() - born + held(partialTick);
        return Math.min(t, phase == Phase.ENDED ? endedAt - born : life);
    }

    /** How far through its life it is, 0 to 1: one turn of a clock over the whole field. */
    public float progress(float partialTick) {
        return Mth.clamp(age(partialTick) / life, 0, 1);
    }

    /** Ticks since it ended (0 while it lasts). */
    public float sinceEnd(float partialTick) {
        return phase == Phase.ENDED ? ClientCharges.ticks() - endedAt + held(partialTick) : 0;
    }

    /** Ticks since its last pulse (very large before the first). */
    public float sincePulse(float partialTick) {
        return ClientCharges.ticks() - lastPulse + held(partialTick);
    }

    /** How many times it has pulsed. */
    public int pulses() { return pulses; }

    /** Pulses heard since the last tick (client tick of each), for {@link FieldVisual#tick}. */
    public List<Long> newPulses() { return newPulses; }

    /** Bodies that entered since the last tick, for {@link FieldVisual#tick}. */
    public List<Entry> newEntries() { return newEntries; }

    /** Its centre: on its caster if it follows one, otherwise moved smoothly between the server's reminders. */
    public Vec3 centre(float partialTick) {
        if (follow >= 0 && Minecraft.getInstance().level != null) {
            Entity caster = Minecraft.getInstance().level.getEntity(follow);
            if (caster != null) return caster.getPosition(held(partialTick)).add(0, 1, 0);
        }
        float k = Mth.clamp((ClientCharges.ticks() - movedAt + held(partialTick)) / 4f, 0, 1);
        return prevCentre.lerp(centre, k);
    }

    /** The height of the ground it lies on (the top of the solid block under its centre). */
    public double ground() { return ground; }

    /** The point on its ground under its centre: where its circle is laid. */
    public Vec3 floor(float partialTick) {
        Vec3 c = centre(partialTick);
        return new Vec3(c.x, ground, c.z);
    }

    /** Whether the camera is within its radius (standing in it). */
    public boolean cameraInside(Vec3 camera, float partialTick) {
        Vec3 f = floor(partialTick);
        double dx = camera.x - f.x, dz = camera.z - f.z;
        return dx * dx + dz * dz <= radius * radius && camera.y > f.y - 1 && camera.y < f.y + 4;
    }

    /** A frozen clock (a client scene's screenshot) holds the frame still too. */
    private static float held(float partialTick) {
        return ClientCharges.frozen() ? 0 : partialTick;
    }
}
