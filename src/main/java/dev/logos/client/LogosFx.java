package dev.logos.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;

import dev.logos.LogosConfig;
import dev.logos.client.FxLive.Beam;
import dev.logos.client.FxLive.LiveRay;
import dev.logos.client.FxLive.Look;
import dev.logos.client.FxLive.SetOut;
import dev.logos.client.FxLive.Trail;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Vfx;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
 * <p>
 * This class is the entry point: it receives the server's effect packets and plays them. What lasts between
 * packets is kept in {@link FxLive} and drawn each frame by {@link FxRender}; the palette and the emitter
 * layers are in {@link FxLayers}.
 */
public final class LogosFx {
    /** The element's colour (opaque RGB), for other renderers that follow the same palette. */
    static int coreColor(Element e) {
        return FxLayers.coreColor(e);
    }

    private static final Map<String, FX> CACHE = new HashMap<>();
    // The tables of lasting effects are FxLive's. The client scenes (client/uitest) read these three by name
    // through reflection, so the names stay here, on the same tables.
    private static final Map<Integer, Trail> TRAILS = FxLive.TRAILS;
    private static final Map<Integer, Beam> BEAMS = FxLive.BEAMS;
    private static final List<LiveRay> RAYS = FxLive.RAYS;
    private static final FxBudget BUDGET = new FxBudget();
    /** The last ray or link that arrived (the client scenes check it). */
    public record FiredRay(Element element, Vfx.RayEnd end) {}
    private static @Nullable FiredRay lastRay;

    private static boolean effect() {
        return BUDGET.effect(LogosConfig.FX_EFFECTS_PER_TICK.get());
    }

    private static int particles(int wanted) {
        return BUDGET.particles(wanted, LogosConfig.FX_PARTICLES_PER_TICK.get());
    }

    /** How far each lasting beam has reached out by now, growing to its full length (the client scenes check it). */
    public static List<Double> drawnBeamLengths() {
        return FxLive.drawnBeamLengths();
    }

    private LogosFx() {}

    // ---- playback ----------------------------------------------------------------------------

    /** One tick's effects from the server, in the order they happened. */
    public static void handle(FxPayload.Batch batch) {
        batch.effects().forEach(LogosFx::handle);
    }

    public static void handle(FxPayload p) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        // One-shots past this tick's budget are skipped; nothing keeps track of them. Lasting effects have caps instead.
        switch (p.kindEnum()) {
            case BURST, PROJECTILE_IMPACT, CAST, EXECUTE, MARK -> {
                if (!effect()) return;
            }
            default -> {}
        }
        Element el = Element.values()[Math.floorMod(p.element(), Element.values().length)];
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        switch (p.kindEnum()) {
            case TRAIL_FROM -> FxLive.SET_OUT.put(p.id(), new SetOut(p.look(), pos, level.getGameTime()));
            case TRAIL -> {
                Trail t = FxLive.TRAILS.get(p.id());
                Vec3 real = pos;
                if (t == null) {
                    // Full up, it goes undrawn until there is room; its end finds nothing to end, which is safe.
                    if (FxLive.TRAILS.size() >= LogosConfig.FX_MAX_TRAILS.get()) return;
                    SetOut from = FxLive.SET_OUT.remove(p.id());
                    Entity caster = from != null ? level.getEntity(from.caster()) : null;
                    // A body cast from the eye starts inside the camera: drawn from the cast point instead, or not until it is a little way out.
                    if (caster == null && nearCamera(pos)) return;
                    Vec3 offset = caster != null ? CastPoints.of(caster, 1).subtract(from.eye()) : Vec3.ZERO;
                    pos = real.add(offset);
                    t = new Trail(particle(p.particle()), Look.of(p, pos, level, level.getGameTime()), pos, level.getGameTime());
                    t.offset = offset;
                    FxLive.TRAILS.put(p.id(), t);
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
                    int n = particles((int) Math.max(1, Math.min(12, Math.ceil(t.last.distanceTo(pos) * 3))));
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
                Trail t = FxLive.TRAILS.remove(p.id());
                if (t != null) t.destroy(false);
                ProjectileImpactVisual impact = ProjectileImpactVisual.create(el);
                List<ParticleEmitter> expiry = impact.expiry();
                if (t != null && t.particle != null) {
                    // A custom trail ends in a puff of its own particle rather than the element's burst.
                    scatter(level, t.particle, pos, 10, 0.4);
                } else if (!expiry.isEmpty()) {
                    if (p.variant() == 0 && effect()) play(level, fx("projectile_expiry", el, 0, () -> expiry), pos, 1);
                } else if (effect()) {
                    play(level, fx("burst", el, 0, () -> FxLayers.burst(el)), pos, 1);
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
                Beam b = FxLive.BEAMS.get(p.id());
                if (b == null) {
                    // Full up, it goes undrawn until there is room; its end finds nothing to end, which is safe.
                    if (FxLive.BEAMS.size() >= LogosConfig.FX_MAX_BEAMS.get()) return;
                    b = new Beam(el, p.look(), particle(p.particle()), pos, to, level.getGameTime());
                    FxLive.BEAMS.put(p.id(), b);
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
                    if (b.particle != null && particles(1) > 0) level.addParticle(b.particle, at.x, at.y, at.z, 0, 0, 0);
                    else if (b.particle == null && !b.dedicated()) accents(level, el, at, 1, 0.05, 0.02);
                }
                if (!b.dedicated() && r.nextInt(3) == 0) accents(level, el, to, 2, 0.15, 0.08);
            }
            case BEAM_END -> {
                Beam b = FxLive.BEAMS.remove(p.id());
                if (b != null) FxLive.leaveWake(level, b);
                if (b != null && b.dedicated()) {
                    b.endVisual();
                    if (b.particle != null) scatter(level, b.particle, pos, 10, 0.4);
                    break;
                }
                Element e = b != null ? b.element : el;
                if (b != null && b.particle != null) scatter(level, b.particle, pos, 10, 0.4);
                else if (effect()) play(level, fx("burst", e, 0, () -> FxLayers.burst(e)), pos, 0.8f);
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
                play(level, fx("burst", el, 0, () -> FxLayers.burst(el)), pos, Math.max(0.7f, Math.min(1.3f, p.size())));
                accents(level, el, pos, 8 + (int) (p.size() * 6), 0.3, 0.12);
            }
            // A small circle at the caster's feet: the "magic circle" of the invocation.
            case CAST -> play(level, fx("ring", el, 0, () -> FxLayers.ring(el)), pos, 0.8f, 0.9f);
            case EXECUTE -> {
                play(level, fx("execute", Element.VOID, 0, FxLayers::execute), pos, 1.4f);
                play(level, fx("ring", Element.VOID, 0, () -> FxLayers.ring(Element.VOID)), pos.add(0, -0.8, 0), 1, 2.5f);
                accents(level, Element.VOID, pos, 40, 0.6, 0.25);
            }
            case MARK -> play(level, fx("mark", el, 0, () -> FxLayers.mark(el)), pos, 0.8f);
            case RING -> legacyRing(level, el, pos, p.size());
            case PULSE -> legacyPulse(level, el, pos, p.size());
            // A field: its element's own visual if it draws one, otherwise the old particle ring every reminder and burst on each pulse.
            case FIELD_HOLD -> {
                FieldVisuals.hold(p.id(), el, pos, p.size(), (int) p.ax(), (int) p.ay(), (int) p.az(), p.look());
                if (!FieldVisuals.drawsOwn(el)) legacyRing(level, el, pos.add(0, -0.8, 0), p.size());
            }
            case FIELD_PULSE -> {
                FieldVisuals.pulse(p.id());
                if (!FieldVisuals.drawsOwn(el)) legacyPulse(level, el, pos.add(0, -0.5, 0), p.size());
            }
            case FIELD_ENTER -> FieldVisuals.enter(p.id(), pos, p.look());
            case FIELD_END -> FieldVisuals.ended(p.id());
            case VEIN_HOLD -> VeinVisuals.hold(p.id(), p.variant(), el, pos, new Vec3(p.ax(), p.ay(), p.az()),
                    p.look() >= 0 && p.look() < 6 ? net.minecraft.core.Direction.from3DDataValue(p.look()) : net.minecraft.core.Direction.UP, p.size(), p.spin(),
                    Math.max(0, p.particle()));
            case VEIN_END -> VeinVisuals.ended(p.id(), p.variant(), pos, p.size() > 0);
            case VEIN_FADE -> VeinVisuals.faded(p.id());
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
                    FxLive.addRay(new LiveRay(ray, level.getGameTime()));
                    break;
                }
                double len = pos.distanceTo(end);
                int beads = (int) Math.max(3, Math.min(80, len * 5));
                FX bead = fx("beam", el, 0, () -> FxLayers.bead(el));
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
        return FxLive.drawnTrails();
    }

    /** The last ray or link that arrived, or null (the client scenes check it). */
    public static @Nullable FiredRay lastRay() {
        return lastRay;
    }

    /** How many element-drawn rays still have something showing (the client scenes check it). */
    public static int liveRays() {
        return FxLive.liveRays();
    }

    /** Ends every ray and beam wake still showing, so a client scene can sample a fresh one. */
    public static void clearRays() {
        FxLive.clearRays();
    }

    /** The old field look: a particle ring of radius {@code size} with a few accents round its rim. */
    private static void legacyRing(Level level, Element el, Vec3 pos, float size) {
        if (!effect()) return;
        play(level, fx("ring", el, 0, () -> FxLayers.ring(el)), pos, 1, size);
        RandomSource r = level.getRandom();
        for (int k = 0; k < (int) (6 + size * 4); k++) {
            double a = r.nextDouble() * Math.PI * 2;
            accents(level, el, pos.add(Math.cos(a) * size, 0.1, Math.sin(a) * size), 1, 0.05, 0.02);
        }
    }

    /** The old field pulse: a particle burst of radius {@code size}. */
    private static void legacyPulse(Level level, Element el, Vec3 pos, float size) {
        if (!effect()) return;
        play(level, fx("pulse", el, 0, () -> FxLayers.pulse(el)), pos, 1, size);
        accents(level, el, pos, (int) (4 + size * 4), size * 0.6, 0.03);
    }

    /** Drops trails whose spell body stopped reporting (e.g. it left tracking range). */
    public static void tick() {
        BUDGET.refill();
        FxLive.tick();
    }

    /** A registered particle type by raw id, if it can be played without extra options. */
    private static @Nullable ParticleOptions particle(int rawId) {
        if (rawId < 0) return null;
        return BuiltInRegistries.PARTICLE_TYPE.byId(rawId) instanceof SimpleParticleType s ? s : null;
    }

    /** {@code count} particles around {@code at}, within {@code spread} blocks; always visible, like any spell effect. */
    private static void scatter(Level level, ParticleOptions type, Vec3 at, int count, double spread) {
        RandomSource r = level.getRandom();
        count = particles(count);
        for (int k = 0; k < count; k++) {
            double dx = spread > 0 ? r.nextGaussian() * spread / 2 : 0;
            double dy = spread > 0 ? r.nextGaussian() * spread / 2 : 0;
            double dz = spread > 0 ? r.nextGaussian() * spread / 2 : 0;
            level.addAlwaysVisibleParticle(type, true, at.x + dx, at.y + dy, at.z + dz, 0, 0, 0);
        }
    }

    /** Draws lasting beams, rays and what they leave, ward spheres, wand charges, fields, the sky over area weather, and the item models of spell bodies that wear a {@code :look}. */
    public static void submit(SubmitCustomGeometryEvent event) {
        FxRender.submit(event);
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
    static void accents(Level level, Element el, Vec3 at, int count, double spread, double speed) {
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
        count = particles(count);
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

    // The definitions themselves are in FxLayers. These four stay for the element visuals that build their own
    // effects from the same layers.
    static RandomConstant range(float min, float max) {
        return FxLayers.range(min, max);
    }

    static NumberFunction3 size(float min, float max) {
        return FxLayers.size(min, max);
    }

    static Sphere sphere(float radius) {
        return FxLayers.sphere(radius);
    }

    /**
     * One emitter layer. {@code halo} layers blend additively at low alpha and are much larger;
     * core layers are opaque and fade from the palette's hot centre to the element colour.
     * {@code rate > 0} makes a looping emitter (trails); otherwise it bursts {@code count} once.
     */
    static ParticleEmitter layer(Element el, boolean halo, float rate, int count, NumberFunction3 size,
                                         RandomConstant speed, RandomConstant life, IShape shape) {
        return FxLayers.layer(el, halo, rate, count, size, speed, life, shape);
    }
}
