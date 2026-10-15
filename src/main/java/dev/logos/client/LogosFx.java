package dev.logos.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Circle;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.net.FxPayload;
import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Vfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Photon visuals built in code from the spell's own semantics: the element picks the palette, the
 * form picks the emitter shape, the motion shapes the trail, the size comes from the program.
 * No authored .fx assets — every effect is a function of (kind, element, variant).
 * <p>
 * Each effect is layered for legibility: a bright <b>core</b> (opaque, fades white → element colour),
 * an additive <b>halo</b> at low alpha (glow without washing everything to white), and fast
 * <b>sparks</b> on impacts. All layers are full-bright so spells read at night and indoors, and a few
 * emissive vanilla particles of the element are sprinkled on top.
 */
public final class LogosFx {
    /** {@code bright}: the hot centre a particle is born with; {@code core}: the element's colour. */
    private record Palette(int bright, int core) {}

    private static final Map<Element, Palette> PALETTES = new EnumMap<>(Element.class);

    /** The element's colour (opaque RGB), for other renderers that follow the same palette. */
    static int coreColor(Element e) {
        return PALETTES.get(e).core() & 0xFFFFFF;
    }

    static {
        PALETTES.put(Element.FIRE, new Palette(0xFFFFF2B0, 0xFFFF5A00));
        PALETTES.put(Element.FROST, new Palette(0xFFF0FFFF, 0xFF38B6FF));
        PALETTES.put(Element.LIGHTNING, new Palette(0xFFFFFFFF, 0xFF6C8CFF));
        PALETTES.put(Element.VOID, new Palette(0xFFF0C8FF, 0xFF9A28FF));
        PALETTES.put(Element.PHYSICAL, new Palette(0xFFFFFFFF, 0xFFCFCFCF));
        PALETTES.put(Element.ARCANE, new Palette(0xFFFFD8FF, 0xFFE83CFF));
        PALETTES.put(Element.LIFE, new Palette(0xFFF2FFD8, 0xFF40E050));
    }

    private static final Map<String, FX> CACHE = new HashMap<>();
    private static final Map<Integer, Trail> TRAILS = new HashMap<>();
    /** A body set out from its caster's eyes is drawn from the caster's cast point and joins its path over these ticks. */
    private static final int CONVERGE_TICKS = 6;
    /** Bodies announced by {@code TRAIL_FROM} and not yet seen: their caster and the eye they left. */
    private record SetOut(int caster, Vec3 eye, long at) {}
    private static final Map<Integer, SetOut> SET_OUT = new HashMap<>();
    private static final Map<Integer, Beam> BEAMS = new HashMap<>();
    /** Rays drawn by their element (not the legacy bead chain) that still have something showing. */
    private record LiveRay(RayVisual visual, long born) {}
    private static final List<LiveRay> RAYS = new ArrayList<>();
    /** No ray lingers longer than this, whatever its element asks for. */
    private static final int RAY_CAP = 200;
    /** The last ray or link that arrived (the client scenes check it). */
    public record FiredRay(Element element, Vfx.RayEnd end) {}
    private static @Nullable FiredRay lastRay;
    /** How fast a new beam's end races out to its full length, in blocks a tick (a 64-block beam in 0.8 s). */
    private static final double BEAM_REACH = 4;

    /**
     * A beam is gone: its element's wake ({@link BeamWake}), if it draws one, is left along the line as last
     * drawn and fades like a ray's. A beam that wears a {@code :trail} particle leaves none.
     */
    private static void leaveWake(Level level, Beam b) {
        if (b.particle != null) return;
        RayVisual wake = BeamWake.create(b.element);
        if (wake.legacy()) return;
        wake.start(level, b.shownFrom, b.shownTo, Vfx.RayEnd.OPEN);
        RAYS.add(new LiveRay(wake, level.getGameTime()));
    }

    /** How far each lasting beam has reached out by now, growing to its full length (the client scenes check it). */
    public static List<Double> drawnBeamLengths() {
        Level level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        return BEAMS.values().stream()
                .map(b -> Math.min(b.from.distanceTo(b.to), (level.getGameTime() - b.born) * BEAM_REACH)).toList();
    }

    /** A lasting {@code beam} as last reported; drawn every frame as a beacon-style tube. */
    private static final class Beam {
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
    private static final class Trail {
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
     * ❖ An item model worn by a spell body ({@code :look}). It is drawn facing the camera, its tip
     * turned along the body's on-screen heading (so a flat sprite never shows its edge, even
     * flying straight away from the caster), and tumbling {@code spin} turns per second.
     */
    private static final class Look {
        final ItemStack stack;
        final float scale;
        final float spin;
        final long born;
        final ItemStackRenderState state = new ItemStackRenderState();
        Vec3 prev;
        Vec3 cur;
        Vec3 heading;
        /** Last on-screen heading angle; kept while the body flies along the view axis and has none. */
        float angle = (float) (Math.PI / 2);
        /** Tumble forward over whichever side of the screen the body is heading to. */
        float tumble = -1;

        Look(ItemStack stack, float size, float spin, Vec3 pos, Vec3 heading, long born) {
            this.stack = stack;
            this.scale = Math.max(0.5f, Math.min(6f, size * 2.5f));
            this.spin = spin;
            this.prev = this.cur = pos;
            this.heading = heading;
            this.born = born;
        }

        static @Nullable Look of(FxPayload p, Vec3 pos, long now) {
            if (p.look() < 0) return null;
            Item item = BuiltInRegistries.ITEM.byId(p.look());
            if (item == null || item == Items.AIR) return null;
            return new Look(new ItemStack(item), p.size(), p.spin(), pos, new Vec3(p.ax(), p.ay(), p.az()), now);
        }

        void moveTo(Vec3 pos, FxPayload p) {
            prev = cur;
            cur = pos;
            heading = new Vec3(p.ax(), p.ay(), p.az());
        }
    }

    private LogosFx() {}

    // ---- playback ----------------------------------------------------------------------------

    public static void handle(FxPayload p) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        Element el = Element.values()[Math.floorMod(p.element(), Element.values().length)];
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        switch (p.kindEnum()) {
            case TRAIL_FROM -> SET_OUT.put(p.id(), new SetOut(p.look(), pos, level.getGameTime()));
            case TRAIL -> {
                Trail t = TRAILS.get(p.id());
                Vec3 real = pos;
                if (t == null) {
                    SetOut from = SET_OUT.remove(p.id());
                    Entity caster = from != null ? level.getEntity(from.caster()) : null;
                    // A body cast from the eye starts inside the camera: drawn from the cast point instead, or not until it is a little way out.
                    if (caster == null && nearCamera(pos)) return;
                    Vec3 offset = caster != null ? CastPoints.of(caster, 1).subtract(from.eye()) : Vec3.ZERO;
                    pos = real.add(offset);
                    t = new Trail(particle(p.particle()), Look.of(p, pos, level.getGameTime()), pos, level.getGameTime());
                    t.offset = offset;
                    TRAILS.put(p.id(), t);
                } else {
                    pos = t.shown(real, level.getGameTime());
                    if (t.look != null) t.look.moveTo(pos, p);
                }
                t.lastSeen = level.getGameTime();
                if (t.particle == null || t.look == null) {
                    double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
                    if (t.visual == null) t.visual = ProjectileVisual.create(el, pos, now);
                    t.visual.move(pos, new Vec3(p.ax(), p.ay(), p.az()), p.size(), now);
                }
                if (t.particle != null) {
                    // ✧ :trail — a stream of that particle along the path flown since the last report.
                    int n = (int) Math.max(1, Math.min(12, Math.ceil(t.last.distanceTo(pos) * 3)));
                    double jitter = p.size() * 0.5;
                    RandomSource r = level.getRandom();
                    for (int k = 1; k <= n; k++) {
                        Vec3 at = t.last.lerp(pos, (double) k / n);
                        level.addParticle(t.particle, at.x + (r.nextDouble() - 0.5) * jitter, at.y + (r.nextDouble() - 0.5) * jitter,
                                at.z + (r.nextDouble() - 0.5) * jitter, 0, 0, 0);
                    }
                } else {
                    if (t.runtime == null || !t.runtime.isValid()) {
                        Motion m = Motion.values()[Math.floorMod(p.variant(), Motion.values().length)];
                        ProjectileVisual visual = t.visual;
                        t.runtime = play(level, fx("projectile", el, m.ordinal(), () -> visual.particles(m)),
                                pos, visual.particleScale(p.size(), t.look != null));
                    } else {
                        t.runtime.getRoot().updatePos(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
                    }
                    if (!t.visual.legacyAccents()) {
                        Vector3f heading = new Vector3f(p.ax(), p.ay(), p.az());
                        if (heading.lengthSquared() > 1e-6f) {
                            t.runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), heading.normalize()));
                        }
                    } else accents(level, el, pos, 1, 0.08, 0.01);
                }
                t.last = pos;
            }
            case TRAIL_END -> {
                Trail t = TRAILS.remove(p.id());
                if (t != null) t.destroy(false);
                ProjectileImpactVisual impact = ProjectileImpactVisual.create(el);
                List<ParticleEmitter> expiry = impact.expiry();
                if (t != null && t.particle != null) {
                    // A custom trail ends in a puff of its own particle rather than the element's burst.
                    scatter(level, t.particle, pos, 10, 0.4);
                } else if (!expiry.isEmpty()) {
                    if (p.variant() == 0) play(level, fx("projectile_expiry", el, 0, () -> expiry), pos, 1);
                } else {
                    play(level, fx("burst", el, 0, () -> burst(el)), pos, 1);
                    accents(level, el, pos, 10, 0.25, 0.12);
                }
            }
            case PROJECTILE_IMPACT -> {
                ProjectileImpactVisual visual = ProjectileImpactVisual.create(el);
                List<ParticleEmitter> layers = visual.impact();
                if (!layers.isEmpty()) {
                    Vec3 outward = new Vec3(p.ax(), p.ay(), p.az());
                    if (outward.lengthSqr() < 1e-6) outward = new Vec3(0, 1, 0);
                    FXRuntime runtime = play(level, fx("projectile_impact", el, 0, () -> layers),
                            pos.add(outward.normalize().scale(.03)), visual.scale(p.size()));
                    runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                            new Vector3f((float)outward.x, (float)outward.y, (float)outward.z).normalize()));
                }
            }
            case PHANTASM -> {
                ParticleOptions type = particle(p.particle());
                if (type != null) scatter(level, type, pos, Math.max(1, p.variant() & 0xFF), p.size());
            }
            case BEAM_HOLD -> {
                Vec3 to = new Vec3(p.ax(), p.ay(), p.az());
                Beam b = BEAMS.get(p.id());
                if (b == null) {
                    b = new Beam(el, p.look(), particle(p.particle()), pos, to, level.getGameTime());
                    BEAMS.put(p.id(), b);
                } else {
                    b.prevFrom = b.from;
                    b.prevTo = b.to;
                    b.from = pos;
                    b.to = to;
                }
                b.radius = p.size();
                b.lastSeen = level.getGameTime();
                // A few motes drift off the tube (skipping the stretch inside the caster's face).
                double len = pos.distanceTo(to);
                RandomSource r = level.getRandom();
                for (int k = 0; k < Math.min(6, 1 + (int) (len / 6)); k++) {
                    Vec3 at = pos.lerp(to, Math.min(1, (1.5 + r.nextDouble() * Math.max(0, len - 1.5)) / Math.max(len, 1e-3)));
                    double s = p.size();
                    at = at.add((r.nextDouble() - 0.5) * s, (r.nextDouble() - 0.5) * s, (r.nextDouble() - 0.5) * s);
                    if (b.particle != null) level.addParticle(b.particle, at.x, at.y, at.z, 0, 0, 0);
                    else if (!b.dedicated()) accents(level, el, at, 1, 0.05, 0.02);
                }
                if (!b.dedicated() && r.nextInt(3) == 0) accents(level, el, to, 2, 0.15, 0.08);
            }
            case BEAM_END -> {
                Beam b = BEAMS.remove(p.id());
                if (b != null) leaveWake(level, b);
                if (b != null && b.dedicated()) {
                    b.endVisual();
                    if (b.particle != null) scatter(level, b.particle, pos, 10, 0.4);
                    break;
                }
                Element e = b != null ? b.element : el;
                if (b != null && b.particle != null) scatter(level, b.particle, pos, 10, 0.4);
                else play(level, fx("burst", e, 0, () -> burst(e)), pos, 0.8f);
            }
            case BURST -> {
                Vfx.Burst what = Vfx.Burst.values()[Math.floorMod(p.variant(), Vfx.Burst.values().length)];
                EffectBurstVisual visual = EffectBurstVisual.create(what, el);
                List<ParticleEmitter> layers = visual.layers();
                if (!layers.isEmpty()) {
                    float scale = visual.scale(p.size());
                    Vector3f away = new Vector3f(p.ax(), p.ay(), p.az());
                    // With a direction, it is drawn on the struck side of the body, which would otherwise hide it.
                    if (away.lengthSquared() > 1e-6f) pos = pos.subtract(new Vec3(away.normalize()).scale(.45 * scale + .3));
                    else away.set(0, 1, 0);
                    // Local +Z points away from the source (or up).
                    FXRuntime runtime = play(level, fx("effect_burst", el, what.ordinal(), () -> layers), pos, scale);
                    runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), away.normalize()));
                    break;
                }
                play(level, fx("burst", el, 0, () -> burst(el)), pos, Math.max(0.7f, Math.min(1.3f, p.size())));
                accents(level, el, pos, 8 + (int) (p.size() * 6), 0.3, 0.12);
            }
            // A small circle at the caster's feet: the "magic circle" of the invocation.
            case CAST -> play(level, fx("ring", el, 0, () -> ring(el)), pos, 0.8f, 0.9f);
            case EXECUTE -> {
                play(level, fx("execute", Element.VOID, 0, LogosFx::execute), pos, 1.4f);
                play(level, fx("ring", Element.VOID, 0, () -> ring(Element.VOID)), pos.add(0, -0.8, 0), 1, 2.5f);
                accents(level, Element.VOID, pos, 40, 0.6, 0.25);
            }
            case MARK -> play(level, fx("mark", el, 0, () -> mark(el)), pos, 0.8f);
            case RING -> {
                play(level, fx("ring", el, 0, () -> ring(el)), pos, 1, p.size());
                RandomSource r = level.getRandom();
                for (int k = 0; k < (int) (6 + p.size() * 4); k++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    accents(level, el, pos.add(Math.cos(a) * p.size(), 0.1, Math.sin(a) * p.size()), 1, 0.05, 0.02);
                }
            }
            case PULSE -> {
                play(level, fx("pulse", el, 0, () -> pulse(el)), pos, 1, p.size());
                accents(level, el, pos, (int) (4 + p.size() * 4), p.size() * 0.6, 0.03);
            }
            case BEAM -> {
                Vec3 end = new Vec3(p.ax(), p.ay(), p.az());
                // A ray from its caster's eyes is drawn from the caster's cast point (a wand's head).
                Entity caster = p.look() >= 0 ? level.getEntity(p.look()) : null;
                if (caster != null) pos = CastPoints.of(caster, 1);
                Vfx.RayEnd how = Vfx.RayEnd.values()[Math.floorMod(p.variant(), Vfx.RayEnd.values().length)];
                lastRay = new FiredRay(el, how);
                RayVisual ray = RayVisual.create(el);
                if (!ray.legacy()) {
                    ray.start(level, pos, end, how);
                    RAYS.add(new LiveRay(ray, level.getGameTime()));
                    break;
                }
                double len = pos.distanceTo(end);
                int beads = (int) Math.max(3, Math.min(80, len * 5));
                FX bead = fx("beam", el, 0, () -> bead(el));
                // Start a little way out: a ray cast from the eye would otherwise put a bead inside the camera.
                double start = caster == null && len > 2 ? 1.2 / len : 0;
                for (int k = 0; k <= beads; k++) {
                    double t = start + (1 - start) * k / beads;
                    Vec3 at = pos.lerp(end, t);
                    play(level, bead, at, 1);
                    if (k % 2 == 0) accents(level, el, at, 1, 0.04, 0.02);
                }
            }
        }
    }

    /** Where each spell body in flight was last drawn (the client scenes check it). */
    public static List<Vec3> drawnTrails() {
        return TRAILS.values().stream().map(t -> t.last).toList();
    }

    /** The last ray or link that arrived, or null (the client scenes check it). */
    public static @Nullable FiredRay lastRay() {
        return lastRay;
    }

    /** How many element-drawn rays still have something showing (the client scenes check it). */
    public static int liveRays() {
        return RAYS.size();
    }

    /** Ends every ray and beam wake still showing, so a client scene can sample a fresh one. */
    public static void clearRays() {
        RAYS.forEach(r -> r.visual().end());
        RAYS.clear();
    }

    /** Drops trails whose spell body stopped reporting (e.g. it left tracking range). */
    public static void tick() {
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
            if (level.getGameTime() - b.lastSeen <= 10) return false;
            leaveWake(level, b);
            b.endVisual();
            return true;
        });
        for (Iterator<Trail> it = TRAILS.values().iterator(); it.hasNext(); ) {
            Trail t = it.next();
            if (level.getGameTime() - t.lastSeen > 20) {
                t.destroy(false);
                it.remove();
            }
        }
    }

    /** A registered particle type by raw id, if it can be played without extra options. */
    private static @Nullable ParticleOptions particle(int rawId) {
        if (rawId < 0) return null;
        return BuiltInRegistries.PARTICLE_TYPE.byId(rawId) instanceof SimpleParticleType s ? s : null;
    }

    /** {@code count} particles around {@code at}, within {@code spread} blocks; always visible, like any spell effect. */
    private static void scatter(Level level, ParticleOptions type, Vec3 at, int count, double spread) {
        RandomSource r = level.getRandom();
        for (int k = 0; k < count; k++) {
            double dx = spread > 0 ? r.nextGaussian() * spread / 2 : 0;
            double dy = spread > 0 ? r.nextGaussian() * spread / 2 : 0;
            double dz = spread > 0 ? r.nextGaussian() * spread / 2 : 0;
            level.addAlwaysVisibleParticle(type, true, at.x + dx, at.y + dy, at.z + dz, 0, 0, 0);
        }
    }

    /** Draws lasting beams, rays and what they leave, ward spheres, and the item models of spell bodies that wear a {@code :look}. */
    public static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ShaderPackOverlay.begin();
        Level level = mc.level;
        if (level == null || (TRAILS.isEmpty() && BEAMS.isEmpty() && RAYS.isEmpty() && !WardSpheres.any()
                && !dev.logos.runtime.ConstructEntity.any() && !AnchorMarkers.showing())) return;
        CameraRenderState cam = event.getLevelRenderState().cameraRenderState;
        Quaternionf toCamera = new Quaternionf(cam.orientation).conjugate();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector vfx = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        for (Beam b : BEAMS.values()) submitBeam(mc, level, b, cam, pt, pose,
                b.dedicated() ? vfx : event.getSubmitNodeCollector());
        ConstructRender.submit(mc, level, cam, pt, pose, event.getSubmitNodeCollector());
        WardSpheres.submit(mc, cam, pt, pose, vfx);
        for (LiveRay r : RAYS) r.visual().submit(level, cam, pt, pose, vfx);
        AnchorMarkers.submit(mc, cam, pt, pose, event.getSubmitNodeCollector());
        for (var e : TRAILS.entrySet()) {
            Trail t = e.getValue();
            if (t.visual != null) t.visual.submit(cam, level.getGameTime() + pt,
                    pose, vfx, t.look == null, t.particle == null);
            Look l = t.look;
            if (l == null) continue;
            Vector3f h = toCamera.transform(new Vector3f((float) l.heading.x, (float) l.heading.y, (float) l.heading.z));
            if (h.x * h.x + h.y * h.y > 0.04f) l.angle = (float) Math.atan2(h.y, h.x);
            if (Math.abs(h.x) > 0.2f) l.tumble = h.x > 0 ? -1 : 1;
            float turned = (float) ((level.getGameTime() - l.born + pt) / 20.0 * l.spin * Math.PI * 2) * l.tumble;
            Vec3 p = l.prev.lerp(l.cur, pt);
            pose.pushPose();
            pose.translate(p.x - cam.pos.x, p.y - cam.pos.y, p.z - cam.pos.z);
            pose.mulPose(cam.orientation);
            // Item sprites point up-right (+x +y); turn that diagonal onto the heading.
            pose.mulPose(Axis.ZP.rotation(l.angle + turned - (float) (Math.PI / 4)));
            pose.scale(l.scale, l.scale, l.scale);
            mc.getItemModelResolver().updateForTopItem(l.state, l.stack, ItemDisplayContext.NONE, level, null, e.getKey());
            l.state.submit(pose, event.getSubmitNodeCollector(), LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    /**
     * A beam follows the interpolated gaze, keeping the server's blocked length, but leaves the
     * same cast point as charging effects. The five damage elements use dedicated visuals; the remaining
     * elements keep the beacon-style core/glow, swelling from a thin outlet to their full radius.
     * A colossus's beam leaves its palm, where the model's muzzle bone was drawn this frame.
     */
    private static void submitBeam(Minecraft mc, Level level, Beam b, CameraRenderState cam, float pt, PoseStack pose, SubmitNodeCollector out) {
        Vec3 from = b.prevFrom.lerp(b.from, pt);
        Vec3 to = b.prevTo.lerp(b.to, pt);
        boolean firstPerson = false;
        Entity anchor = b.anchor >= 0 ? level.getEntity(b.anchor) : null;
        if (anchor instanceof AlchemicalColossus) {
            Vec3 muzzle = CastPoints.drawn(anchor);
            if (muzzle != null) from = muzzle;
        } else if (anchor != null) {
            double len = b.from.distanceTo(b.to);
            to = anchor.getEyePosition(pt).add(anchor.getViewVector(pt).scale(len));
            firstPerson = anchor == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
            from = CastPoints.of(anchor, pt);
        }
        // Its end races out at BEAM_REACH blocks a tick, then holds; only the picture, the server's reach is unchanged.
        double reach = (level.getGameTime() - b.born + pt) * BEAM_REACH, full = from.distanceTo(to);
        if (reach < full) to = from.lerp(to, reach / full);
        b.shownFrom = from;
        b.shownTo = to;
        if (b.fire != null) {
            b.fire.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.frost != null) {
            b.frost.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.lightning != null) {
            b.lightning.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.voidBeam != null) {
            b.voidBeam.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.physical != null) {
            b.physical.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.arcane != null) {
            b.arcane.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        if (b.life != null) {
            b.life.submit(level, from, to, b.radius, cam, pose, out);
            return;
        }
        Vec3 d = to.subtract(from);
        float len = (float) d.length();
        if (len < 0.05f) return;
        d = d.scale(1 / len);
        Palette pal = PALETTES.get(b.element);
        float r = b.radius;
        float taper = Math.min(len * 0.5f, firstPerson ? 2.5f : 1.2f);
        float startFrac = firstPerson ? 0.12f : 0.3f;
        float flow = ((level.getGameTime() % 200) + pt) * 0.08f;
        pose.pushPose();
        pose.translate(from.x - cam.pos.x, from.y - cam.pos.y, from.z - cam.pos.z);
        pose.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float) d.x, (float) d.y, (float) d.z));
        Tube core = new Tube(len, taper, startFrac, r * 0.4f, 0xFF000000 | pal.bright(), flow, false);
        Tube glow = new Tube(len, taper, startFrac, r, (0x70 << 24) | (pal.core() & 0xFFFFFF), flow * 0.6f, true);
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(BeaconRenderer.BEAM_LOCATION, false), core::render);
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(BeaconRenderer.BEAM_LOCATION, true), glow::render);
        pose.popPose();
    }

    /**
     * A round tube along local +Y from 0 to {@code len}, swelling from {@code startFrac × radius} to
     * {@code radius} over {@code taper}. {@code twoSided} also emits inward faces, for a glow seen from inside.
     */
    private record Tube(float len, float taper, float startFrac, float radius, int color, float flow, boolean twoSided) {
        private static final int SIDES = 10;
        private static final float[] RINGS = {0, 0.25f, 0.5f, 0.75f, 1};

        float radiusAt(float y) {
            float t = taper <= 0 ? 1 : Math.min(1, y / taper);
            t = t * t * (3 - 2 * t);
            return radius * (startFrac + (1 - startFrac) * t);
        }

        void render(PoseStack.Pose pose, VertexConsumer buf) {
            float[] ys = new float[RINGS.length + 1];
            for (int i = 0; i < RINGS.length; i++) ys[i] = RINGS[i] * taper;
            ys[RINGS.length] = len;
            for (int s = 0; s < ys.length - 1; s++) {
                float y0 = ys[s], y1 = ys[s + 1];
                if (y1 - y0 < 1e-4f) continue;
                float r0 = radiusAt(y0), r1 = radiusAt(y1);
                // The beacon texture repeats every block along the beam and scrolls away from the caster.
                float v0 = y0 - flow, v1 = y1 - flow;
                for (int k = 0; k < SIDES; k++) {
                    double a0 = Math.PI * 2 * k / SIDES, a1 = Math.PI * 2 * (k + 1) / SIDES;
                    float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                    float u0 = (float) k / SIDES, u1 = (float) (k + 1) / SIDES;
                    vertex(pose, buf, c0 * r1, y1, s0 * r1, u0, v1);
                    vertex(pose, buf, c0 * r0, y0, s0 * r0, u0, v0);
                    vertex(pose, buf, c1 * r0, y0, s1 * r0, u1, v0);
                    vertex(pose, buf, c1 * r1, y1, s1 * r1, u1, v1);
                    if (twoSided) {
                        vertex(pose, buf, c1 * r1, y1, s1 * r1, u1, v1);
                        vertex(pose, buf, c1 * r0, y0, s1 * r0, u1, v0);
                        vertex(pose, buf, c0 * r0, y0, s0 * r0, u0, v0);
                        vertex(pose, buf, c0 * r1, y1, s0 * r1, u0, v1);
                    }
                }
            }
        }

        private void vertex(PoseStack.Pose pose, VertexConsumer buf, float x, float y, float z, float u, float v) {
            buf.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        }
    }

    private static FXRuntime play(Level level, FX fx, Vec3 pos, float scale) {
        return play(level, fx, pos, scale, 1);
    }

    /**
     * {@code scale} scales the whole effect, particle size included; {@code extent} only scales the
     * emission shape (a field's radius), so a big field gets a bigger ring, not bigger particles.
     */
    private static FXRuntime play(Level level, FX fx, Vec3 pos, float scale, float extent) {
        FXRuntime runtime = fx.createRuntime();
        var root = runtime.getRoot();
        root.updatePos(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
        root.updateScale(new Vector3f(scale, scale, scale));
        runtime.emit(new Executor(level));
        // After emit(): emitting resets each emitter, which clears runtime overrides. Particles are
        // only spawned on the next tick, so the override is in place before the first one.
        if (extent != 1) {
            for (var object : runtime.objects.values()) {
                if (object instanceof ParticleEmitter e) e.runtime().shape.scale.set(new NumberFunction3(extent, extent, extent));
            }
        }
        return runtime;
    }

    private record Executor(Level level) implements IEffectExecutor {
        @Override
        public Level getLevel() {
            return level;
        }
    }

    private static boolean nearCamera(Vec3 pos) {
        var player = Minecraft.getInstance().player;
        return player != null && player.getEyePosition().distanceToSqr(pos) < 1.5 * 1.5;
    }

    /** Emissive vanilla particles of the element: cheap, bright and instantly recognisable. */
    private static void accents(Level level, Element el, Vec3 at, int count, double spread, double speed) {
        if (nearCamera(at)) return;
        ParticleOptions type = switch (el) {
            case FIRE -> ParticleTypes.FLAME;
            case FROST -> ParticleTypes.SNOWFLAKE;
            case LIGHTNING -> ParticleTypes.ELECTRIC_SPARK;
            case VOID -> ParticleTypes.REVERSE_PORTAL;
            case PHYSICAL -> ParticleTypes.CRIT;
            case ARCANE -> ParticleTypes.WITCH;
            case LIFE -> ParticleTypes.HAPPY_VILLAGER;
        };
        RandomSource r = level.getRandom();
        for (int k = 0; k < count; k++) {
            level.addParticle(type, at.x + r.nextGaussian() * spread, at.y + r.nextGaussian() * spread, at.z + r.nextGaussian() * spread,
                    r.nextGaussian() * speed, r.nextGaussian() * speed, r.nextGaussian() * speed);
        }
    }

    // ---- effect definitions ------------------------------------------------------------------

    private static FX fx(String kind, Element el, int variant, Supplier<List<ParticleEmitter>> build) {
        return CACHE.computeIfAbsent(kind + "/" + el + "/" + variant, k -> {
            FX fx = new FX();
            fx.getFxData().objects().addAll(build.get());
            return fx;
        });
    }

    static RandomConstant range(float min, float max) {
        return new RandomConstant(min, max);
    }

    static NumberFunction3 size(float min, float max) {
        return new NumberFunction3(range(min, max), range(min, max), range(min, max));
    }

    static Sphere sphere(float radius) {
        Sphere s = new Sphere();
        s.setRadius(radius);
        return s;
    }

    private static Circle circle() {
        Circle c = new Circle();
        c.setRadius(1);
        c.setRadiusThickness(0);
        return c;
    }

    /**
     * One emitter layer. {@code halo} layers blend additively at low alpha and are much larger;
     * core layers are opaque and fade from the palette's hot centre to the element colour.
     * {@code rate > 0} makes a looping emitter (trails); otherwise it bursts {@code count} once.
     */
    static ParticleEmitter layer(Element el, boolean halo, float rate, int count, NumberFunction3 size,
                                         RandomConstant speed, RandomConstant life, IShape shape) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        Palette p = PALETTES.get(el);
        int core = p.core & 0x00FFFFFF;
        if (halo) {
            c.setStartColor(NumberFunction.color(0x70000000 | core));
            c.colorOverLifetime.setEnable(true);
            c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0x00FFFFFF)));
            for (var m : c.renderer.getMaterials()) {
                m.getBlendMode().setSrcColorFactor(SourceFactor.SRC_ALPHA);
                m.getBlendMode().setDstColorFactor(DestFactor.ONE);
            }
        } else {
            c.setStartColor(NumberFunction.color(0xFFFFFFFF));
            c.colorOverLifetime.setEnable(true);
            c.colorOverLifetime.setColor(new Gradient(new GradientColor(p.bright, p.core, 0x00000000 | core)));
        }
        // Full-bright: unaffected by night or caves.
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setStartSize(size);
        c.setStartSpeed(speed);
        c.setStartLifetime(life);
        c.shape.setShape(shape);
        c.setDuration(20);
        if (rate > 0) {
            c.setLooping(true);
            c.emission.setEmissionRate(NumberFunction.constant(rate));
        } else {
            c.setLooping(false);
            c.emission.setEmissionRate(NumberFunction.constant(0));
            EmissionSetting.Burst b = new EmissionSetting.Burst();
            b.time = 0;
            b.setCount(NumberFunction.constant(count));
            c.emission.getBursts().add(b);
        }
        if (el == Element.FIRE) {
            c.physics.setEnable(true);
            c.physics.setGravity(NumberFunction.constant(-0.012f));
        } else if (el == Element.FROST) {
            c.physics.setEnable(true);
            c.physics.setGravity(NumberFunction.constant(0.008f));
        }
        return e;
    }

    private static List<ParticleEmitter> burst(Element el) {
        return List.of(
                layer(el, false, 0, 44, size(0.08f, 0.2f), range(0.04f, 0.15f), range(8, 16), sphere(0.2f)),
                layer(el, true, 0, 6, size(0.7f, 1.2f), range(0, 0.01f), range(6, 10), sphere(0.1f)),
                // sparks: tiny, fast, short
                layer(el, false, 0, 18, size(0.03f, 0.06f), range(0.2f, 0.35f), range(4, 8), sphere(0.1f)));
    }

    private static List<ParticleEmitter> execute() {
        return List.of(
                layer(Element.VOID, false, 0, 90, size(0.1f, 0.25f), range(0.08f, 0.22f), range(10, 20), sphere(0.3f)),
                layer(Element.VOID, true, 0, 12, size(1.2f, 2.0f), range(0, 0.02f), range(8, 14), sphere(0.2f)),
                layer(Element.VOID, false, 0, 30, size(0.04f, 0.08f), range(0.3f, 0.5f), range(6, 10), sphere(0.2f)));
    }

    /** The outline of a field; radius comes from the root scale. */
    private static List<ParticleEmitter> ring(Element el) {
        return List.of(
                layer(el, false, 0, 80, size(0.1f, 0.2f), range(0, 0.01f), range(12, 20), circle()),
                layer(el, true, 0, 24, size(0.5f, 0.8f), range(0, 0.005f), range(8, 14), circle()));
    }

    /** A field's heartbeat: a bloom filling its volume. */
    private static List<ParticleEmitter> pulse(Element el) {
        return List.of(
                layer(el, false, 0, 44, size(0.08f, 0.18f), range(0.01f, 0.04f), range(10, 16), sphere(1)),
                layer(el, true, 0, 10, size(0.8f, 1.3f), range(0, 0.01f), range(8, 12), sphere(0.8f)));
    }

    /** One bead of a ray; many beads in a row read as a beam. */
    private static List<ParticleEmitter> bead(Element el) {
        return List.of(
                layer(el, false, 0, 6, size(0.1f, 0.18f), range(0, 0.012f), range(6, 11), sphere(0.05f)),
                layer(el, true, 0, 1, size(0.45f, 0.65f), range(0, 0.002f), range(5, 8), sphere(0.02f)));
    }

    /** A mark being written: motes rising off the target. */
    private static List<ParticleEmitter> mark(Element el) {
        List<ParticleEmitter> layers = new ArrayList<>();
        ParticleEmitter motes = layer(el, false, 0, 16, size(0.06f, 0.12f), range(0.02f, 0.05f), range(10, 18), sphere(0.3f));
        motes.config.physics.setEnable(true);
        motes.config.physics.setGravity(NumberFunction.constant(-0.01f));
        layers.add(motes);
        layers.add(layer(el, true, 0, 3, size(0.5f, 0.7f), range(0, 0.005f), range(6, 10), sphere(0.1f)));
        return layers;
    }
}
