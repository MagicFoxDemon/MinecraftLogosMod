package dev.logos.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Category;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.net.CastPosePayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Poses a caster keeps while something of theirs lasts, such as a beam: {@link #hold} starts one and
 * the returned {@link Hold} ends it. The server only says which pose and for how long; clients draw it
 * ({@code dev.logos.client.CastPoses}, where each pose id is registered). Anything may hold a pose,
 * Logos' own forms and add-ons alike, and one caster may hold several at once: the latest one shows.
 * A hold whose caster is gone or dead ends by itself.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class CastPose {
    /** Beam (光柱): the wand held out along the gaze until the beam ends. */
    public static final Identifier BEAM = Identifier.fromNamespaceAndPath(LogosMod.MODID, "beam");
    /** One-shot gestures a cast plays by the form it leads with ({@link #gesture}); {@link #CAST} when it leads with no form. */
    public static final Identifier CAST = id("cast"), PROJECTILE = id("projectile"), RAY = id("ray"), FIELD = id("field"),
            CONSTRUCT = id("construct"), VEIN = id("vein"), IMBUE = id("imbue");
    private static final Map<String, Identifier> GESTURES = Map.of("projectile", PROJECTILE, "ray", RAY, "field", FIELD,
            "construct", CONSTRUCT, "vein", VEIN, "imbue", IMBUE);
    /** How often a lasting hold is sent again, for players who came close after it began. */
    public static final int REFRESH_TICKS = 10;

    private static final Map<Integer, Hold> HOLDS = new LinkedHashMap<>();
    private static int nextId = 1;

    private CastPose() {}

    /** One pose being held. {@link #release} ends it; releasing twice does nothing. */
    public static final class Hold {
        private final int id;
        private final LivingEntity caster;
        private final Identifier pose;
        private boolean released;

        private Hold(int id, LivingEntity caster, Identifier pose) {
            this.id = id;
            this.caster = caster;
            this.pose = pose;
        }

        public Identifier pose() {
            return pose;
        }

        public boolean released() {
            return released;
        }

        public void release() {
            if (released) return;
            released = true;
            HOLDS.remove(id);
            send(caster, new CastPosePayload(caster.getId(), id, pose, false));
        }
    }

    /** Starts holding {@code pose} on {@code caster} until the returned hold is released. */
    public static Hold hold(LivingEntity caster, Identifier pose) {
        Hold h = new Hold(nextId++, caster, pose);
        HOLDS.put(h.id, h);
        send(caster, new CastPosePayload(caster.getId(), h.id, pose, true));
        return h;
    }

    /**
     * Plays {@code pose} once on {@code caster}: the client gives it the length its drawing says, and it
     * ends by itself, so there is nothing to release. It shows over a held pose while it plays.
     */
    public static void play(LivingEntity caster, Identifier pose) {
        send(caster, new CastPosePayload(caster.getId(), nextId++, pose, true));
    }

    /**
     * The gesture a cast of {@code spell} plays: its leading form's ({@link #lead}), or {@link #CAST} when it
     * leads with no form. Null for a beam, which holds {@link #BEAM} for as long as it lasts instead.
     */
    public static @Nullable Identifier gesture(Spell spell) {
        Node lead = lead(spell);
        if (lead == null) return CAST;
        return lead.rune.name.equals("beam") ? null : GESTURES.getOrDefault(lead.rune.name, CAST);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, path);
    }

    /** The poses {@code caster} is holding now, oldest first (the tests read it). */
    public static List<Identifier> holding(LivingEntity caster) {
        List<Identifier> out = new ArrayList<>();
        for (Hold h : HOLDS.values()) if (h.caster == caster) out.add(h.pose);
        return out;
    }

    /**
     * The form a cast leads with: the first statement after the invocation when it is a form, looking
     * through {@code split} to the form it copies. Null when the spell starts with anything else.
     */
    public static @Nullable Node lead(Spell spell) {
        List<Node> body = spell.body();
        while (!body.isEmpty()) {
            Node first = body.getFirst();
            if (first.rune == null || first.rune.category != Category.FORM) return null;
            if (!first.rune.name.equals("split")) return first;
            body = first.body;
        }
        return null;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (HOLDS.isEmpty() || event.getServer().getTickCount() % REFRESH_TICKS != 0) return;
        for (Hold h : List.copyOf(HOLDS.values())) {
            if (h.caster.isRemoved() || !h.caster.isAlive()) h.release();
            else send(h.caster, new CastPosePayload(h.caster.getId(), h.id, h.pose, true));
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        HOLDS.clear();
    }

    private static void send(LivingEntity caster, CastPosePayload payload) {
        if (!(caster.level() instanceof ServerLevel level)) return;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(caster) <= 128 * 128 && p.connection.hasChannel(CastPosePayload.TYPE)) PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
