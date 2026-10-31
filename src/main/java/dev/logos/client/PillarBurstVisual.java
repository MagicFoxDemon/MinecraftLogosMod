package dev.logos.client;

import static dev.logos.client.VfxParts.argb;
import static dev.logos.client.VfxParts.smooth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.VfxParts.View;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A golden pillar of light bursting up from a point into the sky (after NiagaeaBigVFXPack's NS_FlamePillar and
 * NS_FlamePillar_Stone; FORM_VFX.md): concentric layers of bright veins and dark cells shoot 64 blocks up in six
 * ticks, hold for about a second and a half, then narrow to a line against the ground and go out. At its foot: a
 * ground glow and turning rings, two shockwaves along the ground and ripples spreading on, two rings of flame
 * tongues flung from the base, streaks rushing up inside and motes rising round it, dust thrown up (tinted by the
 * ground block's map colour) and debris wearing the surrounding blocks' own textures. Client only, sized by
 * {@code scale}, about four seconds in all.
 */
public final class PillarBurstVisual {
    public static final int LIFE = 84;
    private static final int WHITE = 0xFFF4D0, LIGHT = 0xFFD873, MAIN = 0xFFB52E, DEEP = 0xE08A1A, DARK = 0x2B1606;
    /** Rises for RISE ticks; holds until HOLD; then narrows, down to a line at LINE; out at GONE. */
    private static final float RADIUS = 1.6f, HEIGHT = 64, RISE = 6, HOLD = 42, LINE = 66, GONE = 74;
    /**
     * How many blocks one tile of the veins texture spans round the pillar (the cells layer has its own), and how
     * much taller it is than wide. Laid out by world size, so a taller pillar does not stretch it.
     */
    private static final float TILE = 3.2f, TALL = 1.35f;

    /**
     * One layer of the pillar: material, radius factor, alpha, rush speed (blocks per tick), colour, the tile's
     * width in blocks and height factor, and how much more the tile is stretched the higher it is ({@code stretch},
     * 0 for none). As in M_FlamePillar_main, bright veins alternate with opaque dark cells (main1's dark red-brown),
     * which hide the bright layers behind them and make the rush read faster.
     */
    private record Layer(net.minecraft.client.renderer.rendertype.RenderType material, float radius, float alpha, float speed, int rgb,
            float tile, float tall, float stretch) {
        Layer(net.minecraft.client.renderer.rendertype.RenderType material, float radius, float alpha, float speed, int rgb) {
            this(material, radius, alpha, speed, rgb, TILE, TALL, 0);
        }
    }

    /** The power the stretch grows with height by: the bottom third barely changes, the top steepest. */
    private static final float STRETCH_POWER = 3;
    /** How many bands a stretched layer is cut into for its UVs. */
    private static final int BANDS = 24;

    /**
     * Drawn in this order (one order per layer, see {@link VfxParts#layered}). The dark cells go first and the
     * inner additive veins over them, washing the cells out to scattered amber patches (the approved look); the
     * golden veil goes over the outside.
     */
    private static final Layer[] LAYERS = {
            // The cells are laid larger and taller: in UE they are much coarser than the veins. Stretched more the
            // higher they are, nearly to a line at the top.
            new Layer(VfxParts.blend("pillar_burst/cells"), 1, .75f, 1.4f, DARK, 4.5f, 1.8f, 40),
            new Layer(VfxParts.add("pillar_burst/veins"), .45f, .55f, 2.2f, WHITE),
            new Layer(VfxParts.add("charge/pillar"), .25f, .35f, 2.6f, WHITE),
            new Layer(VfxParts.add("pillar_burst/veins"), .85f, .75f, 1.7f, MAIN),
            // The daytime sky is bright and washes additive glow to white: a translucent golden veil over the
            // bright layers tints it back.
            new Layer(VfxParts.blend("charge/pillar"), 1.06f, .45f, 1.2f, MAIN),
            new Layer(VfxParts.add("pillar_burst/veins"), 1.18f, .6f, 1.2f, MAIN),
            new Layer(VfxParts.add("charge/pillar"), 1.6f, .16f, .8f, DEEP)};

    private record Spark(float born, float speed, float radius, float angle, float length) {}

    /** A flame tongue flung from the base: its heading round the pillar, tilt from vertical, length, width, first frame, flicker phase and outward drift. */
    private record Flare(float angle, float tilt, float length, float width, int frame, float phase, float drift) {}

    /** Flame tongues: flung out (ticks), held until, gone. */
    private static final float FLARE_OUT = 4, FLARE_HOLD = 10, FLARE_GONE = 58;

    /** A piece of debris or a puff of dust, with its previous position to interpolate from. */
    private static final class Bit {
        Vec3 pos, prev, vel;
        final float size, life, born;
        final Quaternionf turn = new Quaternionf(), spin;
        final TextureAtlasSprite sprite;
        final int tint;
        float landed = -1;

        Bit(Vec3 pos, Vec3 vel, float size, float life, float born, Quaternionf spin, TextureAtlasSprite sprite, int tint) {
            this.pos = this.prev = pos;
            this.vel = vel;
            this.size = size;
            this.life = life;
            this.born = born;
            this.spin = spin;
            this.sprite = sprite;
            this.tint = tint;
        }
    }

    private final Vec3 base;
    private final float scale;
    private final RandomSource random;
    private final int seed;
    private final List<Spark> sparks = new ArrayList<>(), motes = new ArrayList<>();
    private final List<Bit> rocks = new ArrayList<>(), dust = new ArrayList<>();
    private final List<Flare> flares = new ArrayList<>();
    private int age;

    PillarBurstVisual(Level level, Vec3 base, float scale, long seed) {
        this.base = base;
        this.scale = scale;
        this.random = RandomSource.create(seed);
        this.seed = (int) (seed ^ seed >>> 32);
        for (int i = 0; i < 48; i++) {
            sparks.add(new Spark(random.nextFloat() * 38, 1.8f + 1.8f * random.nextFloat(), RADIUS * .9f * Mth.sqrt(random.nextFloat()),
                    random.nextFloat() * Mth.TWO_PI, 1.6f + 2.2f * random.nextFloat()));
        }
        for (int i = 0; i < 30; i++) {
            motes.add(new Spark(random.nextFloat() * 34, .12f + .18f * random.nextFloat(), RADIUS * (1.4f + 2.6f * random.nextFloat()),
                    random.nextFloat() * Mth.TWO_PI, .05f + .07f * random.nextFloat()));
        }
        int ground = VfxParts.groundTint(level, base);
        for (int i = 0; i < 20; i++) {
            float a = random.nextFloat() * Mth.TWO_PI, r = RADIUS * (1.1f + 1.2f * random.nextFloat()) * scale;
            Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
            dust.add(new Bit(base.add(out.scale(r)).add(0, .3 * scale, 0), out.scale((.18 + .2 * random.nextFloat()) * scale).add(0, .03 * scale, 0),
                    .7f + .5f * random.nextFloat(), 40 + random.nextInt(24), random.nextInt(4), null, null, ground));
        }
        for (int i = 0; i < 40; i++) rock(level, i < 30 ? 0 : random.nextInt(4));
        // The upper ring opens up and out like a crown, the lower one sprays out nearly along the ground.
        for (int i = 0; i < 16; i++) {
            flares.add(new Flare((i + .6f * random.nextFloat()) / 16 * Mth.TWO_PI, (35 + 20 * random.nextFloat()) * Mth.DEG_TO_RAD,
                    3 + 2 * random.nextFloat(), 1.6f + .8f * random.nextFloat(), random.nextInt(4), random.nextFloat() * 6, .02f + .02f * random.nextFloat()));
        }
        for (int i = 0; i < 12; i++) {
            flares.add(new Flare((i + .3f + .6f * random.nextFloat()) / 12 * Mth.TWO_PI, (70 + 12 * random.nextFloat()) * Mth.DEG_TO_RAD,
                    3 + 2 * random.nextFloat(), 2 + .8f * random.nextFloat(), random.nextInt(4), random.nextFloat() * 6, .04f + .03f * random.nextFloat()));
        }
    }

    /** Throws up a piece of debris: a ground block round the pillar, in its own texture; air and specially rendered blocks throw none. */
    private void rock(Level level, int born) {
        float a = random.nextFloat() * Mth.TWO_PI, r = RADIUS * (.6f + 1.9f * random.nextFloat()) * scale;
        Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
        Vec3 at = base.add(out.scale(r)).add(0, .15, 0);
        TextureAtlasSprite sprite = VfxParts.blockSprite(level, at.x, base.y, at.z);
        if (sprite == null) return;
        // Thrown up in a cone of about 35° (NS_FlamePillar_Stone's Cone Angle 35.2).
        double up = (.45 + .5 * random.nextDouble()) * scale, side = (.1 + .3 * random.nextDouble()) * scale;
        Quaternionf spin = new Quaternionf().rotateXYZ((random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f);
        Bit b = new Bit(at, out.scale(side).add(0, up, 0), (.07f + .11f * random.nextFloat()) * scale, 46 + random.nextInt(14), born, spin, sprite, 0xFFFFFF);
        b.turn.rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
        rocks.add(b);
    }

    /** One client tick; false once it has played out. */
    boolean tick() {
        age++;
        for (Bit b : rocks) {
            b.prev = b.pos;
            if (age < b.born || b.landed >= 0) continue;
            b.vel = new Vec3(b.vel.x * .98, b.vel.y - .055 * scale, b.vel.z * .98);
            b.pos = b.pos.add(b.vel);
            b.turn.mul(b.spin);
            if (b.vel.y < 0 && b.pos.y <= base.y + b.size) {
                b.pos = new Vec3(b.pos.x, base.y + b.size, b.pos.z);
                b.prev = b.pos;
                b.landed = age;
            }
        }
        for (Bit d : dust) {
            d.prev = d.pos;
            if (age < d.born) continue;
            d.vel = d.vel.multiply(.9, .96, .9);
            d.pos = d.pos.add(d.vel);
        }
        return age < LIFE;
    }

    /** {@code layered}: where the pillar's layers go in order (see {@link VfxParts#layered}); the rest goes to {@code out}. */
    void submit(View view, float pt, PoseStack stack, SubmitNodeCollector out, IntFunction<OrderedSubmitNodeCollector> layered) {
        float t = age + pt;
        // Overall strength: a flash at first, a slight shimmer while held, then out.
        float shimmer = 1 + .06f * Mth.sin(t * .9f) + .04f * Mth.sin(t * 2.3f);
        float strength = t < 2 ? 1.4f * t / 2 : t < 10 ? Mth.lerp((t - 2) / 8, 1.4f, 1) : shimmer * (1 - smooth(HOLD, GONE, t));
        // The pillar itself keeps its brightness while it narrows, and goes out only once it is a line.
        float beam = t < 10 ? strength : shimmer * (1 - smooth(LINE, GONE, t));
        pillar(stack, layered, view, t, beam);
        groundGlow(stack, out, t, strength);
        shockwaves(stack, out, t);
        ripples(stack, out, t);
        flares(stack, layered, view, t);
        sparks(stack, out, view, t, strength);
        dust(stack, out, view, t, pt);
        rocks(stack, out, view, t, pt);
    }

    private void pillar(PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layered, View view, float t, float strength) {
        if (strength <= 0) return;
        float k = Mth.clamp(t / RISE, 0, 1);
        float top = HEIGHT * scale * (1 - (1 - k) * (1 - k) * (1 - k));
        // At the end the foot stays on the ground and the pillar narrows to a line by LINE, then goes out.
        float thin = smooth(HOLD, LINE, t);
        float bottom = 0, leave = 0;
        float width = (t < 10 ? Mth.lerp(t / 10, 1.35f, 1) : 1) * (1 - .97f * thin);
        float mid = (top - bottom) * .3f;
        double dx = view.cam().x - base.x, dz = view.cam().z - base.z;
        float near = (float) Math.sqrt(dx * dx + dz * dz);
        for (int i = 0; i < LAYERS.length; i++) {
            Layer l = LAYERS[i];
            int index = i;
            layered.apply(i).submitCustomGeometry(stack, l.material(), (pose, buf) -> layer(pose, buf, l, index, t, strength, width, bottom, mid, top, leave, near));
        }
    }

    /**
     * One layer of the pillar: the lower third brightening from the foot, the upper seven tenths fading to the top.
     * The texture is laid by world size (a whole number of tiles round it, so there is no seam) and rushes up at
     * {@code speed} blocks a tick, neighbouring layers turning slowly against each other.
     */
    private void layer(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Layer l, int i, float t, float strength,
            float width, float bottom, float mid, float top, float leave, float near) {
        float r = RADIUS * scale * l.radius() * width;
        // Fades out as the camera comes close and into the pillar, rather than filling the view or leaving a hard edge.
        float a = strength * l.alpha() * smooth(r, r * 2 + 1.5f, near);
        if (a <= 0) return;
        // Tiles round it counted from the full radius: as the pillar narrows the texture squeezes with it instead of
        // jumping a tile at a time from rounding.
        float around = Math.max(1, Math.round(Mth.TWO_PI * RADIUS * scale * l.radius() / (l.tile() * scale)));
        float tall = l.tile() * l.tall() * scale, v0 = t * l.speed() * scale / tall;
        float u0 = (i % 2 == 0 ? 1 : -1) * t * .003f * (i + 1);
        Vec3 foot = base.add(0, bottom, 0), waist = base.add(0, mid, 0);
        int segments = Math.max(24, (int) around * 8);
        if (l.stretch() > 0) {
            stretched(pose, buf, l, r, segments, u0, around, tall, v0, bottom, mid, top, a, leave);
            return;
        }
        VfxParts.cylinder(pose, buf, foot, r, mid - bottom, segments, u0, around, v0 + (top - mid) / tall, (mid - bottom) / tall,
                argb(a * (1 - leave), l.rgb()), argb(a, l.rgb()));
        VfxParts.cylinder(pose, buf, waist, r, top - mid, segments, u0, around, v0, (top - mid) / tall, argb(a, l.rgb()), argb(0, l.rgb()));
    }

    /**
     * A layer drawn in bands, the texture's vertical density falling steadily with height: at {@code f} of the full
     * height one tile is stretched {@code 1 + stretch·f^3} times. The bottom matches the other layers, the top is
     * drawn out nearly to a line, and so as it scrolls the top rushes faster.
     */
    private void stretched(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Layer l, float r, int segments, float u0,
            float around, float tall, float v0, float bottom, float mid, float top, float a, float leave) {
        float full = HEIGHT * scale, step = (top - bottom) / BANDS;
        // Tiles walked so far from the foot up; the texture's V falls upwards, so v = v0 - walked.
        float walked = 0;
        for (int b = 0; b < BANDS; b++) {
            float y0 = bottom + step * b, y1 = y0 + step;
            float f = (y0 + y1) * .5f / full;
            float length = step / (tall * (1 + l.stretch() * (float) Math.pow(f, STRETCH_POWER)));
            float lo = v0 - walked, hi = lo - length;
            VfxParts.cylinder(pose, buf, base.add(0, y0, 0), r, step, segments, u0, around, hi, lo - hi,
                    argb(profile(y0, bottom, mid, top, a, leave), l.rgb()), argb(profile(y1, bottom, mid, top, a, leave), l.rgb()));
            walked += length;
        }
    }

    /** The same alpha as the plain layers: brightening from the foot to a third up (the foot paler as it leaves the ground), fading above to the top. */
    private static float profile(float y, float bottom, float mid, float top, float a, float leave) {
        if (y <= mid) return Mth.lerp((y - bottom) / Math.max(1e-3f, mid - bottom), a * (1 - leave), a);
        return Mth.lerp((y - mid) / Math.max(1e-3f, top - mid), a, 0);
    }

    private void groundGlow(PoseStack stack, SubmitNodeCollector out, float t, float strength) {
        Vec3 at = base.add(0, .04, 0);
        float r = RADIUS * scale;
        float flash = Math.max(0, 1 - t / 9);
        out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) -> {
            if (strength > 0) VfxParts.ground(pose, buf, at, r * 4.5f, 0, argb(strength * .5f, MAIN));
            if (flash > 0) VfxParts.ground(pose, buf, at.add(0, .01, 0), r * (3 + 5 * (1 - flash)), 0, argb(flash, WHITE));
        });
        if (strength <= 0) return;
        out.submitCustomGeometry(stack, VfxParts.add("heal/ring"), (pose, buf) -> {
            // NS_FlamePillar's Under_ring: rings turning underfoot (about 80° a second).
            VfxParts.ground(pose, buf, at.add(0, .02, 0), r * 2.3f, t * .07f, argb(strength * .75f, LIGHT));
            VfxParts.ground(pose, buf, at.add(0, .03, 0), r * 1.45f, -t * .11f, argb(strength * .6f, WHITE));
        });
        out.submitCustomGeometry(stack, VfxParts.add("heal/swirl"), (pose, buf) ->
                VfxParts.ground(pose, buf, at.add(0, .015, 0), r * 3.2f, t * .05f, argb(strength * .35f, MAIN)));
    }

    /** Two shockwaves spreading along the ground: a bright ring on the ground and a low wall of wind (as in the colossal charge's waves). */
    private void shockwaves(PoseStack stack, SubmitNodeCollector out, float t) {
        float[][] waves = {{0, 22, 11}, {4, 30, 15}};
        List<float[]> live = new ArrayList<>();
        for (float[] w : waves) {
            float k = (t - w[0]) / w[1];
            if (k >= 0 && k < 1) live.add(new float[] {k, w[2]});
        }
        if (live.isEmpty()) return;
        out.submitCustomGeometry(stack, VfxParts.add("heal/ring"), (pose, buf) -> {
            for (float[] w : live) {
                float k = w[0], r = RADIUS * scale * (1.2f + w[1] * (1 - (float) Math.pow(1 - k, 2.5)));
                VfxParts.ground(pose, buf, base.add(0, .06, 0), r, k, argb((1 - k) * smooth(0, .06f, k), LIGHT));
            }
        });
        out.submitCustomGeometry(stack, VfxParts.add("physical_ray/wind"), (pose, buf) -> {
            for (float[] w : live) {
                float k = w[0], r = RADIUS * scale * (1.2f + w[1] * (1 - (float) Math.pow(1 - k, 2.5)));
                float h = (.25f + 1.6f * (1 - k)) * scale;
                float a = (1 - k) * (1 - k) * smooth(0, .08f, k) * .75f;
                VfxParts.cylinder(pose, buf, base.add(0, .03, 0), r, h, 48, t * .02f, 8, 0, 1, argb(a, MAIN), argb(0, LIGHT));
            }
        });
    }

    /**
     * All the while the pillar stands, a ring of white air spreads out from its foot every few ticks: a wavering,
     * broken edge trailing streaks of flow inwards. Each ring takes one of three shapes, turned at random and a
     * little squashed one way or the other, so no two look alike. None start once the pillar begins to leave.
     * Worked out from the time alone, with no state kept.
     */
    private void ripples(PoseStack stack, SubmitNodeCollector out, float t) {
        int every = 4, life = 24;
        int first = Math.max(0, (int) Math.ceil((t - life - 2) / every)), last = (int) Math.floor((Math.min(t, HOLD + 6) - 2) / every);
        if (last < first) return;
        for (int variant = 0; variant < 3; variant++) {
            int shape = variant;
            out.submitCustomGeometry(stack, VfxParts.add("pillar_burst/ripple_" + shape), (pose, buf) -> {
                for (int i = first; i <= last; i++) {
                    // The ring's shape, turn and squash hashed from its number, the same every frame.
                    int hash = mix(i * 0x9E3779B9 ^ seed);
                    if (Math.floorMod(hash, 3) != shape) continue;
                    float k = (t - 2 - i * every) / life;
                    if (k < 0 || k >= 1) continue;
                    float r = RADIUS * scale * (1.05f + 5.5f * (1 - (1 - k) * (1 - k)));
                    // The later a ring starts the fainter it is, ending with the pillar.
                    float a = (1 - k) * smooth(0, .1f, k) * .9f * (1 - smooth(HOLD - 10, HOLD + 6, 2 + i * every));
                    if (a <= 0) continue;
                    float turn = (hash >>> 8 & 1023) / 1023f * Mth.TWO_PI;
                    float squash = .88f + .24f * ((hash >>> 18 & 255) / 255f);
                    Vec3 along = new Vec3(Mth.cos(turn), 0, Mth.sin(turn)), across = new Vec3(-Mth.sin(turn), 0, Mth.cos(turn));
                    VfxParts.quad(pose, buf, base.add(0, .05 + .002 * (i % 5), 0), along.scale(r * squash), across.scale(r / squash), argb(a, 0xFFFFFF));
                }
            });
        }
    }

    /**
     * Two rings of flame tongues flung from the foot together at the burst (after the flame surge at the root of
     * Charlotta's pillar in Granblue Fantasy Relink): out to full length in four ticks, held bright a while, then
     * over about two and a half seconds fading and thinning as they drift outwards. Each is a long strip facing the
     * camera round its own axis, flickering through the four frames of {@code pillar_burst/flame}. A translucent
     * deep gold goes under first (the bright daytime sky washes additive glow to white), then the golden glow and a
     * thinner white core; {@code layered} fixes the order of the two materials, after the pillar's layers.
     */
    private void flares(PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layered, View view, float t) {
        if (t >= FLARE_GONE) return;
        float grow = 1 - (float) Math.pow(1 - Math.min(1, t / FLARE_OUT), 3);
        float fade = smooth(FLARE_HOLD, FLARE_GONE, t);
        float bright = (t < 2 ? .6f + .4f * t / 2 : 1) * (1 - fade);
        if (bright <= 0) return;
        layered.apply(LAYERS.length).submitCustomGeometry(stack, VfxParts.blend("pillar_burst/flame"), (pose, buf) ->
                flareStrips(pose, buf, view, t, grow, fade, bright, true));
        layered.apply(LAYERS.length + 1).submitCustomGeometry(stack, VfxParts.add("pillar_burst/flame"), (pose, buf) ->
                flareStrips(pose, buf, view, t, grow, fade, bright, false));
    }

    /** Every flame tongue's strip: the deep gold under them if {@code tint}, else the golden glow and white core. */
    private void flareStrips(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, View view, float t, float grow, float fade,
            float bright, boolean tint) {
        {
            for (Flare f : flares) {
                // Flicker: the length breathes a little, the frame changes every two ticks.
                float lick = 1 + .08f * Mth.sin(t * .9f + f.phase) + .05f * Mth.sin(t * 2.1f + f.phase * 2);
                float length = f.length * scale * grow * lick * (1 + .25f * fade);
                Vec3 outward = new Vec3(Mth.cos(f.angle), 0, Mth.sin(f.angle));
                Vec3 dir = outward.scale(Mth.sin(f.tilt)).add(0, Mth.cos(f.tilt), 0);
                // The root stays at the foot, and the whole tongue drifts outwards as it fades.
                Vec3 tail = base.add(outward.scale(RADIUS * scale * (1.05f + f.drift * t))).add(0, .15 * scale, 0);
                Vec3 head = tail.add(dir.scale(length));
                float near = VfxParts.fade(view.cam(), tail.add(head).scale(.5), 1.2, 3.5);
                float a = bright * near;
                if (a <= 0) continue;
                float width = f.width * scale * (1 - .4f * fade);
                int frame = (f.frame + (int) (t / 2)) % 4;
                float v0 = frame / 4f, v1 = v0 + .25f;
                if (tint) {
                    VfxParts.streak(pose, buf, view, tail, head, width, 0, v0, 1, v1, argb(a * .6f, DEEP));
                    continue;
                }
                VfxParts.streak(pose, buf, view, tail, head, width, 0, v0, 1, v1, argb(a * .6f, MAIN));
                VfxParts.streak(pose, buf, view, tail, tail.add(dir.scale(length * .75f)), width * .4f, 0, v0, 1, v1, argb(a * .35f, WHITE));
            }
        }
    }

    /** Scrambles an int into a random-looking one (murmur3's finaliser). */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        return h ^ h >>> 16;
    }

    /** Streaks rushing up inside the pillar (grain_up) and motes slowly rising round it (grain2). Worked out from the time alone. */
    private void sparks(PoseStack stack, SubmitNodeCollector out, View view, float t, float strength) {
        float top = HEIGHT * scale;
        out.submitCustomGeometry(stack, VfxParts.add("force/streak"), (pose, buf) -> {
            for (Spark s : sparks) {
                float y = (t - s.born) * s.speed * scale;
                if (y < 0 || y > top) continue;
                Vec3 head = base.add(Mth.cos(s.angle) * s.radius * scale, y, Mth.sin(s.angle) * s.radius * scale);
                float a = Math.min(1, strength + .3f) * (1 - y / top) * .9f;
                VfxParts.streak(pose, buf, view, head.add(0, -s.length * scale, 0), head, .1f * scale, argb(a, WHITE));
            }
        });
        out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) -> {
            for (Spark m : motes) {
                float life = 30, k = (t - m.born) / life;
                if (k < 0 || k >= 1) continue;
                float y = (t - m.born) * m.speed * scale;
                Vec3 at = base.add(Mth.cos(m.angle) * m.radius * scale, .2 + y, Mth.sin(m.angle) * m.radius * scale);
                float a = Math.min(k / .15f, (1 - k) / .4f) * VfxParts.fade(view.cam(), at, .8, 2.5);
                if (a > 0) VfxParts.sprite(pose, buf, view, at, m.length * scale * 2.2f, 0, argb(a, (int) m.born % 3 == 0 ? WHITE : LIGHT));
            }
        });
    }

    private void dust(PoseStack stack, SubmitNodeCollector out, View view, float t, float pt) {
        out.submitCustomGeometry(stack, VfxParts.blend("physical_ray/smoke"), (pose, buf) -> {
            for (Bit d : dust) {
                float k = (t - d.born) / d.life;
                if (k < 0 || k >= 1) continue;
                Vec3 at = d.prev.lerp(d.pos, pt);
                float a = Math.min(k / .08f, 1) * (1 - k) * (1 - k) * .42f * VfxParts.fade(view.cam(), at, 1.5, 4);
                float size = d.size * scale * (1 + 2.2f * (1 - (1 - k) * (1 - k)));
                if (a > 0) VfxParts.sprite(pose, buf, view, at, size, d.born + k * .8f, argb(a, d.tint));
            }
        });
    }

    private void rocks(PoseStack stack, SubmitNodeCollector out, View view, float t, float pt) {
        if (rocks.isEmpty()) return;
        out.submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Bit b : rocks) {
                if (t < b.born) continue;
                // Landed, it lies a while before fading.
                float gone = b.landed >= 0 ? (t - b.landed - 12) / 10 : (t - b.born - b.life) / 8;
                float a = (1 - Mth.clamp(gone, 0, 1));
                Vec3 at = b.prev.lerp(b.pos, pt);
                a *= VfxParts.fade(view.cam(), at, 1, 2.5);
                if (a <= 0) continue;
                TextureAtlasSprite s = b.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                VfxParts.cube(pose, buf, at, b.size, b.turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 2.5f, s.getV0() + dv * 2.5f, b.tint, a);
            }
        });
    }
}
