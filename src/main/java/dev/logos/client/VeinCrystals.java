package dev.logos.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Crystals thrust up out of the surface a lingering vein crawled over (see VEIN_VFX.md): the shared part of the spiky
 * lingering looks. As its head crawls, {@link #lay} stands crystals along the way, a few big ones and a crowd of small
 * ones between, each leaning a little off the surface it stands on (floor, wall or ceiling). Each thrusts up in a couple
 * of ticks, stays as long as what the vein left there stays, then sinks back into the surface and dissolves.
 * {@link #submit} draws them as faceted crystals in the {@link Style}'s material: its tileable texture flowing over
 * them, each facet lit on its own, the facets turned away from the camera glowing in the rim colour.
 */
public final class VeinCrystals {
    /** Ticks a crystal takes to thrust up. */
    private static final float GROW = 2.5f;
    /** At most this many ticks to sink back, and never more than this share of its life. */
    private static final float SINK = 12, SINK_SHARE = .3f;
    /** No more than this many crystals for one head, however long the way. */
    private static final int MAX = 240;
    /** Beyond this far from the camera the small crystals are not drawn, beyond the second none. */
    private static final double SMALL_FAR = 40, FAR = 128;
    /** Where the light comes from, for shading the facets. */
    private static final Vec3 LIGHT = new Vec3(.35, 1, .25).normalize();

    /**
     * What the crystals look like. {@code texture} (under {@code textures/vfx/}) is tileable and drawn in its own colours
     * times {@code tint}; {@code rim} is the glow of the facets turned away from the camera, and where they dissolve.
     * {@code clear} is how opaque the body is (1 solid; less is see-through, its rim still solid). {@code contrast} is
     * how much of the texture's light and dark shows (1 all of it; less flattens it toward a clean, even colour).
     * {@code scale} is how many times the texture repeats per block, {@code panU}/{@code panV} how far it flows round and up a crystal per
     * second. {@code sides} is how many facets round a crystal, {@code tip} what share of its length is its point,
     * {@code slant} how far off its axis the point is (in its radii: 0 a straight point, near 1 a slanting cut), and
     * {@code lean} the most it leans off the surface, in degrees. {@code thick} and {@code tall} scale its radius by
     * the vein's width and its length by the vein's height; {@code big} and {@code small} are how many of each stand
     * per block crawled.
     */
    public record Style(String texture, int tint, int rim, float clear, float contrast, float scale, float panU, float panV,
            int sides, float tip, float slant, float lean, float thick, float tall, float big, float small) {
        /** {@code texture} in its own colours with a {@code rim}: solid four-sided crystals with slanting points. */
        public static Style of(String texture, int rim) {
            return new Style(texture, 0xFFFFFF, rim, 1, 1, .9f, .1f, .2f, 4, .22f, .6f, 30, 1, 1, 1.1f, 2.6f);
        }

        public Style withTint(int tint) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }

        /** See-through: the body only {@code clear} opaque. */
        public Style withClear(float clear) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }

        /** The texture's light and dark only {@code contrast} as strong, the rest an even colour. */
        public Style withContrast(float contrast) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }

        public Style withFlow(float scale, float panU, float panV) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }

        public Style withShape(int sides, float tip, float slant, float lean) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }

        public Style withSize(float thick, float tall) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }

        public Style withCount(float big, float small) {
            return new Style(texture, tint, rim, clear, contrast, scale, panU, panV, sides, tip, slant, lean, thick, tall, big, small);
        }
    }

    /**
     * One crystal: standing at {@code base} on the surface along {@code axis} (unit), {@code radius} thick and
     * {@code length} long when grown, its facets turned by {@code x}/{@code y} (unit, square to the axis and each other,
     * {@code x × y = axis}); laid at tick {@code laid}, gone at {@code until}. {@code big} is one of the big ones.
     */
    public record Crystal(Vec3 base, Vec3 axis, Vec3 x, Vec3 y, float radius, float length, float seed, long laid, long until,
            boolean big) {
        /** How far it stands out of the surface at {@code now}, 0 to 1 (a little over as it thrusts up). */
        public float out(double now) {
            double t = now - laid;
            if (t <= 0) return 0;
            float grown = t >= GROW ? 1 : backOut((float) (t / GROW));
            double sink = Math.min(SINK, (until - laid) * SINK_SHARE);
            double left = until - now;
            return left >= sink ? grown : grown * smooth((float) (left / Math.max(sink, 1e-3)));
        }

        /** How far it has dissolved at {@code now}: 0 whole to 1 gone, over the time it sinks. */
        public float dissolved(double now) {
            double sink = Math.min(SINK, (until - laid) * SINK_SHARE);
            return Mth.clamp((float) (1 - (until - now) / Math.max(sink, 1e-3)), 0, 1);
        }

        /** Its point, when grown. */
        public Vec3 top() {
            return base.add(axis.scale(length));
        }
    }

    private static final Map<Style, RenderType> MATERIALS = new HashMap<>();

    private final Style style;
    private final ArrayDeque<Crystal> crystals = new ArrayDeque<>();
    private double carriedBig, carriedSmall;

    public VeinCrystals(Style style) {
        this.style = style;
    }

    public Style style() { return style; }

    /** Every crystal still standing, oldest first. */
    public ArrayDeque<Crystal> crystals() { return crystals; }

    public boolean isEmpty() { return crystals.isEmpty(); }

    /**
     * Stands crystals along what {@code vein}'s head crawled since the last tick, to stay {@link LiveVein#linger} ticks
     * from {@code now}; the ones laid are added to {@code laid} (if not null), oldest first.
     */
    public void lay(LiveVein vein, RandomSource r, long now, List<Crystal> laid) {
        Vec3 from = vein.prev(), to = vein.pos(), n = vein.normal();
        double length = from.distanceTo(to);
        Vec3 side = vein.dir().cross(n);
        side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
        long until = now + Math.max(1, vein.linger());
        if (style.big > 0) {
            double every = 1 / style.big;
            for (double s = every - carriedBig; s <= length; s += every) add(vein, r, from, to, length, s, n, side, until, now, true, laid);
            carriedBig = (carriedBig + length) % every;
        }
        if (style.small > 0) {
            double every = 1 / style.small;
            for (double s = every - carriedSmall; s <= length; s += every) add(vein, r, from, to, length, s, n, side, until, now, false, laid);
            carriedSmall = (carriedSmall + length) % every;
        }
    }

    private void add(LiveVein vein, RandomSource r, Vec3 from, Vec3 to, double length, double s, Vec3 n, Vec3 side, long until,
            long now, boolean big, List<Crystal> laid) {
        if (crystals.size() >= MAX) return;
        float w = vein.width() * style.thick, h = vein.height() * style.tall;
        // Big ones keep near the line, small ones crowd round them.
        double across = (r.nextDouble() - .5) * vein.width() * (big ? .6 : 1.1);
        Vec3 at = from.lerp(to, length < 1e-9 ? 1 : s / length).add(side.scale(across));
        float radius = w * (big ? .16f + .08f * r.nextFloat() : .06f + .04f * r.nextFloat());
        float tall = h * (big ? .9f + .7f * r.nextFloat() : .3f + .4f * r.nextFloat());
        // Leaning off the surface, any way round, a little more outward the farther it stands from the line.
        double lean = Math.toRadians(style.lean) * Math.sqrt(r.nextDouble());
        double turn = r.nextDouble() * Math.PI * 2;
        Vec3 u = perpendicular(n), v = n.cross(u);
        Vec3 off = u.scale(Math.cos(turn)).add(v.scale(Math.sin(turn))).add(side.scale(across * .5)).normalize();
        Vec3 axis = n.scale(Math.cos(lean)).add(off.scale(Math.sin(lean))).normalize();
        Vec3 x = perpendicular(axis);
        double twist = r.nextDouble() * Math.PI * 2;
        x = x.scale(Math.cos(twist)).add(axis.cross(x).scale(Math.sin(twist))).normalize();
        Vec3 y = axis.cross(x);
        // Its foot sunk a little into the surface, so it comes out of it rather than stands on it.
        Vec3 base = at.subtract(axis.scale(tall * .12f));
        // Thrust up one after another as the head passes, not all in one tick.
        long when = now + (big ? 0 : r.nextInt(2));
        Crystal c = new Crystal(base, axis, x, y, radius, tall, r.nextFloat(), when, until + (when - now), big);
        crystals.addLast(c);
        if (laid != null) laid.add(c);
    }

    private static Vec3 perpendicular(Vec3 a) {
        Vec3 p = Math.abs(a.y) < .9 ? new Vec3(0, 1, 0).cross(a) : new Vec3(1, 0, 0).cross(a);
        return p.normalize();
    }

    /** Drops the crystals gone by {@code now}. */
    public void prune(long now) {
        crystals.removeIf(c -> c.until() <= now);
    }

    /** Draws every crystal standing; {@code out} already goes through {@link ShaderPackOverlay}. */
    public void submit(Vec3 cam, double now, PoseStack pose, SubmitNodeCollector out) {
        if (crystals.isEmpty()) return;
        out.submitCustomGeometry(pose, material(style), (p, buf) -> {
            for (Crystal c : crystals) {
                double far = c.base().distanceTo(cam);
                if (far > FAR || !c.big() && far > SMALL_FAR) continue;
                float grown = c.out(now);
                if (grown <= .01f) continue;
                crystal(p, buf, cam, c, grown, c.dissolved(now), (float) Math.max(0, 1 - (now - c.laid()) / 6));
            }
        });
    }

    private void crystal(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Crystal c, float grown, float dissolved, float flash) {
        int sides = Math.max(3, style.sides);
        double length = c.length() * grown;
        double shoulder = length * (1 - style.tip);
        double r = c.radius(), top = r * .85;
        Vec3 apex = c.base().add(c.axis().scale(length)).add(c.x().scale(style.slant * top));
        Vec3[] foot = new Vec3[sides + 1], ridge = new Vec3[sides + 1];
        for (int i = 0; i <= sides; i++) {
            double a = Math.PI * 2 * i / sides;
            Vec3 round = c.x().scale(Math.cos(a)).add(c.y().scale(Math.sin(a)));
            foot[i] = c.base().add(round.scale(r));
            ridge[i] = c.base().add(c.axis().scale(shoulder)).add(round.scale(top));
        }
        float around = (float) (Math.PI * 2 * r / sides) * style.scale;
        float v0 = c.seed() * 7, v1 = v0 + (float) shoulder * style.scale, v2 = v0 + (float) length * style.scale;
        int dis = (int) (dissolved * 1000), seed = (int) (c.seed() * 1000);
        for (int i = 0; i < sides; i++) {
            float u0 = i * around, u1 = (i + 1) * around;
            face(pose, buf, cam, foot[i], foot[i + 1], ridge[i + 1], ridge[i], u0, u1, v0, v1, flash, dis, seed);
            face(pose, buf, cam, ridge[i], ridge[i + 1], apex, apex, u0, u1, v1, v2, flash, dis, seed);
        }
    }

    /**
     * One facet a-b-c-d (a triangle when c is d), wound so it faces out. Its colour carries numbers for the shader:
     * how lit it is, how far it faces away from the camera, how much it flashes.
     */
    private static void face(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
            float u0, float u1, float v0, float v1, float flash, int dis, int seed) {
        Vec3 n = b.subtract(a).cross(c.subtract(a));
        if (n.lengthSqr() < 1e-12) return;
        n = n.normalize();
        Vec3 mid = a.add(b).add(c).add(d).scale(.25);
        Vec3 view = cam.subtract(mid);
        double facing = view.lengthSqr() < 1e-9 ? 1 : Math.abs(n.dot(view.normalize()));
        float rim = (float) Math.pow(1 - facing, 2.2);
        float shade = (float) (.3 + .7 * Math.max(0, n.dot(LIGHT)));
        int color = 255 << 24 | (int) (shade * 255) << 16 | (int) (rim * 255) << 8 | (int) (Mth.clamp(flash, 0, 1) * 255);
        vertex(pose, buf, cam, a, u0, v0, color, n, dis, seed);
        vertex(pose, buf, cam, b, u1, v0, color, n, dis, seed);
        vertex(pose, buf, cam, c, u1, v1, color, n, dis, seed);
        vertex(pose, buf, cam, d, d == c ? u1 : u0, v1, color, n, dis, seed);
    }

    /** Positions relative to the camera, so crystals far from the world origin keep their precision. */
    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 at, float u, float v, int color, Vec3 n,
            int dis, int seed) {
        buf.addVertex(pose, (float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z)).setColor(color).setUv(u, v)
                .setUv2(dis, seed).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    /**
     * The crystals' material: the field-line vertex stage (handing the fragment stage the dissolve and a per-crystal
     * offset through the second UV) and {@code core/vein_crystal}, the style's colours and flow as defines. Facets are
     * culled from behind and write depth, so crystals hide one another and the ground's particles behind them.
     */
    static RenderType material(Style style) {
        return MATERIALS.computeIfAbsent(style, s -> {
            String key = (s.texture + "_" + Integer.toHexString(s.hashCode())).replace('/', '_');
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/vein_crystal_" + key))
                    .withVertexShader(id("core/field_line")).withFragmentShader(id("core/vein_crystal")).withCull(true)
                    .withShaderDefine("TINT_R", (s.tint >> 16 & 255) / 255f).withShaderDefine("TINT_G", (s.tint >> 8 & 255) / 255f)
                    .withShaderDefine("TINT_B", (s.tint & 255) / 255f)
                    .withShaderDefine("RIM_R", (s.rim >> 16 & 255) / 255f).withShaderDefine("RIM_G", (s.rim >> 8 & 255) / 255f)
                    .withShaderDefine("RIM_B", (s.rim & 255) / 255f)
                    .withShaderDefine("PAN_U", s.panU).withShaderDefine("PAN_V", s.panV)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
            if (s.clear < 1) pipeline.withShaderDefine("CLEAR", s.clear);
            if (s.contrast < 1) pipeline.withShaderDefine("CONTRAST", s.contrast);
            return RenderType.create("logos_vein_crystal_" + key, RenderSetup.builder(pipeline.build())
                    .withTexture("Sampler0", id("textures/vfx/" + s.texture + ".png")).sortOnUpload().createRenderSetup());
        });
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Up past 1 and back: the overshoot of something thrust up hard. */
    private static float backOut(float t) {
        float k = 1.7f, s = t - 1;
        return 1 + s * s * ((k + 1) * s + k);
    }
}
