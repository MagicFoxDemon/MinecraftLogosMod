package dev.logos.client;

import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;

/**
 * The burst half of Piloto Studio's {@code PS_UltimateBeam_Arcane} (the charge is the wand's): a rose-violet
 * laser with a white thread, a starry ribbon and a silky veil, crackle, darts racing down the line and a
 * twin spiral wake, all within a third of a second. What it leaves is the arcane part: the line is
 * written over with glowing runes and four-point glints that drift up, twinkle and go out one by one,
 * while the thread they hang on dissolves in patches. A hit (block or creature) flares in the arcane
 * projectile impact's colours with a few runes flung back; the open air takes nothing; a link is a plain line.
 */
final class ArcaneRayVisual implements RayVisual {
    // ---- timeline (ticks) -----------------------------------------------------------------------
    /** The laser and its starry ribbon: full until 38% of this, then gone (MA_Laser_01 / MA_Laser_Fairy). */
    private static final float LASER = 7;
    /** The silky veil swells and fades over this (WaterNoise_MaskedAlpha). */
    private static final float VEIL = 16;
    /** The thread holds until here, then dissolves until {@link #THREAD_GONE}. */
    private static final float THREAD_HOLD = 8, THREAD_GONE = 100;
    /** Nothing is left after this (6.8 s; the last rune goes out by about 124). */
    private static final int LIFE = 136, LINK_LIFE = 40;

    // ---- palette (from the arcane projectile and its impact) --------------------------------------
    private static final int PINK = 0xE668EF, VIOLET = 0xCA38FF, DEEP = 0xA136EF, LILAC = 0xE6A0FF,
            BLUSH = 0xFBB6F2, HOT = 0xF9DCFF, PERIWINKLE = 0x8C72FC;

    // Colour-carrying layers blend normally so the ray stays rose-violet against a noon sky; the
    // additive ones only add light on top (alone they wash out to white by day).
    private static final RenderType GLOW = material("glow", "fuzz", false, "ARCANE_RAY_CORE");
    private static final RenderType CORE = material("core", "fuzz", false, "ARCANE_RAY_CORE");
    private static final RenderType STARRY = material("starry", "starry", true, "ARCANE_RAY_BAND");
    private static final RenderType ELECTRIC = material("electric", "electric", true, "ARCANE_RAY_BAND");
    private static final RenderType SILK = material("silk", "silk", false, "ARCANE_RAY_BAND");
    private static final RenderType THREAD = material("thread", "silk", false, "ARCANE_RAY_DISSOLVE");
    private static final RenderType THREAD_GLOW = material("thread_glow", "silk", false, "ARCANE_RAY_DISSOLVE", "ARCANE_RAY_SOFT");
    private static final RenderType STREAK = material("streak", "streak", true, "ARCANE_RAY_HOT");
    private static final RenderType WIND = material("wind", "wind", true);
    private static final RenderType RUNE = material("rune", "runes", false, "ARCANE_RAY_HOT");
    private static final RenderType GLINT = material("glint", "glint", true, "ARCANE_RAY_HOT");
    private static final RenderType FLARE = material("flare", "flare", true, "ARCANE_RAY_HOT");
    private static final RenderType FUZZ = material("fuzz", "fuzz", true);
    private static final RenderType DOT = material("dot", "dot", true, "ARCANE_RAY_HOT");

    private static final FX IMPACT = impactEffect();

    private record Rune(double s, Vec3 offset, Vec3 drift, float appear, float fade, float size, float roll, float spin, int frame, float phase) {}
    private record Glint(double s, Vec3 offset, Vec3 drift, float appear, float fade, float size, float phase, boolean dot) {}
    private record Dart(float delay, float speed, float length, float width, Vec3 offset, int color) {}

    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO, dir = new Vec3(0, 0, 1), side = new Vec3(1, 0, 0), lift = new Vec3(0, 1, 0);
    private double length;
    private Vfx.RayEnd end = Vfx.RayEnd.OPEN;
    private long born;
    private float flareRoll;
    private final List<Rune> runes = new ArrayList<>();
    private final List<Glint> glints = new ArrayList<>();
    private final List<Dart> darts = new ArrayList<>();
    private FXRuntime impact;

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        this.to = to;
        this.end = end;
        born = level.getGameTime();
        Vec3 delta = to.subtract(from);
        length = delta.length();
        if (length > 1e-4) dir = delta.scale(1 / length);
        side = dir.cross(Math.abs(dir.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        lift = side.cross(dir).normalize();
        Random r = new Random(Double.doubleToLongBits(from.x * 31 + to.z * 17 + to.y) ^ born);
        flareRoll = r.nextFloat() * 360;
        if (end == Vfx.RayEnd.LINK || length < .3) return;
        double reveal = revealTicks();
        // Runes written along the line, the wave following the burst out to the end.
        int count = (int) Math.max(2, Math.min(40, Math.round(length / 1.25)));
        for (int i = 0; i < count; i++) {
            double s = (i + .5 + (r.nextDouble() - .5) * .6) / count * length;
            if (s < .9) continue;
            double a = r.nextDouble() * Math.PI * 2, rad = .06 + r.nextDouble() * .16;
            Vec3 offset = side.scale(Math.cos(a) * rad).add(lift.scale(Math.sin(a) * rad));
            Vec3 drift = new Vec3((r.nextDouble() - .5) * .004, .0056 + r.nextDouble() * .005, (r.nextDouble() - .5) * .004);
            // Written once the laser starts to thin, so the burst itself stays clean.
            float appear = (float) (reveal * s / length) + 3.5f + r.nextFloat() * 2.5f;
            runes.add(new Rune(s, offset, drift, appear, 54 + r.nextFloat() * 64, .19f + r.nextFloat() * .08f,
                    (r.nextFloat() - .5f) * 50, (r.nextFloat() - .5f) * 1.6f, r.nextInt(9), r.nextFloat() * 6.28f));
        }
        int stars = (int) Math.max(3, Math.min(70, Math.round(length * 1.6)));
        for (int i = 0; i < stars; i++) {
            double s = .6 + r.nextDouble() * Math.max(0, length - .6);
            double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * .34;
            Vec3 offset = side.scale(Math.cos(a) * rad).add(lift.scale(Math.sin(a) * rad));
            boolean dot = r.nextFloat() < .55f;
            Vec3 drift = new Vec3((r.nextDouble() - .5) * .006, (dot ? .0075 : .0025) + r.nextDouble() * .006, (r.nextDouble() - .5) * .006);
            float appear = (float) (reveal * s / length) + r.nextFloat() * 4;
            glints.add(new Glint(s, offset, drift, appear, 29 + r.nextFloat() * (dot ? 48 : 74),
                    dot ? .025f + r.nextFloat() * .025f : .07f + r.nextFloat() * .07f, r.nextFloat() * 6.28f, dot));
        }
        int[] tints = {PINK, VIOLET, LILAC, PERIWINKLE, HOT};
        for (int i = 0; i < 9; i++) {
            double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * .2;
            darts.add(new Dart(r.nextFloat() * 2.2f, (float) ((length + 3) / (1.6 + r.nextDouble() * 2.2)),
                    (float) Math.min(length * .45, 1.2 + r.nextDouble() * 2), .05f + r.nextFloat() * .06f,
                    side.scale(Math.cos(a) * rad).add(lift.scale(Math.sin(a) * rad)), tints[r.nextInt(tints.length)]));
        }
        if (end == Vfx.RayEnd.BLOCK || end == Vfx.RayEnd.CREATURE) {
            // Pulled back toward the caster: out of the block face, onto the near side of the creature.
            double back = end == Vfx.RayEnd.CREATURE ? Math.min(.5, length * .25) : .08;
            impact = emit(level, to.subtract(dir.scale(back)), dir.scale(-1));
        }
    }

    @Override
    public boolean tick(Level level) {
        long age = level.getGameTime() - born;
        if (impact != null && age > 30) { impact.destroy(false); impact = null; }
        return age < (end == Vfx.RayEnd.LINK ? LINK_LIFE : LIFE);
    }

    @Override
    public void end() {
        if (impact != null) { impact.destroy(false); impact = null; }
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack stack, SubmitNodeCollector out) {
        if (length < .05) return;
        float t = (float) (level.getGameTime() - born) + partialTick;
        Vec3 cam = camera.pos;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        boolean link = end == Vfx.RayEnd.LINK;
        double front = Math.min(length, length * t / revealTicks());
        stack.pushPose();
        stack.translate(-cam.x, -cam.y, -cam.z);

        // ---- burst ----
        float laser = laser(t, LASER), starry = laser(t, LASER - 1);
        if (laser > 0) {
            out.submitCustomGeometry(stack, GLOW, (pose, buf) -> strip(pose, buf, cam, 0, front, .5f * laser,
                    argb(.4f, VIOLET), 0, 0));
            out.submitCustomGeometry(stack, CORE, (pose, buf) -> strip(pose, buf, cam, 0, front, .16f * laser,
                    argb(.95f, PINK), 0, 0));
        }
        if (starry > 0) {
            int tint = mix(PINK, DEEP, Math.min(1, t / LASER));
            out.submitCustomGeometry(stack, STARRY, (pose, buf) -> strip(pose, buf, cam, 0, front, .34f * starry,
                    argb(.75f, tint), .5f, -t * .32f));
        }
        if (!link) {
            float crackle = t < 5 ? Math.min(1, t * 2) : 0;
            if (crackle > 0) {
                float flip = (int) (t * 1.5f) * .29f;
                out.submitCustomGeometry(stack, ELECTRIC, (pose, buf) -> strip(pose, buf, cam, 0, front, .24f,
                        argb(.42f * crackle, 0xB98CFF), .55f, flip));
            }
            if (t < VEIL) {
                float x = t / VEIL;
                float width = .06f + .5f * Math.min(1, x / .7f);
                float alpha = .26f * (x < .42f ? 1 : 1 - (x - .42f) / .58f);
                int tint = mix(PERIWINKLE, 0x85759A, x);
                out.submitCustomGeometry(stack, SILK, (pose, buf) -> strip(pose, buf, cam, 0, front, width,
                        argb(alpha, tint), .3f, -t * .04f));
            }
            if (t < 7) out.submitCustomGeometry(stack, WIND, (pose, buf) -> spirals(pose, buf, cam, t));
            if (t < 6) out.submitCustomGeometry(stack, STREAK, (pose, buf) -> darts(pose, buf, cam, t));
            if (t < 7) outlet(stack, out, cam, right, up, t);
        }

        // ---- what it leaves ----
        float threadLeft = t < THREAD_HOLD ? 1 : Math.max(0, 1 - (t - THREAD_HOLD) / ((link ? 30 : THREAD_GONE) - THREAD_HOLD));
        float threadIn = (float) smooth(2, 6, t);
        if (threadLeft > 0 && threadIn > 0) {
            // Fades in patch by patch as the laser thins, and goes the same way.
            float left = threadLeft * threadIn;
            out.submitCustomGeometry(stack, THREAD_GLOW, (pose, buf) -> strip(pose, buf, cam, 0, front, .2f,
                    argb(left, VIOLET), .22f, 0));
            out.submitCustomGeometry(stack, THREAD, (pose, buf) -> strip(pose, buf, cam, 0, front, .05f,
                    argb(left, PINK), .22f, 0));
        }
        if (!runes.isEmpty()) {
            out.submitCustomGeometry(stack, FUZZ, (pose, buf) -> runeGlow(pose, buf, cam, right, up, t));
            out.submitCustomGeometry(stack, RUNE, (pose, buf) -> runes(pose, buf, cam, right, up, t));
        }
        if (!glints.isEmpty()) {
            out.submitCustomGeometry(stack, GLINT, (pose, buf) -> glints(pose, buf, cam, right, up, t, false));
            out.submitCustomGeometry(stack, DOT, (pose, buf) -> glints(pose, buf, cam, right, up, t, true));
        }
        stack.popPose();
    }

    // ---- burst pieces -----------------------------------------------------------------------------

    /** Ticks for the burst to reach the end: practically instant, but the eye still sees it go out. */
    private double revealTicks() {
        return .8 + length / 48;
    }

    /** MA_Laser_01's width: up in half a tick, full until 38% of its life, then eased to nothing. */
    private static float laser(float t, float life) {
        if (t >= life) return 0;
        float x = t / life;
        float attack = Math.min(1, .35f + t * 1.3f);
        return attack * (x < .378f ? 1 : (float) Math.pow(1 - (x - .378f) / .622f, 1.3));
    }

    /** DiagonalFlare + AlphaFuzzball at the wand head; held small and pushed out when it is right at the camera. */
    private void outlet(PoseStack stack, SubmitNodeCollector out, Vec3 cam, Vec3 right, Vec3 up, float t) {
        float x = t / 7;
        float close = (float) smooth(.6, 2.2, cam.distanceTo(from));
        Vec3 at = from.add(dir.scale(.12 + .5 * (1 - close)));
        float grow = .45f + .55f * Math.min(1, t / 1.5f);
        float flare = (.75f * close + .16f) * grow * (x < .21f ? 1 : 1 - (x - .21f) / .79f);
        float glow = (.6f * close + .14f) * (.5f + .5f * Math.min(1, t / 2));
        float alpha = (.45f + .55f * close) * (1 - x);
        float roll = flareRoll + t * 4;
        out.submitCustomGeometry(stack, FUZZ, (pose, buf) -> sprite(pose, buf, at, right, up, glow, 0,
                argb(.7f * alpha, mix(PERIWINKLE, DEEP, x)), 0, 0, 1, 1, cam, false));
        out.submitCustomGeometry(stack, FLARE, (pose, buf) -> {
            sprite(pose, buf, at, right, up, flare, roll, argb(alpha, mix(PINK, 0x7974FF, x)), 0, 0, 1, 1, cam, false);
            sprite(pose, buf, at, right, up, flare * .7f, roll + 110, argb(.7f * alpha, VIOLET), 0, 0, 1, 1, cam, false);
        });
        if (end == Vfx.RayEnd.OPEN && t < 5) {
            // The open end only thins out into a last glint; there is nothing to hit.
            Vec3 tip = to;
            float fade = 1 - t / 5;
            out.submitCustomGeometry(stack, GLINT, (pose, buf) -> sprite(pose, buf, tip, right, up, .35f * fade, 45,
                    argb(.8f * fade, LILAC), 0, 0, 1, 1, cam, false));
        }
    }

    /** Trail_WindyGeneric orbiting the line: two spiral wakes racing out behind the burst front. */
    private void spirals(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, float t) {
        double head = Math.min(length + 2.5, (length + 2.5) * t / (revealTicks() + 1.6));
        double tail = Math.max(0, head - 5);
        head = Math.min(head, length);
        if (head - tail < .1) return;
        float fade = t < 4 ? 1 : 1 - (t - 4) / 3;
        int n = (int) Math.max(4, Math.min(120, (head - tail) / .12));
        for (int k = 0; k < 2; k++) {
            Vec3[] p = new Vec3[n + 1];
            float[] w = new float[n + 1], u = new float[n + 1];
            int[] c = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                double s = tail + (head - tail) * i / n;
                double radius = (.2 + .14 * Math.min(1, t / 5)) * Math.min(1, s / 1.2);
                double a = s / 2.3 * Math.PI * 2 + k * Math.PI + t * .35;
                p[i] = from.add(dir.scale(s)).add(side.scale(Math.cos(a) * radius)).add(lift.scale(Math.sin(a) * radius));
                w[i] = .09f;
                u[i] = (float) i / n;
                c[i] = argb(.85f * fade, mix(DEEP, 0xFF53FD, (float) i / n));
            }
            polyline(pose, buf, cam, p, w, u, c);
        }
    }

    /** Flare_Ultrawide_Intense: bright darts racing down the line ahead of and through the laser. */
    private void darts(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, float t) {
        for (Dart d : darts) {
            float age = t - d.delay;
            if (age <= 0) continue;
            double head = age * d.speed;
            double tail = Math.max(0, head - d.length);
            if (tail >= length) continue;
            head = Math.min(head, length);
            if (head - tail < .05) continue;
            float fade = Math.min(1, age * 2) * (age > 2.5f ? Math.max(0, 1 - (age - 2.5f) / 1.5f) : 1);
            Vec3 a = from.add(dir.scale(tail)).add(d.offset), b = from.add(dir.scale(head)).add(d.offset);
            int c = argb(fade, d.color);
            polyline(pose, buf, cam, new Vec3[]{a, a.lerp(b, .5), b}, new float[]{d.width, d.width, d.width},
                    new float[]{0, .5f, 1}, new int[]{c, c, c});
        }
    }

    // ---- what it leaves ---------------------------------------------------------------------------

    private void runes(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 right, Vec3 up, float t) {
        for (Rune r : runes) {
            float a = runeAlpha(r, t);
            if (a <= 0) continue;
            float age = t - r.appear;
            float pop = 1 + .7f * (float) Math.exp(-age * .55f);
            Vec3 at = from.add(dir.scale(r.s)).add(r.offset).add(r.drift.scale(age));
            float u0 = r.frame % 3 / 3f, v0 = r.frame / 3 / 3f;
            int tint = mix(HOT, mix(PINK, DEEP, Math.min(1, age / 50)), Math.min(1, age / 5));
            sprite(pose, buf, at, right, up, r.size * pop, r.roll + r.spin * age, argb(a, tint), u0, v0, u0 + 1 / 3f, v0 + 1 / 3f, cam, true);
        }
    }

    private void runeGlow(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 right, Vec3 up, float t) {
        for (Rune r : runes) {
            float a = runeAlpha(r, t);
            if (a <= 0) continue;
            float age = t - r.appear;
            Vec3 at = from.add(dir.scale(r.s)).add(r.offset).add(r.drift.scale(age));
            sprite(pose, buf, at, right, up, r.size * 2.4f, 0, argb(.3f * a, VIOLET), 0, 0, 1, 1, cam, true);
        }
    }

    /** In over two ticks, a slow twinkle, out over the last nineteen before it goes. */
    private static float runeAlpha(Rune r, float t) {
        float age = t - r.appear;
        if (age <= 0 || t >= r.fade) return 0;
        float in = Math.min(1, age / 2);
        float out = Math.min(1, (r.fade - t) / 19);
        float twinkle = .78f + .22f * (float) Math.sin(t * .45f + r.phase);
        return in * out * out * twinkle;
    }

    private void glints(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3 right, Vec3 up, float t, boolean dots) {
        for (Glint g : glints) {
            if (g.dot != dots) continue;
            float age = t - g.appear;
            if (age <= 0 || t >= g.fade) continue;
            float in = Math.min(1, age / 1.5f), out = Math.min(1, (g.fade - t) / 16);
            float twinkle = dots ? 1 : .35f + .65f * (float) Math.pow(.5 + .5 * Math.sin(t * .8f + g.phase), 2);
            float a = in * out * twinkle;
            Vec3 at = from.add(dir.scale(g.s)).add(g.offset).add(g.drift.scale(age));
            int tint = dots ? BLUSH : mix(HOT, LILAC, Math.min(1, age / 16));
            sprite(pose, buf, at, right, up, g.size * (dots ? 1 : .7f + .3f * twinkle), dots ? 0 : 45 * (g.phase > 3.14f ? 1 : 0),
                    argb(a, tint), 0, 0, 1, 1, cam, true);
        }
    }

    // ---- geometry ---------------------------------------------------------------------------------

    /** A camera-facing ribbon along the ray from {@code s0} to {@code s1} blocks; U runs {@code uPerBlock} per block from {@code u0}. */
    private void strip(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, double s0, double s1, float halfWidth,
            int color, float uPerBlock, float u0) {
        if (s1 - s0 < .02 || halfWidth <= 0) return;
        int n = (int) Math.max(2, Math.min(160, Math.ceil((s1 - s0) / .35)));
        Vec3[] p = new Vec3[n + 1];
        float[] w = new float[n + 1], u = new float[n + 1];
        int[] c = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            double s = s0 + (s1 - s0) * i / n;
            p[i] = from.add(dir.scale(s));
            float shape = taper(s);
            w[i] = halfWidth * shape;
            u[i] = (float) (u0 + s * uPerBlock);
            c[i] = fade(color, Math.min(1, shape * 1.4f));
        }
        polyline(pose, buf, cam, p, w, u, c);
    }

    /** Softly opened at the wand head; an open ray thins out over its last stretch instead of stopping short. */
    private float taper(double s) {
        float start = (float) Math.min(1, .35 + s / .6);
        if (end != Vfx.RayEnd.OPEN) return start;
        double left = length - s;
        return start * (float) Math.min(1, .15 + left / Math.min(2.5, length * .4));
    }

    /** A camera-facing strip through {@code p}; widths shrink near the camera so the wand head never fills the view. */
    private static void polyline(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vec3[] p, float[] w, float[] u, int[] c) {
        int n = p.length - 1;
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tangent = p[Math.min(n, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 cross = tangent.cross(cam.subtract(p[i]));
            sides[i] = (cross.lengthSqr() < 1e-10 ? new Vec3(0, 1, 0) : cross.normalize()).scale(w[i] * near(cam, p[i]));
        }
        for (int i = 0; i < n; i++) {
            vertex(pose, buf, p[i].add(sides[i]), u[i], 0, c[i]);
            vertex(pose, buf, p[i].subtract(sides[i]), u[i], 1, c[i]);
            vertex(pose, buf, p[i + 1].subtract(sides[i + 1]), u[i + 1], 1, c[i + 1]);
            vertex(pose, buf, p[i + 1].add(sides[i + 1]), u[i + 1], 0, c[i + 1]);
        }
    }

    private static void sprite(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Vec3 right, Vec3 up, float half, float rollDeg,
            int color, float u0, float v0, float u1, float v1, Vec3 cam, boolean shrinkNear) {
        if (half <= 0 || (color >>> 24) == 0) return;
        if (shrinkNear) {
            float near = near(cam, at);
            half *= near;
            color = fade(color, near);
        }
        double roll = Math.toRadians(rollDeg), cos = Math.cos(roll), sin = Math.sin(roll);
        Vec3 r = right.scale(cos).add(up.scale(sin)).scale(half);
        Vec3 u = up.scale(cos).subtract(right.scale(sin)).scale(half);
        vertex(pose, buf, at.subtract(r).add(u), u0, v0, color);
        vertex(pose, buf, at.subtract(r).subtract(u), u0, v1, color);
        vertex(pose, buf, at.add(r).subtract(u), u1, v1, color);
        vertex(pose, buf, at.add(r).add(u), u1, v0, color);
    }

    /**
     * World width kept in proportion to the distance inside 2.5 blocks, so on screen the part at the
     * wand head is no wider than the part a few blocks out and never fans across the view.
     */
    private static float near(Vec3 cam, Vec3 at) {
        return (float) Math.max(.05, Math.min(1, (cam.distanceTo(at) - .1) / 2.4));
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float u, float v, int color) {
        buf.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    // ---- colour -----------------------------------------------------------------------------------

    private static double smooth(double a, double b, double x) {
        double k = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return k * k * (3 - 2 * k);
    }

    private static int argb(float alpha, int rgb) {
        return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static int fade(int argb, float k) {
        return Math.round((argb >>> 24) * Math.max(0, Math.min(1, k))) << 24 | argb & 0xFFFFFF;
    }

    private static int mix(int a, int b, float k) {
        k = Math.max(0, Math.min(1, k));
        int r = Math.round((a >> 16 & 255) * (1 - k) + (b >> 16 & 255) * k);
        int g = Math.round((a >> 8 & 255) * (1 - k) + (b >> 8 & 255) * k);
        int bl = Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return r << 16 | g << 8 | bl;
    }

    // ---- materials --------------------------------------------------------------------------------

    private static RenderType material(String name, String texture, boolean additive, String... defines) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/arcane_ray_" + name)).withFragmentShader(id("core/arcane_ray_fx"))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        for (String define : defines) builder.withShaderDefine(define);
        return RenderType.create("logos_arcane_ray_" + name, RenderSetup.builder(builder.build())
                .withTexture("Sampler0", id("textures/vfx/arcane_ray/" + texture + ".png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    // ---- the hit (Photon) -------------------------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** Local +Y faces {@code outward} (back toward the caster). */
    private static FXRuntime emit(Level level, Vec3 at, Vec3 outward) {
        FXRuntime runtime = IMPACT.createRuntime();
        runtime.getRoot().updatePos(new Vector3f((float) at.x, (float) at.y, (float) at.z));
        runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) outward.x, (float) outward.y, (float) outward.z)));
        runtime.getRoot().updateScale(new Vector3f(1.25f));
        runtime.emit(new Executor(level));
        return runtime;
    }

    private static FX impactEffect() {
        ParticleEmitter cross = stationary("impact_cross", .55f, .7f, 9, 0xC8F5C2FF);
        cross.config.setStartSize(new NumberFunction3(range(.55f, .7f), range(.9f, 1.1f), NumberFunction.constant(1)));
        cross.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), NumberFunction.constant(0)));
        ParticleEmitter rays = stationary("impact_rays", .85f, 1.05f, 8, 0x70D796FF);
        ParticleEmitter halo = stationary("impact_halo", .85f, 1.05f, 12, 0x3AD35DFF);
        ParticleEmitter glow = stationary("impact_glow", .8f, 1, 14, 0x28D35DFF);

        Cone outward = new Cone();
        outward.setRadius(.05f);
        outward.setAngle(70);
        ParticleEmitter streaks = layer(Element.ARCANE, true, 0, 14, size(.02f, .035f), range(3, 6), range(4, 8), outward);
        mask(streaks, "impact_spark", 0xDFFBB6F2);
        streaks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        streaks.config.renderer.setLengthScale(2.5f);
        streaks.config.renderer.setVelocityScale(.06f);

        Cone back = new Cone();
        back.setRadius(.15f);
        back.setAngle(55);
        ParticleEmitter glyphs = layer(Element.ARCANE, true, 0, 4, size(.2f, .28f), range(.5f, 1.1f), range(16, 24), back);
        mask(glyphs, "runes", 0xF0F5C2FF);
        glyphs.config.uvAnimation.setEnable(true);
        glyphs.config.uvAnimation.setTiles(new Vector2i(3, 3));
        glyphs.config.uvAnimation.setStartFrame(range(0, 8.999f));
        glyphs.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        glyphs.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(-25, 25)));

        ParticleEmitter stars = layer(Element.ARCANE, true, 0, 9, size(.07f, .13f), range(.4f, 1.2f), range(9, 18), sphere(.22f));
        mask(stars, "impact_star", 0xDCE6A0FF);
        ParticleEmitter motes = layer(Element.ARCANE, true, 0, 16, size(.013f, .025f), range(.5f, 1.7f), range(10, 20), sphere(.1f));
        mask(motes, "impact_mote", 0xDCFBB6F2);
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(glow, halo, rays, cross, streaks, glyphs, stars, motes));
        return fx;
    }

    private static ParticleEmitter stationary(String texture, float min, float max, float life, int color) {
        ParticleEmitter emitter = layer(Element.ARCANE, true, 0, 1, size(min, max), range(0, 0), range(life, life), sphere(0));
        mask(emitter, texture, color);
        emitter.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        Curve envelope = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(
                0, .3f, .04f, .7f, .12f, 1, .22f, 1,
                .22f, 1, .5f, .95f, .8f, .4f, 1, .05f));
        emitter.config.sizeOverLifetime.setSize(new NumberFunction3(envelope, envelope, envelope));
        return emitter;
    }

    private static void mask(ParticleEmitter emitter, String texture, int color) {
        configureTexture(emitter, "arcane_ray/" + texture, true);
        emitter.config.setDuration(24);
        emitter.config.setMaxParticles(24);
        emitter.config.setStartColor(NumberFunction.color(color));
        emitter.config.colorOverLifetime.setColor(new Gradient(new GradientColor(
                0xFFFFFFFF, 0xDFF9DCFF, 0x80E6A0FF, 0x22C15AFF, 0x00A136EF)));
        emitter.config.physics.setEnable(false);
    }
}
