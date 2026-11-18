package dev.logos.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.lowdragmc.photon.client.fx.FXRuntime;

import dev.logos.LogosConfig;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Vfx;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The spell effects that outlast the packet that announced them, as the client keeps them: spell bodies in
 * flight and what they wear, lasting beams, and the rays and beam wakes still showing. {@link LogosFx} fills
 * these tables from the server's packets, {@link FxRender} draws them every frame, and {@link #tick} drops
 * what stopped reporting or has played out.
 */
final class FxLive {
    static final Map<Integer, Trail> TRAILS = new HashMap<>();
    /** A body set out from its caster's eyes is drawn from the caster's cast point and joins its path over these ticks. */
    private static final int CONVERGE_TICKS = 6;
    /** Bodies announced by {@code TRAIL_FROM} and not yet seen: their caster and the eye they left. */
    record SetOut(int caster, Vec3 eye, long at) {}
    static final Map<Integer, SetOut> SET_OUT = new HashMap<>();
    static final Map<Integer, Beam> BEAMS = new HashMap<>();
    /** Rays drawn by their element (not the legacy bead chain) that still have something showing. */
    record LiveRay(RayVisual visual, long born) {}
    static final List<LiveRay> RAYS = new ArrayList<>();
    /** No ray lingers longer than this, whatever its element asks for. */
    private static final int RAY_CAP = 200;
    /** How fast a new beam's end races out to its full length, in blocks a tick (a 64-block beam in 0.8 s). */
    static final double BEAM_REACH = 4;

    /**
     * A beam is gone: its element's wake ({@link BeamWake}), if it draws one, is left along the line as last
     * drawn and fades like a ray's. A beam that wears a {@code :trail} particle leaves none.
     */
    static void leaveWake(Level level, Beam b) {
        if (b.particle != null) return;
        RayVisual wake = BeamWake.create(b.element);
        if (wake.legacy()) return;
        wake.start(level, b.shownFrom, b.shownTo, Vfx.RayEnd.OPEN);
        addRay(new LiveRay(wake, level.getGameTime()));
    }

    /** At the cap a new ray replaces the oldest: they go by themselves soon enough, and the newest is what was just cast. */
    static void addRay(LiveRay ray) {
        if (!RAYS.isEmpty() && RAYS.size() >= LogosConfig.FX_MAX_RAYS.get()) RAYS.removeFirst().visual().end();
        RAYS.add(ray);
    }

    /** How far each lasting beam has reached out by now, growing to its full length (the client scenes check it). */
    static List<Double> drawnBeamLengths() {
        Level level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        return BEAMS.values().stream()
                .map(b -> Math.min(b.from.distanceTo(b.to), (level.getGameTime() - b.born) * BEAM_REACH)).toList();
    }

    /** A lasting {@code beam} as last reported; drawn every frame as a beacon-style tube. */
    static final class Beam {
        final Element element;
        final int anchor;
        final @Nullable ParticleOptions particle;
        Vec3 prevFrom, prevTo, from, to;
        float radius;
        long lastSeen;
        /** When it first arrived: its end races out from the cast point from then on. */
        final long born;
        /** The line as last drawn (after the cast point and the reach), where its wake is left. */
        Vec3 shownFrom, shownTo;

        @Nullable FireBeamVisual fire;
        @Nullable FrostBeamVisual frost;
        @Nullable LightningBeamVisual lightning;
        @Nullable VoidBeamVisual voidBeam;
        @Nullable PhysicalBeamVisual physical;
        @Nullable ArcaneBeamVisual arcane;
        @Nullable LifeBeamVisual life;

        /** Whether its element draws the beam itself, rather than the generic beacon-style tube. */
        boolean dedicated() {
            return fire != null || frost != null || lightning != null || voidBeam != null || physical != null || arcane != null || life != null;
        }

        void endVisual() {
            if (fire != null) fire.end();
            if (frost != null) frost.end();
            if (lightning != null) lightning.end();
            if (voidBeam != null) voidBeam.end();
            if (physical != null) physical.end();
            if (arcane != null) arcane.end();
            if (life != null) life.end();
        }

        Beam(Element element, int anchor, @Nullable ParticleOptions particle, Vec3 from, Vec3 to, long born) {
            this.element = element;
            this.anchor = anchor;
            this.particle = particle;
            this.prevFrom = this.from = this.shownFrom = from;
            this.prevTo = this.to = to;
            this.shownTo = from;
            this.born = born;
            if (element == Element.FIRE) fire = new FireBeamVisual();
            if (element == Element.FROST) frost = new FrostBeamVisual();
            if (element == Element.LIGHTNING) lightning = new LightningBeamVisual(particle == null);
            if (element == Element.VOID) voidBeam = new VoidBeamVisual(particle == null);
            if (element == Element.PHYSICAL) physical = new PhysicalBeamVisual(particle == null);
            if (element == Element.ARCANE && !ArcaneBeamVisual.legacy()) arcane = new ArcaneBeamVisual(particle == null);
            if (element == Element.LIFE && !LifeBeamVisual.legacy()) life = new LifeBeamVisual(particle == null);
        }
    }

    /** One moving spell body as the client sees it. */
    static final class Trail {
        /** The element trail; stays null when a {@code :trail} particle replaces it. */
        @Nullable FXRuntime runtime;
        @Nullable ProjectileVisual visual;
        final @Nullable ParticleOptions particle;
        final @Nullable Look look;
        long lastSeen;
        Vec3 last;
        /** From the caster's cast point to where it really set out; drawn shifted by this, easing to nothing. */
        Vec3 offset = Vec3.ZERO;
        long born;

        Trail(@Nullable ParticleOptions particle, @Nullable Look look, Vec3 pos, long now) {
            this.particle = particle;
            this.look = look;
            this.last = pos;
            this.lastSeen = now;
            this.born = now;
        }

        /** Where to draw it when it is really at {@code pos}: shifted toward the cast point at first, onto its path by {@value #CONVERGE_TICKS} ticks. */
        Vec3 shown(Vec3 pos, long now) {
            if (offset == Vec3.ZERO) return pos;
            float s = Math.min(1, (now - born) / (float) CONVERGE_TICKS);
            return pos.add(offset.scale(1 - s * s * (3 - 2 * s)));
        }

        void destroy(boolean force) {
            if (runtime != null) runtime.destroy(force);
        }
    }

    /**
     * ❖ What a spell body wears ({@code :look}): an item model, drawn facing the camera, its tip turned
     * along the body's on-screen heading (so a flat sprite never shows its edge, even flying straight
     * away from the caster), and tumbling {@code spin} turns per second; or a creature's model, a
     * stand-in never added to the world, facing where the body flies and tumbling over its side.
     */
    static final class Look {
        final @Nullable ItemStack stack;
        final @Nullable Entity model;
        final float scale;
        final float spin;
        final long born;
        Vec3 prev;
        Vec3 cur;
        Vec3 heading;
        /** Last on-screen heading angle; kept while the body flies along the view axis and has none. */
        float angle = (float) (Math.PI / 2);
        /** Tumble forward over whichever side of the screen the body is heading to. */
        float tumble = -1;

        Look(@Nullable ItemStack stack, @Nullable Entity model, float size, float spin, Vec3 pos, Vec3 heading, long born) {
            this.stack = stack;
            this.model = model;
            this.scale = Math.max(0.5f, Math.min(6f, size * 2.5f));
            this.spin = spin;
            this.prev = this.cur = pos;
            this.heading = heading;
            this.born = born;
        }

        static @Nullable Look of(FxPayload p, Vec3 pos, Level level, long now) {
            Vec3 heading = new Vec3(p.ax(), p.ay(), p.az());
            int creature = dev.logos.runtime.Vfx.creatureOf(p.look());
            if (creature >= 0) {
                var type = BuiltInRegistries.ENTITY_TYPE.byId(creature);
                Entity model = type == null ? null : type.create(level);
                return model == null ? null : new Look(null, model, p.size(), p.spin(), pos, heading, now);
            }
            if (p.look() < 0) return null;
            Item item = BuiltInRegistries.ITEM.byId(p.look());
            if (item == null || item == Items.AIR) return null;
            return new Look(new ItemStack(item), null, p.size(), p.spin(), pos, heading, now);
        }

        void moveTo(Vec3 pos, FxPayload p) {
            prev = cur;
            cur = pos;
            heading = new Vec3(p.ax(), p.ay(), p.az());
        }
    }

    private FxLive() {}

    /** Where each spell body in flight was last drawn (the client scenes check it). */
    static List<Vec3> drawnTrails() {
        return TRAILS.values().stream().map(t -> t.last).toList();
    }

    /** How many element-drawn rays still have something showing (the client scenes check it). */
    static int liveRays() {
        return RAYS.size();
    }

    /** Ends every ray and beam wake still showing, so a client scene can sample a fresh one. */
    static void clearRays() {
        RAYS.forEach(r -> r.visual().end());
        RAYS.clear();
    }

    /** Drops trails whose spell body stopped reporting (e.g. it left tracking range). */
    static void tick() {
        Level level = Minecraft.getInstance().level;
        if (level != null) SET_OUT.values().removeIf(o -> level.getGameTime() - o.at() > 40);
        if (level == null) {
            TRAILS.values().forEach(t -> t.destroy(true));
            TRAILS.clear();
            BEAMS.values().forEach(Beam::endVisual);
            BEAMS.clear();
            RAYS.forEach(r -> r.visual().end());
            RAYS.clear();
            return;
        }
        RAYS.removeIf(r -> {
            if (level.getGameTime() - r.born() <= RAY_CAP && r.visual().tick(level)) return false;
            r.visual().end();
            return true;
        });
        BEAMS.values().removeIf(b -> {
            // Told only every few ticks (far off), it rests where it was last told instead of sliding over that stretch again.
            if (level.getGameTime() - b.lastSeen > 1) {
                b.prevFrom = b.from;
                b.prevTo = b.to;
            }
            if (level.getGameTime() - b.lastSeen <= 10) return false;
            leaveWake(level, b);
            b.endVisual();
            return true;
        });
        for (Iterator<Trail> it = TRAILS.values().iterator(); it.hasNext(); ) {
            Trail t = it.next();
            if (t.look != null && level.getGameTime() - t.lastSeen > 1) t.look.prev = t.look.cur;
            if (level.getGameTime() - t.lastSeen > 20) {
                t.destroy(false);
                it.remove();
            }
        }
    }
}
