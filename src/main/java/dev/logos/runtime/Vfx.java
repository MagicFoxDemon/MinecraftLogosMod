package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.net.FxPayload;
import dev.logos.net.FxPayload.Kind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Visuals derived from the program instead of authored per skill: element → palette, form →
 * emitter shape, motion → trail, size from the program. The server only reports what happened;
 * clients render it with Photon (see {@code dev.logos.client.LogosFx}).
 */
public final class Vfx {
    private static final double RANGE = 96;

    private Vfx() {}

    public static SoundEvent sound(Element e) {
        return switch (e) {
            case FIRE -> SoundEvents.BLAZE_SHOOT;
            case FROST -> SoundEvents.GLASS_BREAK;
            case LIGHTNING -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case VOID -> SoundEvents.WITHER_SHOOT;
            default -> SoundEvents.ILLUSIONER_CAST_SPELL;
        };
    }

    /** Raw registry id meaning "none": no item look, or no custom trail particle. */
    public static final int NONE = -1;

    /**
     * How a spell body chooses to look, as raw registry ids (synced to clients): an item model
     * ({@code :look}) tumbling {@code spin} turns per second, and a particle that replaces the
     * element trail ({@code :trail}). Purely cosmetic.
     */
    public record Guise(int look, double spin, int trail) {
        public static final Guise PLAIN = new Guise(NONE, 0, NONE);
    }

    /** The raw registry id of the item named {@code id}, or {@link #NONE}. */
    public static int lookId(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return NONE;
        return BuiltInRegistries.ITEM.getOptional(key).map(BuiltInRegistries.ITEM::getId).orElse(NONE);
    }

    /** The raw registry id of the particle type named {@code id}, or {@link #NONE}. */
    public static int particleId(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return NONE;
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(key).map(BuiltInRegistries.PARTICLE_TYPE::getId).orElse(NONE);
    }

    private static void send(ServerLevel level, Kind kind, Element el, int variant, Vec3 p, Vec3 aux, float size, int id) {
        send(level, kind, el, variant, p, aux, size, id, Guise.PLAIN);
    }

    private static void send(ServerLevel level, Kind kind, Element el, int variant, Vec3 p, Vec3 aux, float size, int id, Guise g) {
        FxPayload payload = new FxPayload((byte) kind.ordinal(), (byte) el.ordinal(), (byte) variant, (float) p.x, (float) p.y, (float) p.z,
                (float) aux.x, (float) aux.y, (float) aux.z, size, id, g.look(), (float) g.spin(), g.trail());
        for (ServerPlayer player : level.players()) {
            // Only connections that negotiated the channel (fake players and modless clients have not).
            if (player.distanceToSqr(p) <= RANGE * RANGE && player.connection.hasChannel(FxPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * A moving spell body; the client keeps one persistent effect per id and moves it. The guise
     * can add an item model (pointed along {@code heading}) and swap the trail for another particle.
     */
    public static void trail(ServerLevel level, int id, Vec3 pos, Vec3 heading, Element e, Motion m, double size, Guise g) {
        send(level, Kind.TRAIL, e, m.ordinal(), pos, heading, (float) size, id, g);
    }

    /** Spell body {@code id} sets out from {@code caster}'s eyes at {@code eye}: clients start drawing it at the caster's cast point. */
    public static void trailFrom(ServerLevel level, int id, Vec3 eye, int caster) {
        send(level, Kind.TRAIL_FROM, Element.ARCANE, 0, eye, Vec3.ZERO, 0, id, new Guise(caster, 0, NONE));
    }

    /**
     * A lasting beam from {@code from} to {@code to}, re-sent every tick under a stable id.
     * {@code anchor} is the network id of the entity whose eye and gaze it follows (or -1); clients
     * then draw it from that entity's interpolated pose, so the caster sees it swing with no delay.
     */
    public static void beam(ServerLevel level, int id, Vec3 from, Vec3 to, Element e, double radius, int anchor, int trail) {
        send(level, Kind.BEAM_HOLD, e, 0, from, to, (float) radius, id, new Guise(anchor, 0, trail));
    }

    public static void beamEnd(ServerLevel level, int id, Vec3 pos, Element e) {
        send(level, Kind.BEAM_END, e, 0, pos, Vec3.ZERO, 1, id);
    }

    /** {@code phantasm}: {@code count} particles of a registered type, scattered within {@code spread} blocks. */
    public static void phantasm(ServerLevel level, Vec3 p, int particle, int count, double spread) {
        if (particle == NONE) return;
        send(level, Kind.PHANTASM, Element.ARCANE, count, p, Vec3.ZERO, (float) spread, 0, new Guise(NONE, 0, particle));
    }

    public static void trailEnd(ServerLevel level, int id, Vec3 pos, Element e) {
        send(level, Kind.TRAIL_END, e, 0, pos, Vec3.ZERO, 1, id);
    }

    /** Collision termination is distinct from expiry; this does not alter spell event semantics. */
    public static void trailEnd(ServerLevel level, int id, Vec3 pos, Element e, boolean impact) {
        send(level, Kind.TRAIL_END, e, impact ? 1 : 0, pos, Vec3.ZERO, 1, id);
    }

    public static void projectileImpact(ServerLevel level, int id, Vec3 pos, Vec3 outward, Element e, double size) {
        send(level, Kind.PROJECTILE_IMPACT, e, 0, pos, outward, (float) size, id);
    }

    public static void line(ServerLevel level, Vec3 from, Vec3 to, Element e) {
        line(level, from, to, e, NONE);
    }

    /** A line linking two bodies (drain, execute); {@code caster} as for {@link #ray}. */
    public static void line(ServerLevel level, Vec3 from, Vec3 to, Element e, int caster) {
        ray(level, from, to, e, caster, RayEnd.LINK);
    }

    /**
     * How a ray ended, so clients can show the hit: it ran out in the {@code OPEN}, struck a {@code BLOCK}
     * or a {@code CREATURE}; or it is a {@code LINK} drawn between two bodies. Synced by ordinal; append only.
     */
    public enum RayEnd { OPEN, BLOCK, CREATURE, LINK }

    /** A {@code ray}; {@code caster} is the network id of the entity whose eyes it left from (or {@link #NONE}), so clients draw it from that caster's cast point. */
    public static void ray(ServerLevel level, Vec3 from, Vec3 to, Element e, int caster, RayEnd end) {
        send(level, Kind.BEAM, e, end.ordinal(), from, to, 1, 0, new Guise(caster, 0, NONE));
    }

    /**
     * A lasting field under a stable id, re-sent every few ticks: its centre, radius, how long it has lasted
     * and will last in all, and how often it pulses, all in ticks. {@code follow} is the network id of the
     * caster it moves with, or {@link #NONE}; clients then keep it on that caster between updates.
     */
    public static void field(ServerLevel level, int id, Vec3 centre, double radius, Element e, int age, int life, int every, int follow) {
        send(level, Kind.FIELD_HOLD, e, 0, centre, new Vec3(age, life, every), (float) radius, id, new Guise(follow, 0, NONE));
    }

    /** Field {@code id} pulsed (its {@code on-tick} fired). */
    public static void fieldPulse(ServerLevel level, int id, Vec3 centre, double radius, Element e) {
        send(level, Kind.FIELD_PULSE, e, 0, centre, Vec3.ZERO, (float) radius, id);
    }

    /** {@code body} (its centre {@code at}) entered field {@code id}. */
    public static void fieldEnter(ServerLevel level, int id, Vec3 at, Element e, Entity body) {
        send(level, Kind.FIELD_ENTER, e, 0, at, Vec3.ZERO, 1, id, new Guise(body.getId(), 0, NONE));
    }

    /** Field {@code id} ended. */
    public static void fieldEnd(ServerLevel level, int id, Vec3 centre, Element e) {
        send(level, Kind.FIELD_END, e, 0, centre, Vec3.ZERO, 1, id);
    }

    /**
     * What a burst says happened, so clients can show the act and not only the element: harm, healing, a
     * ward raised, a force applied ({@code push}, {@code pull}, {@code disarm}), a potion effect by how it
     * reads, or anything else ({@code GENERIC}). Synced by ordinal; append only.
     */
    public enum Burst { GENERIC, DAMAGE, HEAL, WARD, FORCE, POTION_BENEFICIAL, POTION_HARMFUL, POTION_NEUTRAL }

    /**
     * How a potion effect reads: vanilla's beneficial and harmful effects by their category; anything
     * else (neutral, or from another mod, whose category may not mean the same) as neutral.
     */
    public static Burst potion(Holder<MobEffect> effect) {
        boolean vanilla = effect.unwrapKey().map(k -> k.identifier().getNamespace().equals("minecraft")).orElse(false);
        if (!vanilla) return Burst.POTION_NEUTRAL;
        return switch (effect.value().getCategory()) {
            case BENEFICIAL -> Burst.POTION_BENEFICIAL;
            case HARMFUL -> Burst.POTION_HARMFUL;
            case NEUTRAL -> Burst.POTION_NEUTRAL;
        };
    }

    /** An impact; {@code n} is the old particle count, mapped to a scale. */
    public static void burst(ServerLevel level, Vec3 p, Element e, int n) {
        send(level, Kind.BURST, e, Burst.GENERIC.ordinal(), p, Vec3.ZERO, (float) Math.min(3, 0.5 + n / 20.0), 0);
    }

    /**
     * {@code what} took effect on {@code t}: drawn round its body, sized to it, and turned to face away
     * from {@code from} (the caster's eyes, say) when that is not null.
     */
    public static void burst(ServerLevel level, Entity t, @Nullable Vec3 from, Element e, Burst what) {
        Vec3 c = Targeting.center(t);
        Vec3 dir = from == null || from.distanceToSqr(c) < 1e-6 ? Vec3.ZERO : c.subtract(from).normalize();
        float scale = (float) Math.max(0.6, Math.min(2.5, Math.max(t.getBbWidth(), t.getBbHeight() * 0.6) / 0.9));
        send(level, Kind.BURST, e, what.ordinal(), c, dir, scale, 0);
    }

    public static void cast(ServerLevel level, Vec3 p, Element e) {
        send(level, Kind.CAST, e, 0, p, Vec3.ZERO, 1, 0);
    }

    public static void execute(ServerLevel level, Vec3 p) {
        send(level, Kind.EXECUTE, Element.VOID, 0, p, Vec3.ZERO, 1, 0);
    }

    public static void mark(ServerLevel level, Vec3 p) {
        send(level, Kind.MARK, Element.ARCANE, 0, p, Vec3.ZERO, 1, 0);
    }

    public static void sound(ServerLevel level, Vec3 p, SoundEvent s, float volume, float pitch) {
        level.playSound(null, p.x, p.y, p.z, s, SoundSource.PLAYERS, volume, pitch);
    }
}
