package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import dev.logos.lang.Weather;
import dev.logos.runtime.AreaWeather;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * What hangs in the sky over an area weather zone: the spell holding the weather up. A magic circle as wide as
 * the zone, its lines flowing with light, a smaller one turning against it, and a dark cloud vortex about
 * them, wheeling slowly; the bigger the zone the higher it hangs, from 20 blocks over the ground to cloud
 * height. Each weather adds one light touch: a ring of mist under rain, a flash at each bolt of a storm,
 * frost crystals for snow, a sun disc and a few shafts of light for a clear sky. It spreads out from the
 * centre as the zone rises and shrinks away when it ends. Client only, drawn from the zones the server
 * syncs, through {@link ShaderPackOverlay} like every other effect.
 */
final class AreaWeatherSky {
    /** Ticks to spread out, and to shrink away after the zone ends. */
    static final int OPEN = 24, CLOSE = 24;
    /** Lowest the circle hangs over the ground, for the smallest zone; the largest hangs at cloud height. */
    static final double LOW = 20;
    /** Beyond this (plus the zone's radius) a zone's sky is not drawn. */
    static final double REACH = 320;

    /** One zone's sky, eased between syncs and kept a moment after its zone is gone. */
    private static final class Sky {
        final int id;
        final Weather kind;
        double x, z, toX, toZ;
        final float radius;
        final long born;
        long ended = -1, flash = -100;

        Sky(AreaWeather.Zone zone, long now) {
            id = zone.id();
            kind = zone.kind();
            x = toX = zone.x();
            z = toZ = zone.z();
            radius = (float) zone.radius();
            born = now;
        }
    }

    private static final Map<Integer, Sky> SKIES = new HashMap<>();
    private static final Set<Integer> STRUCK = new HashSet<>();

    private AreaWeatherSky() {}

    static boolean any() {
        return !SKIES.isEmpty();
    }

    static void clear() {
        SKIES.clear();
        STRUCK.clear();
    }

    /** Follows the synced zones: new ones open, moved ones glide, vanished ones close; storms flash at each bolt. */
    static void tick(ClientLevel level) {
        long now = ClientCharges.ticks();
        Set<Integer> live = new HashSet<>();
        for (AreaWeather.Zone zone : AreaWeather.known(level)) {
            live.add(zone.id());
            Sky sky = SKIES.computeIfAbsent(zone.id(), id -> new Sky(zone, now));
            sky.toX = zone.x();
            sky.toZ = zone.z();
            sky.ended = -1;
        }
        for (Iterator<Sky> it = SKIES.values().iterator(); it.hasNext(); ) {
            Sky sky = it.next();
            if (!live.contains(sky.id) && sky.ended < 0) sky.ended = now;
            if (sky.ended >= 0 && now - sky.ended > CLOSE) it.remove();
            sky.x += (sky.toX - sky.x) * .35;
            sky.z += (sky.toZ - sky.z) * .35;
        }
        boolean storms = false;
        for (Sky sky : SKIES.values()) storms |= sky.kind == Weather.THUNDER && sky.ended < 0;
        if (!storms) {
            STRUCK.clear();
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LightningBolt)) continue;
            seen.add(e.getId());
            if (!STRUCK.add(e.getId())) continue;
            for (Sky sky : SKIES.values()) {
                double dx = e.getX() - sky.x, dz = e.getZ() - sky.z;
                if (sky.kind == Weather.THUNDER && dx * dx + dz * dz <= sky.radius * sky.radius * 1.2) sky.flash = now;
            }
        }
        STRUCK.retainAll(seen);
    }

    static void submit(Level level, CameraRenderState camera, float pt, PoseStack pose, SubmitNodeCollector out) {
        if (SKIES.isEmpty()) return;
        View view = View.of(camera);
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : pt);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (Sky sky : SKIES.values()) draw(level, sky, view, now, pose, out);
        pose.popPose();
    }

    /** How high the sky of a zone of {@code radius} hangs over ground at {@code ground}, under clouds at {@code clouds}. */
    static double height(double ground, double clouds, float radius) {
        double k = smooth(1, 48, radius);
        return Math.max(ground + LOW, Mth.lerp(k, ground + LOW, clouds));
    }

    private static void draw(Level level, Sky sky, View view, double now, PoseStack pose, SubmitNodeCollector out) {
        Vec3 ground = new Vec3(sky.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(sky.x), Mth.floor(sky.z)), sky.z);
        double clouds = level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_HEIGHT, ground);
        Vec3 c = new Vec3(sky.x, height(ground.y, clouds, sky.radius), sky.z);
        if (view.cam().distanceTo(c) > REACH + sky.radius) return;
        float age = (float) (now - sky.born), end = sky.ended < 0 ? 0 : (float) (now - sky.ended);
        float open = 1 - (float) Math.pow(1 - Mth.clamp(age / OPEN, 0, 1), 3);
        float close = 1 - smooth(0, CLOSE, end);
        float R = sky.radius * open * (.6f + .4f * close);
        float a = Mth.clamp(age / 6, 0, 1) * close;
        if (R <= .05f || a <= .01f) return;
        Look look = Look.of(sky.kind);
        float flash = (float) Math.exp(-(now - sky.flash) / 4);

        // The cloud vortex, above the circle and wider than it: two dark spirals wheeling against each other,
        // and a broad ring of heavy puffs drawn round by them.
        if (look.cloud != 0) {
            float wheel = (float) (now * .0025);
            out.submitCustomGeometry(pose, ChargeParts.blend("heal/swirl"), (p, buf) -> {
                ChargeParts.flat(p, buf, c.add(0, 1.6, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R * 1.75f, wheel, argb(.9f * a, look.cloud));
                ChargeParts.flat(p, buf, c.add(0, 1.2, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R * 1.3f, -wheel * 1.7f + 1, argb(.85f * a, look.cloud));
            });
            int puffs = Mth.clamp(Math.round(R * 2.4f), 16, 72);
            out.submitCustomGeometry(pose, ChargeParts.blend("fire_beam/smoke_twirly"), (p, buf) -> {
                for (int i = 0; i < puffs; i++) {
                    double r = R * (.75 + .85 * hash(i * 11 + 3));
                    // The inner puffs go round faster, as in a vortex.
                    double phi = Math.PI * 2 * i / puffs + wheel * (2.4 - r / R) + hash(i * 5 + sky.id) * .4;
                    Vec3 at = c.add(Math.sin(phi) * r, .4 + 2 * hash(i * 7 + 1), -Math.cos(phi) * r);
                    float size = (float) (Math.max(2, R * .45) * (.7 + .6 * hash(i * 3 + 9)));
                    ChargeParts.frame(p, buf, view, at, size, (float) (hash(i) * 6 + now * .002), i % 4, 2, 2, argb(.8f * a, look.cloud));
                }
            });
        }

        // The circles: the zone's width, lines flowing with light; a smaller one inside turning against it.
        int tint = lerpColor(look.main, look.light, .25f + .5f * flash);
        float bright = look.gain * (.75f + .9f * flash);
        out.submitCustomGeometry(pose, ChargeParts.add("heal/glow"), (p, buf) ->
                ChargeParts.flat(p, buf, c.add(0, -.05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R * 1.2f, 0, argb((.18f + .35f * flash) * look.gain * a, look.deep)));
        out.submitCustomGeometry(pose, FieldParts.lines(look.outer, "core/field_line", "ward/noise"), (p, buf) ->
                FieldParts.circle(p, buf, c, R, (float) (now * .003), argb(Math.min(1, .7f * bright) * a, tint), FieldParts.NO_SWEEP, 1 - close));
        out.submitCustomGeometry(pose, FieldParts.lines(look.inner, "core/field_line", "ward/noise"), (p, buf) ->
                FieldParts.circle(p, buf, c.add(0, -.1, 0), R * .5f, (float) (-now * .008), argb(Math.min(1, .85f * bright) * a,
                        lerpColor(look.main, look.light, sky.kind == Weather.CLEAR ? .1f : .6f)), FieldParts.NO_SWEEP, 1 - close));

        switch (sky.kind) {
            case RAIN -> {
                // A thin ring of mist at the rim, and ripples spreading over the circle like drops on a pond.
                out.submitCustomGeometry(pose, ChargeParts.add("heal/halo"), (p, buf) ->
                        ChargeParts.flat(p, buf, c.add(0, -.4, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R * 1.12f, (float) (now * .002), argb(.22f * a, look.light)));
                ripples(c, R, now, a, look, pose, out);
            }
            case THUNDER -> arcs(view, c, R, now, a, flash, look, pose, out);
            case SNOW -> flakes(view, c, R, now, a, look, pose, out);
            case CLEAR -> {
                corona(c, R, now, a, look, pose, out);
                shafts(view, c, ground.y, R, now, a, look, pose, out);
            }
        }
    }

    /** Rain: rings spreading from random spots over the circle, one after another, each a drop landing. */
    private static void ripples(Vec3 c, float R, double now, float a, Look look, PoseStack pose, SubmitNodeCollector out) {
        final int life = 30, drops = Mth.clamp(Math.round(R * .5f), 4, 16);
        out.submitCustomGeometry(pose, ChargeParts.add("heal/ring"), (p, buf) -> {
            for (int i = 0; i < drops; i++) {
                // Each drop repeats every `life` ticks, out of step with the others, landing somewhere new each time.
                double t = now + hash(i * 31) * life;
                int round = (int) Math.floor(t / life);
                float k = (float) (t / life - round);
                int seed = i * 97 + round * 13;
                double phi = hash(seed) * Math.PI * 2, r = R * .9 * Math.sqrt(hash(seed + 1));
                Vec3 at = c.add(Math.sin(phi) * r, -.2, -Math.cos(phi) * r);
                ChargeParts.flat(p, buf, at, new Vec3(1, 0, 0), new Vec3(0, 0, 1), (float) Math.max(.8, R * .3) * (.15f + k), 0,
                        argb((1 - k) * smooth(0, .15f, k) * .75f * a, look.light));
            }
        });
    }

    /** Thunder: short arcs jumping between points of the rim, each flickering for a few ticks; more of them at a bolt. */
    private static void arcs(View view, Vec3 c, float R, double now, float a, float flash, Look look, PoseStack pose, SubmitNodeCollector out) {
        int tick = (int) now, n = Mth.clamp(Math.round(R * .25f), 2, 8) + Math.round(6 * flash);
        out.submitCustomGeometry(pose, ChargeParts.add("arcane_ray/electric"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                // A new arc every 3 ticks per slot, and only some slots lit at a time.
                int seed = i * 59 + (tick / 3) * 7;
                if (hash(seed + 2) > .75 + .25 * flash) continue;
                double phi = hash(seed) * Math.PI * 2, span = .25 + .5 * hash(seed + 1);
                Vec3 from = c.add(Math.sin(phi) * R * .98, -.15, -Math.cos(phi) * R * .98);
                Vec3 to = c.add(Math.sin(phi + span) * R * .98, -.15, -Math.cos(phi + span) * R * .98);
                ChargeParts.streak(p, buf, view, from, to, (float) Math.max(.4, R * .05), argb((.55f + .45f * flash) * a, look.light));
            }
        });
    }

    /** Snow: flakes glinting as they drift slowly down from under the circle and fade a few blocks below it. */
    private static void flakes(View view, Vec3 c, float R, double now, float a, Look look, PoseStack pose, SubmitNodeCollector out) {
        final double fall = 6, life = 120;
        int n = Mth.clamp(Math.round(R * 1.5f), 10, 60);
        out.submitCustomGeometry(pose, ChargeParts.add("arcane_ray/glint"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double t = now + hash(i * 41) * life;
                int round = (int) Math.floor(t / life);
                float k = (float) (t / life - round);
                int seed = i * 71 + round * 17;
                double phi = hash(seed) * Math.PI * 2, r = R * Math.sqrt(hash(seed + 1));
                Vec3 at = c.add(Math.sin(phi) * r + Math.sin(now * .03 + i) * .4, -.3 - fall * k, -Math.cos(phi) * r);
                float twinkle = .6f + .4f * Mth.sin((float) (now * .15 + i * 2.7));
                ChargeParts.sprite(p, buf, view, at, (float) Math.max(.18, R * .02), (float) (now * .02 + i),
                        argb(smooth(0, .1f, k) * (1 - smooth(.6f, 1, k)) * twinkle * .9f * a, look.light));
            }
        });
    }

    /**
     * Clear: a ring of sun rays behind the disc, turning slowly, and the disc's pattern laid in gold paint
     * under its glowing lines (added light alone turns gold to white against a blue sky).
     */
    private static void corona(Vec3 c, float R, double now, float a, Look look, PoseStack pose, SubmitNodeCollector out) {
        out.submitCustomGeometry(pose, ChargeParts.blend(look.outer), (p, buf) ->
                ChargeParts.flat(p, buf, c.add(0, .15, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R, (float) (now * .003), argb(.75f * a, look.deep)));
        out.submitCustomGeometry(pose, ChargeParts.add("arcane_ray/impact_rays"), (p, buf) ->
                ChargeParts.flat(p, buf, c.add(0, .3, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), R * 1.5f, (float) (now * .0015), argb(.16f * a, look.main)));
    }

    /** A few faint shafts of sunlight falling from the circle to the ground, swaying a little. */
    private static void shafts(View view, Vec3 c, double ground, float R, double now, float a, Look look, PoseStack pose, SubmitNodeCollector out) {
        int n = Mth.clamp(Math.round(2 + R * .15f), 3, 7);
        out.submitCustomGeometry(pose, ChargeParts.add("force/streak"), (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double phi = Math.PI * 2 * (i + hash(i * 17)) / n + now * .0008;
                double r = R * (.15 + .45 * hash(i * 23 + 4));
                Vec3 top = c.add(Math.sin(phi) * r, -.5, -Math.cos(phi) * r);
                Vec3 foot = new Vec3(top.x, ground + .5, top.z);
                float w = (float) Math.max(.6, R * .06) * (.8f + .4f * Mth.sin((float) (now * .03 + i * 2.1)));
                float glow = .5f + .5f * Mth.sin((float) (now * .02 + i * 1.3));
                ChargeParts.streak(p, buf, view, foot, top, w, argb((.06f + .07f * glow) * a, look.light));
            }
        });
    }

    /** A weather's colours, circles and cloud shade (0 for no cloud). */
    /** {@code gain} damps the additive layers where they would wash out to white (a sun disc against a bright sky). */
    private record Look(int light, int main, int deep, String outer, String inner, int cloud, float gain) {
        static Look of(Weather kind) {
            return switch (kind) {
                case RAIN -> new Look(0xD8F4FF, 0x3AA6E8, 0x1A4C9A, "charge/circle_03", "charge/circle_11", 0x3A424E, 1);
                case THUNDER -> new Look(0xF4ECFF, 0x8C70FF, 0x3A20B0, "lightning_field/circle_01", "charge/circle_10", 0x24202E, 1);
                case SNOW -> new Look(0xF4FFFF, 0x9ADCFF, 0x4A8CD0, "frost_field/circle_24", "frost_charge/circle_23", 0xB8C4D0, 1);
                case CLEAR -> new Look(0xFFF6D0, 0xFFC840, 0xC07A10, "fire_charge/sun_circle", "charge/circle_28", 0, .5f);
            };
        }
    }

    private static double hash(int i) {
        double x = Math.sin(i * 12.9898 + 78.233) * 43758.5453;
        return x - Math.floor(x);
    }
}
