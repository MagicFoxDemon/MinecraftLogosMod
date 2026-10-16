package dev.logos.client;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * One charge as {@link ChargeVisuals} keeps it for its {@link ChargeVisual}: whose it is, its element and
 * tier, which phase it is in and since when, how full it is, and where its caster's cast point and feet
 * are. Times are client ticks ({@link ClientCharges#ticks()}), which run on while the game is frozen
 * with {@code /tick freeze}; positions follow the caster while it is there and stay where it was last
 * seen once it is gone.
 */
public final class LiveCharge {
    public enum Phase {
        /** Mana still flowing in: {@link #progress} rises from 0 to 1. */
        CHARGING,
        /** Full, waiting to be let go. */
        HELD,
        /** Let go into its spell. */
        RELEASED,
        /** Ended without casting (let go early, switched away, interrupted). */
        FIZZLED;

        public boolean over() {
            return this == RELEASED || this == FIZZLED;
        }
    }

    final int entityId;
    final Element element;
    final int tier;
    final ChargeVisual visual;
    /** The synced charge while it is charging or held; null once over (or for a release never seen charging). */
    ClientCharges.@Nullable State state;
    Phase phase;
    final long born;
    long since;
    boolean entered = true;
    float endProgress;
    private Vec3 tip = Vec3.ZERO, feet = Vec3.ZERO, look = new Vec3(0, 0, 1);

    LiveCharge(int entityId, Element element, int tier, ChargeVisual visual, Phase phase) {
        this.entityId = entityId;
        this.element = element;
        this.tier = tier;
        this.visual = visual;
        this.phase = phase;
        this.born = this.since = ClientCharges.ticks();
        this.endProgress = phase == Phase.RELEASED ? 1 : 0;
        LivingEntity holder = holder();
        if (holder != null) {
            tip = CastPoints.of(holder, 1);
            feet = holder.position();
            look = holder.getLookAngle();
        }
    }

    void enter(Phase next) {
        if (next == phase) return;
        if (next.over()) endProgress = progress(0);
        phase = next;
        since = ClientCharges.ticks();
        entered = true;
        if (next.over()) state = null;
    }

    public int entityId() { return entityId; }

    public Element element() { return element; }

    /** 0 ordinary, 1 elaborate, 2 grand, 3 colossal ({@code Charging.tier}: under 100, 800, 3200 mana, and above). */
    public int tier() { return tier; }

    public Phase phase() { return phase; }

    /** True on the first {@link ChargeVisual#tick} of each phase (and the first tick of all). */
    public boolean entered() { return entered; }

    /** Injected share, 0 to 1, eased between the server's updates; 1 once held or released, frozen where it stopped once fizzled. */
    public float progress(float partialTick) {
        partialTick = held(partialTick);
        if (state != null) return state.progress(partialTick);
        return phase == Phase.FIZZLED ? endProgress : 1;
    }

    /** Ticks since the charge was first heard of. */
    public float age(float partialTick) {
        partialTick = held(partialTick);
        return ClientCharges.ticks() - born + partialTick;
    }

    /** Ticks since the current phase began. */
    public float phaseAge(float partialTick) {
        partialTick = held(partialTick);
        return ClientCharges.ticks() - since + partialTick;
    }

    /** A frozen charge clock (a client scene's screenshot) holds the frame still too. */
    private static float held(float partialTick) {
        return ClientCharges.frozen() ? 0 : partialTick;
    }

    /** The caster, while it is still in the level. */
    public @Nullable LivingEntity holder() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.getEntity(entityId) instanceof LivingEntity l && l.isAlive() ? l : null;
    }

    /** The cast point (a wand's head as drawn this frame, a lifeform's cast bone, or an estimate). */
    public Vec3 tip(float partialTick) {
        LivingEntity holder = holder();
        if (holder != null) tip = CastPoints.of(holder, partialTick);
        return tip;
    }

    /** The caster's feet: where circles on the ground are drawn. */
    public Vec3 feet(float partialTick) {
        LivingEntity holder = holder();
        if (holder != null) feet = holder.getPosition(partialTick);
        return feet;
    }

    /** Where the caster looks. */
    public Vec3 look(float partialTick) {
        LivingEntity holder = holder();
        if (holder != null) look = holder.getViewVector(partialTick);
        return look;
    }

    /** The caster's height, for pillars and auras round the body (a player's when it is gone). */
    public float height() {
        LivingEntity holder = holder();
        return holder != null ? holder.getBbHeight() : 1.8f;
    }

    /** Whether this is the camera's own charge seen in first person: the head is right in front of the eyes. */
    public boolean firstPerson() {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        return camera != null && camera.getId() == entityId && mc.options.getCameraType().isFirstPerson();
    }

    /** Scale for anything drawn at the head: half size in first person, where a glow that close fills the view. */
    public float near() {
        return firstPerson() ? 0.5f : 1f;
    }
}
