package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.runtime.Vfx;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.sphere;
import static dev.logos.client.ProjectileParticles.configureTexture;

/**
 * A lightning ray, rebuilt from the burst half of Piloto Studio {@code PS_UltimateBeam_ShockBlue}: a
 * jagged white-hot bolt cracks from the cast point to the end within a tenth of a second, with wild
 * side strands, short forks and a soft blue glow, taking a new shape every tick for about a third of a
 * second. It leaves its ionised channel behind: a thin blue line along the bolt's last shape that
 * re-strikes a few times, then breaks into dashes, while small arcs snap on and off along it, sparser
 * and dimmer until nothing is left after six and a half seconds. A ray stopped by a block or a creature
 * ends in an electric shock; open rays and links just end. Near the camera (a first-person wand) the
 * geometry fades out and the muzzle keeps only a small flash and forward sparks.
 *
 * <p>{@link #beamWake()} keeps only the afterglow, for {@link LightningBeamWake}: no bolt, muzzle or
 * shock, the channel tighter and in the lightning beam's cyan, lit at once under a fading after-image of
 * the beam, re-striking four times and gone after about six seconds. The ray itself is unchanged.
 */
final class LightningRayVisual implements RayVisual {
    /** In ticks: the bolt reaching the end, the bolt's flash, and everything including the afterglow. */
    private static final float REACH = 1.5f, STRIKE = 7, LIFE = 130;
    /** The channel re-strikes at these ages (ticks), each time a little weaker. */
    private static final float[] RESTRIKES = {14, 24, 40};
    private static final int BOLT = 0x7CC4FF, STRAND = 0x4A9BFF, GLOW = 0x2F78FF, ARC = 0xBCEBFF, HOT = 0xDDF2FF;
    private static final RenderType LINE = material("line", "line", "RAY_LINE");
    private static final RenderType BAND = material("band", "line", "RAY_BAND");
    private static final RenderType DOT = material("dot", "glow", "RAY_BAND");
    private static final FX MUZZLE = muzzle(false), MUZZLE_NEAR = muzzle(true), IMPACT = impact();
    /** The beam's wake starts this far into the ray's clock (where the channel takes over), and its after-image lasts this long. */
    private static final float WAKE_LEAD = 4, WAKE_IMAGE = 12;

    /** Everything that differs between a ray's afterglow and a lightning beam's wake. */
    private record Look(boolean wake, double amp, double step, int fresh, int spent, int arc, int halo,
            float[] restrikes, float[] power) {}
    private static final Look RAY = new Look(false, .3, .45, 0x6AAEFF, 0x2A55FF, ARC, STRAND,
            RESTRIKES, new float[] {.8f, .55f, .3f});
    /** The beam (strip 0x57EFFF, ribbons 0x00D5F5, discharges 0xC9FFFF) is straighter and cyan; so is its wake. */
    private static final Look WAKE = new Look(true, .16, .4, 0x57EFFF, 0x1E6BFF, 0xC9FFFF, 0x00BFFF,
            new float[] {9, 18, 30, 46}, new float[] {.7f, .55f, .38f, .22f});

    private final Look look;

    private final List<FXRuntime> playing = new ArrayList<>();
    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO, axis = new Vec3(0, 0, 1);
    private double length;
    private Vfx.RayEnd how = Vfx.RayEnd.OPEN;
    private long seed;
    /** Game time it was fired; ages follow the level's clock, so they pause with it (and with {@code /tick freeze}). */
    private long born, lastTick;
    private boolean struck;
    /** The bolt's last shape, which the afterglow keeps. */
    private Vec3[] channel = new Vec3[0];

    LightningRayVisual() { this(RAY); }

    private LightningRayVisual(Look look) { this.look = look; }

    /** Only the ionised channel, left along a lightning beam's last line (see {@link LightningBeamWake}). */
    static LightningRayVisual beamWake() { return new LightningRayVisual(WAKE); }

    /** The ray's clock: the beam's wake starts where the ray's afterglow begins. */
    private float lead() { return look.wake ? WAKE_LEAD : 0; }

    @Override public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.how = end;
        this.seed = level.getRandom().nextLong();
        this.born = this.lastTick = level.getGameTime();
        // A creature's ray ends at its centre; the bolt stops where it enters the body instead.
        if (end == Vfx.RayEnd.CREATURE) to = surface(level, from, to);
        this.from = from;
        this.to = to;
        this.length = from.distanceTo(to);
        this.axis = length > 1e-4 ? to.subtract(from).scale(1 / length) : new Vec3(0, 0, 1);
        this.channel = trunk(seed ^ (long)(STRIKE - 1), look.amp, look.step);
        if (look.wake) return;
        boolean near = nearCamera(from, 2);
        playing.add(emit(near ? MUZZLE_NEAR : MUZZLE, level, from.add(axis.scale(near ? .25 : .05)), axis, 1));
    }

    @Override
    public boolean tick(Level level) {
        long age = level.getGameTime() - born + (long)lead();
        // The shock goes off as the bolt's head arrives.
        if (!struck && age >= 1 && (how == Vfx.RayEnd.BLOCK || how == Vfx.RayEnd.CREATURE)) {
            struck = true;
            playing.add(emit(IMPACT, level, to.subtract(axis.scale(.08)), axis.scale(-1), how == Vfx.RayEnd.CREATURE ? .8f : 1));
        }
        boolean moved = level.getGameTime() != lastTick;
        lastTick = level.getGameTime();
        // Now and then a spark jumps off the channel while its arcs still crackle.
        float r = afterglow(age);
        if (moved && age > STRIKE && r < .7f && length > .5 && level.getRandom().nextFloat() < .45f * (1 - r)) {
            var rand = level.getRandom();
            Vec3 at = along(channel, .1 + .9 * rand.nextDouble());
            if (!nearCamera(at, 2.5)) level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                    rand.nextGaussian() * .04, rand.nextGaussian() * .04 + .02, rand.nextGaussian() * .04);
        }
        return age < LIFE;
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (length < .05) return;
        float t = level.getGameTime() - born + partialTick + lead();
        Vec3 eye = camera.pos;
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        out.submitCustomGeometry(pose, BAND, (p, buf) -> {
            if (look.wake) afterimage(p, buf, t, eye, false);
            else glow(p, buf, t, eye);
        });
        out.submitCustomGeometry(pose, LINE, (p, buf) -> {
            if (look.wake) afterimage(p, buf, t, eye, true);
            else if (t < STRIKE) strike(p, buf, t, eye);
            channel(p, buf, t, eye);
            arcs(p, buf, t, eye);
        });
        out.submitCustomGeometry(pose, DOT, (p, buf) -> sprites(p, buf, t, camera));
        pose.popPose();
    }

    @Override
    public void end() {
        for (FXRuntime fx : playing) fx.destroy(false);
        playing.clear();
    }

    // ---- the strike -----------------------------------------------------------------------------

    /** 1 while the bolt is fresh, falling to 0 at the end of {@link #STRIKE}. */
    private static float strength(float t) {
        if (t >= STRIKE) return 0;
        return t < 3 ? 1 : (float)Math.pow(1 - (t - 3) / (STRIKE - 3), 1.5);
    }

    /** How far along the ray the bolt has reached, 0–1. */
    private static double reach(float t) { return Math.min(1, t / REACH); }

    /** A beam's wake always ends where the beam did (its terminus stood there). */
    private boolean pinned() { return look.wake || how != Vfx.RayEnd.OPEN; }

    /** Arcs keep crawling over the struck point; a beam's wake keeps them where its terminus was. */
    private boolean struckEnd() { return look.wake || how == Vfx.RayEnd.BLOCK || how == Vfx.RayEnd.CREATURE; }

    /** Lines leave the cast point thin and reach full width a couple of blocks out. */
    private double muzzle(double s) { return .35 + .65 * Math.min(1, s * length / 2.5); }

    /** An open ray thins out into the air over its last stretch. */
    private double tip(double s) {
        if (pinned()) return 1;
        double left = (1 - s) * length;
        return Math.max(0, Math.min(1, left / Math.min(2.5, length * .3)));
    }

    private void strike(PoseStack.Pose pose, VertexConsumer buf, float t, Vec3 eye) {
        float k = strength(t);
        long frame = (long)Math.floor(t);
        double head = reach(t);
        Vec3[] main = cut(trunk(seed ^ frame, .3, .45), head);
        double width = .14 * (.55 + .45 * k);
        ribbon(pose, buf, main, eye, s -> width * tip(s * head) * muzzle(s * head), s -> k * tip(s * head), BOLT, 1);
        // Two wilder strands wrapped around the bolt (ElectricNoises).
        for (int i = 1; i <= 2; i++) {
            Vec3[] strand = cut(trunk(seed ^ (frame * 31 + i * 7919L), .5, .22), head);
            ribbon(pose, buf, strand, eye, s -> .035 * tip(s * head) * muzzle(s * head), s -> .75 * k * tip(s * head), STRAND, 1.2);
        }
        // Short forks off the bolt, tapering to nothing.
        int forks = Math.min(10, 2 + (int)(length / 5));
        for (int i = 0; i < forks; i++) {
            long fs = seed ^ (frame * 131 + i * 977L);
            double at = .08 + .84 * unit(fs, 1, 0);
            if (at > head || main.length < 2 || at * length < 3) continue;
            Vec3 base = along(main, at / head);
            double angle = Math.toRadians(25 + 30 * unit(fs, 2, 0)), turn = Math.PI * 2 * unit(fs, 3, 0);
            Vec3 dir = axis.scale(Math.cos(angle)).add(perpendicular(turn).scale(Math.sin(angle)));
            double out = Math.min(.5 + 1.3 * unit(fs, 4, 0), length * .3);
            Vec3[] fork = bolt(fs, base, base.add(dir.scale(out)), .14, .15, false, null);
            ribbon(pose, buf, fork, eye, s -> .05 * (1 - s), s -> .8 * k * (1 - s * .6), BOLT);
        }
    }

    /** MA_Laser_01: a wide soft blue band along the ray, gone with the strike. */
    private void glow(PoseStack.Pose pose, VertexConsumer buf, float t, Vec3 eye) {
        float k = strength(t);
        if (k <= 0) return;
        double head = reach(t);
        Vec3[] band = new Vec3[9];
        for (int i = 0; i < band.length; i++) band[i] = from.lerp(to, head * i / (band.length - 1));
        ribbon(pose, buf, band, eye, s -> .5 * (.6 + .4 * k) * tip(s * head) * muzzle(s * head),
                s -> .45 * k * tip(s * head) * muzzle(s * head), GLOW, 4);
    }

    /**
     * A beam's wake only: the beam's after-image along its straight line, a wide soft cyan band and a
     * bright core that thin and fade over {@link #WAKE_IMAGE} ticks while the channel shows through.
     */
    private void afterimage(PoseStack.Pose pose, VertexConsumer buf, float t, Vec3 eye, boolean core) {
        float k = 1 - (t - WAKE_LEAD) / WAKE_IMAGE;
        if (k <= 0) return;
        float e = k * k;
        Vec3[] line = new Vec3[13];
        for (int i = 0; i < line.length; i++) line[i] = from.lerp(to, (double)i / (line.length - 1));
        if (core) ribbon(pose, buf, line, eye, s -> .06 * (.3 + .7 * k) * muzzle(s), s -> .9 * e, 0xC9FFFF, 5);
        else ribbon(pose, buf, line, eye, s -> .42 * (.45 + .55 * k) * muzzle(s), s -> .5 * e * muzzle(s), 0x2EBEFF, 8);
    }

    // ---- the afterglow --------------------------------------------------------------------------

    /** 0 when the afterglow starts, 1 when it is gone. */
    private static float afterglow(float t) { return Math.max(0, Math.min(1, (t - 4) / (LIFE - 4))); }

    /** Extra brightness while the channel re-strikes. */
    private float restrike(float t) {
        for (int i = 0; i < look.restrikes.length; i++) {
            float d = t - look.restrikes[i];
            if (d >= 0 && d < 1.5f) return (1 - d / 1.5f) * look.power[i];
        }
        return 0;
    }

    /** The ionised channel: the bolt's last shape in thin blue, breaking into dashes that go out one by one. */
    private void channel(PoseStack.Pose pose, VertexConsumer buf, float t, Vec3 eye) {
        float r = afterglow(t);
        if (t < 4 || r >= 1) return;
        float flash = restrike(t);
        float fade = (float)Math.pow(1 - r, 1.1);
        // A fresh channel fades in under the dying bolt.
        float in = look.wake ? 1 : Math.min(1, (t - 4) / 3);
        long tick = (long)Math.floor(t);
        int dashes = Math.max(2, (int)Math.ceil(length / 1.4));
        int color = lerpColor(look.fresh, look.spent, r);
        for (int i = 0; i < dashes; i++) {
            // Each dash goes dark for good at its own moment; the restrikes light all of them again.
            double life = unit(seed, 100 + i, 0);
            boolean lit = life > Math.pow(r, .8) * 1.15 - .1;
            if (!lit && flash <= 0) continue;
            double flicker = .5 + .5 * unit(seed, 200 + i, tick);
            float a = (float)((lit ? .7 * fade * flicker * in : 0) + flash);
            double s0 = (double)i / dashes, s1 = (i + .82) / dashes;
            Vec3[] dash = slice(channel, s0, s1);
            double w = .028 + .035 * flash;
            ribbon(pose, buf, dash, eye, s -> w * tip(s0 + (s1 - s0) * s) * muzzle(s0 + (s1 - s0) * s),
                    s -> a * tip(s0 + (s1 - s0) * s), color, look.wake ? 4 : 2.5);
        }
    }

    /** Small arcs snapping on and off along the channel (and around the struck point), ever fewer. */
    private void arcs(PoseStack.Pose pose, VertexConsumer buf, float t, Vec3 eye) {
        float r = afterglow(t);
        if (t < 5 || r >= 1) return;
        long slot = (long)Math.floor(t / 2);
        float second = (t / 2 - slot) > .5f ? .55f : 1;
        float chance = .6f * (float)Math.pow(1 - r, 1.2);
        int sites = Math.max(3, (int)(length / 1.3));
        for (int i = 0; i < sites; i++) {
            long as = seed ^ (i * 7_368_787L + slot * 1_299_721L);
            if (unit(as, 1, 0) > chance) continue;
            double c = (i + .5 + (unit(as, 2, 0) - .5) * .8) / sites;
            if (c * length < 2.5) continue;
            double half = (.25 + .3 * unit(as, 3, 0)) / Math.max(length, 1);
            Vec3 a = along(channel, c - half), b = along(channel, c + half);
            Vec3 bow = perpendicular(Math.PI * 2 * unit(as, 4, 0)).scale(.08 + .2 * unit(as, 5, 0));
            Vec3[] arc = bolt(as, a, b, .1, .09, true, bow);
            float alpha = .9f * second * (float)Math.pow(1 - r, .7) * (float)tip(c);
            ribbon(pose, buf, arc, eye, s -> .03, s -> alpha, look.arc);
        }
        if (!struckEnd()) return;
        // Arcs still crawling over the struck point.
        for (int i = 0; i < 3; i++) {
            long as = seed ^ (i * 92_821L + slot * 6_291_469L + 5);
            if (unit(as, 1, 0) > .8f * Math.pow(1 - r, 1.2)) continue;
            double turn = Math.PI * 2 * unit(as, 2, 0);
            Vec3 out = axis.scale(-.6).add(perpendicular(turn)).normalize().scale(.35 + .45 * unit(as, 3, 0));
            Vec3 a = to.subtract(axis.scale(.05));
            Vec3[] arc = bolt(as, a, a.add(out), .1, .08, false, null);
            float alpha = .9f * second * (float)Math.pow(1 - r, .8);
            ribbon(pose, buf, arc, eye, s -> .03 * (1 - s * .7), s -> alpha * (1 - s * .5), look.arc);
        }
    }

    /** Glows: the bolt's racing head, the restrikes, and a soft halo on every lit arc. */
    private void sprites(PoseStack.Pose pose, VertexConsumer buf, float t, CameraRenderState camera) {
        if (!look.wake && t < REACH + 1.5f) {
            double head = reach(t);
            float a = t < REACH ? 1 : 1 - (t - REACH) / 1.5f;
            if (head < 1 || pinned()) sprite(pose, buf, camera, from.lerp(to, head), .42 * (.6 + .4 * a), a, HOT);
        }
        float r = afterglow(t);
        if (t < 5 || r >= 1) return;
        long slot = (long)Math.floor(t / 2);
        float chance = .6f * (float)Math.pow(1 - r, 1.2);
        int sites = Math.max(3, (int)(length / 1.3));
        for (int i = 0; i < sites; i++) {
            long as = seed ^ (i * 7_368_787L + slot * 1_299_721L);
            if (unit(as, 1, 0) > chance) continue;
            double c = (i + .5 + (unit(as, 2, 0) - .5) * .8) / sites;
            if (c * length < 2.5) continue;
            sprite(pose, buf, camera, along(channel, c), .45, .32f * (float)Math.pow(1 - r, .7) * (float)tip(c), look.halo);
        }
        float flash = restrike(t);
        if (flash > 0 && struckEnd()) sprite(pose, buf, camera, to, .9, flash * .8f, look.halo);
    }

    // ---- geometry -------------------------------------------------------------------------------

    /**
     * A jagged line from {@code a} to {@code b}: a slow wander plus a zig-zag at every joint, pinned to
     * {@code a} (and to {@code b} when {@code pinEnd}), optionally bowed out sideways by {@code bow}.
     */
    private static Vec3[] bolt(long seed, Vec3 a, Vec3 b, double amp, double step, boolean pinEnd, Vec3 bow) {
        return bolt(seed, a, b, amp, step, pinEnd, bow, Math.min(.6, a.distanceTo(b) * .25));
    }

    /** The bolt along the whole ray; it only swings out to full width a few blocks from the cast point. */
    private Vec3[] trunk(long seed, double amp, double step) {
        return bolt(seed, from, to, amp, step, pinned(), null, Math.min(3, length * .3));
    }

    private static Vec3[] bolt(long seed, Vec3 a, Vec3 b, double amp, double step, boolean pinEnd, Vec3 bow, double grip) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1e-4) return new Vec3[] {a, b};
        Vec3 dir = d.scale(1 / len), right = side(dir), up = dir.cross(right).normalize();
        int n = (int)Math.max(3, Math.min(220, Math.ceil(len / step)));
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double s = (double)i / n;
            double env = Math.min(1, s * len / grip) * (pinEnd ? Math.min(1, (1 - s) * len / Math.min(grip, .6)) : 1);
            double x = smooth(seed, 1, s * len / 2.5) * .65 + (unit(seed, 3, i) * 2 - 1) * .45;
            double y = smooth(seed, 2, s * len / 2.5) * .65 + (unit(seed, 4, i) * 2 - 1) * .45;
            Vec3 p = a.add(d.scale(s)).add(right.scale(x * amp * env)).add(up.scale(y * amp * env));
            if (bow != null) p = p.add(bow.scale(Math.sin(Math.PI * s)));
            pts[i] = p;
        }
        if (pinEnd) pts[n] = b;
        pts[0] = a;
        return pts;
    }

    /** The first {@code part} (0–1) of a polyline. */
    private static Vec3[] cut(Vec3[] pts, double part) {
        if (part >= 1) return pts;
        int n = Math.max(1, (int)Math.ceil(part * (pts.length - 1)));
        Vec3[] out = new Vec3[n + 1];
        System.arraycopy(pts, 0, out, 0, n);
        out[n] = along(pts, part);
        return out;
    }

    /** The stretch of a polyline between fractions {@code s0} and {@code s1} of its joints. */
    private static Vec3[] slice(Vec3[] pts, double s0, double s1) {
        int last = pts.length - 1;
        List<Vec3> out = new ArrayList<>();
        out.add(along(pts, s0));
        for (int i = (int)Math.ceil(s0 * last); i <= (int)Math.floor(s1 * last); i++)
            if (i > s0 * last && i < s1 * last) out.add(pts[i]);
        out.add(along(pts, s1));
        return out.toArray(Vec3[]::new);
    }

    /** The point a fraction {@code s} of the way along a polyline's joints. */
    private static Vec3 along(Vec3[] pts, double s) {
        if (pts.length == 0) return Vec3.ZERO;
        if (pts.length == 1) return pts[0];
        double f = Math.max(0, Math.min(1, s)) * (pts.length - 1);
        int i = Math.min(pts.length - 2, (int)Math.floor(f));
        return pts[i].lerp(pts[i + 1], f - i);
    }

    /** A camera-facing strip along a polyline; width and alpha are functions of the fraction along it. */
    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3[] pts, Vec3 eye,
            DoubleUnaryOperator width, DoubleUnaryOperator alpha, int rgb) {
        ribbon(pose, buf, pts, eye, width, alpha, rgb, 2.5);
    }

    /** As above, fully faded in only {@code near} blocks from the camera. */
    private static void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3[] pts, Vec3 eye,
            DoubleUnaryOperator width, DoubleUnaryOperator alpha, int rgb, double near) {
        int n = pts.length;
        if (n < 2) return;
        Vec3[] left = new Vec3[n], right = new Vec3[n];
        int[] color = new int[n];
        for (int i = 0; i < n; i++) {
            Vec3 tangent = pts[Math.min(n - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 across = tangent.cross(eye.subtract(pts[i]));
            if (across.lengthSqr() < 1e-12) across = side(tangent.lengthSqr() < 1e-12 ? new Vec3(0, 0, 1) : tangent.normalize());
            double s = (double)i / (n - 1);
            across = across.normalize().scale(width.applyAsDouble(s));
            left[i] = pts[i].add(across);
            right[i] = pts[i].subtract(across);
            float a = (float)Math.max(0, Math.min(1, alpha.applyAsDouble(s))) * fadeNear(pts[i], eye, near);
            color[i] = (int)(a * 255) << 24 | rgb & 0xFFFFFF;
        }
        for (int i = 0; i < n - 1; i++) {
            if (color[i] >>> 24 == 0 && color[i + 1] >>> 24 == 0) continue;
            float u0 = i * .25f, u1 = (i + 1) * .25f;
            vertex(pose, buf, left[i], u0, 0, color[i]);
            vertex(pose, buf, right[i], u0, 1, color[i]);
            vertex(pose, buf, right[i + 1], u1, 1, color[i + 1]);
            vertex(pose, buf, left[i + 1], u1, 0, color[i + 1]);
        }
    }

    private static void sprite(PoseStack.Pose pose, VertexConsumer buf, CameraRenderState camera, Vec3 at, double size, float alpha, int rgb) {
        alpha *= fadeNear(at, camera.pos, 2.5);
        if (alpha <= .004f) return;
        int color = (int)(Math.min(1, alpha) * 255) << 24 | rgb & 0xFFFFFF;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(size);
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(size);
        vertex(pose, buf, at.subtract(right).add(up), 0, 0, color);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1, color);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1, color);
        vertex(pose, buf, at.add(right).add(up), 1, 0, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float u, float v, int color) {
        buf.addVertex(pose, (float)at.x, (float)at.y, (float)at.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** Geometry within reach of the camera (a first-person wand) fades out so it never covers the view. */
    private static float fadeNear(Vec3 at, Vec3 eye, double near) {
        double s = Math.max(0, Math.min(1, (at.distanceTo(eye) - .3) / (near - .3)));
        return (float)(s * s * (3 - 2 * s));
    }

    private Vec3 perpendicular(double turn) {
        Vec3 right = side(axis), up = axis.cross(right).normalize();
        return right.scale(Math.cos(turn)).add(up.scale(Math.sin(turn)));
    }

    private static Vec3 side(Vec3 dir) {
        Vec3 right = dir.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = dir.cross(new Vec3(1, 0, 0));
        return right.normalize();
    }

    /** A hash of three numbers, in [0, 1). */
    private static double unit(long seed, long a, long b) {
        long h = seed ^ a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth value noise in [-1, 1]. */
    private static double smooth(long seed, long channel, double x) {
        long i = (long)Math.floor(x);
        double f = x - i, s = f * f * (3 - 2 * f);
        double a = unit(seed, channel * 1000 + 17, i), b = unit(seed, channel * 1000 + 17, i + 1);
        return (a + (b - a) * s) * 2 - 1;
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int)((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int)((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int)((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** Where the line from {@code from} enters the creature whose centre is {@code centre} (a little inside). */
    private static Vec3 surface(Level level, Vec3 from, Vec3 centre) {
        for (Entity e : level.getEntities((Entity)null, new AABB(centre, centre).inflate(.3))) {
            AABB box = e.getBoundingBox();
            if (!box.inflate(.05).contains(centre) || box.contains(from)) continue;
            var hit = box.clip(from, centre);
            if (hit.isPresent()) return hit.get().add(centre.subtract(from).normalize().scale(.1));
        }
        return centre;
    }

    private static boolean nearCamera(Vec3 at, double within) {
        Entity viewer = Minecraft.getInstance().getCameraEntity();
        return viewer != null && Minecraft.getInstance().options.getCameraType().isFirstPerson()
                && viewer.getEyePosition().distanceTo(at) < within;
    }

    // ---- Photon: muzzle and impact --------------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** Plays {@code template} at {@code at} with local +Y along {@code dir}. */
    private static FXRuntime emit(FX template, Level level, Vec3 at, Vec3 dir, float scale) {
        FXRuntime runtime = template.createRuntime();
        runtime.getRoot().updatePos(new Vector3f((float)at.x, (float)at.y, (float)at.z));
        runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float)dir.x, (float)dir.y, (float)dir.z).normalize()));
        runtime.getRoot().updateScale(new Vector3f(scale));
        runtime.emit(new Executor(level));
        return runtime;
    }

    /**
     * The muzzle (Flare_FatDot, AnimeShock, AlphaFuzzball and the forward sparks); near the camera only a
     * small flash and the sparks, so a first-person wand never flashes over the view.
     */
    private static FX muzzle(boolean near) {
        FX fx = new FX();
        var objects = fx.getFxData().objects();
        objects.add(flash(near ? .14f : .45f, near ? 2 : 3));
        objects.add(sparks(near ? 5 : 9, 25, range(6, 11), range(3, 6), .2f));
        if (!near) {
            objects.add(shock(.85f, 7, 0xE04A9BFF));
            objects.add(halo(1.0f, 6, 0x803C8CFF));
        }
        return fx;
    }

    /** The shock where a ray strikes: flash, AnimeShock, halo, arcs and sparks thrown back toward the caster. */
    private static FX impact() {
        FX fx = new FX();
        var objects = fx.getFxData().objects();
        objects.add(flash(.75f, 3));
        objects.add(shock(1.4f, 8, 0xF04A9BFF));
        objects.add(halo(1.6f, 8, 0x803C8CFF));
        objects.add(sparks(16, 75, range(4, 9), range(6, 11), .25f));
        ParticleEmitter arcs = burst("electric", 3, range(.7f, 1.0f), range(5, 7), sphere(.25f), 0xF09ACBFF);
        flipbook(arcs, 4, 2);
        fade(arcs, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        objects.add(arcs);
        return fx;
    }

    private static ParticleEmitter flash(float size, float life) {
        ParticleEmitter e = burst("flare", 1, range(size, size), range(life, life), sphere(0), 0xFFDDF2FF);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .8f, .7f, .3f, 1, 0)));
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    private static ParticleEmitter shock(float size, float life, int color) {
        ParticleEmitter e = burst("shock", 1, range(size, size), range(life, life), sphere(0), color);
        flipbook(e, 4, 4);
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF, 0x80FFFFFF);
        return e;
    }

    private static ParticleEmitter halo(float size, float life, int color) {
        ParticleEmitter e = burst("glow", 1, range(size, size), range(life, life), sphere(0), color);
        envelope(e, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, 1, .3f, .8f, .7f, .4f, 1, 0)));
        fade(e, 0xFFFFFFFF, 0x00FFFFFF);
        return e;
    }

    /** Flare_FatDot streaks along local +Y, within {@code angle} degrees; {@code speed} in blocks per second. */
    private static ParticleEmitter sparks(int count, float angle, RandomConstant speed, RandomConstant life, float slow) {
        Cone cone = new Cone();
        cone.setRadius(.05f);
        cone.setAngle(angle);
        ParticleEmitter e = burst("flare", count, range(.035f, .06f), life, cone, 0xFF82BFFF);
        e.config.setStartSpeed(speed);
        e.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        e.config.renderer.setLengthScale(3);
        e.config.renderer.setVelocityScale(.05f);
        fade(e, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed",
                new ECBCurves(0, 1, .3f, .5f, .6f, slow, 1, slow)));
        return e;
    }

    /** One additive, full-bright, non-looping layer of {@code lightning_ray/<texture>}. */
    private static ParticleEmitter burst(String texture, int count, RandomConstant size, RandomConstant life, IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "lightning_ray/" + texture, true);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(32);
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.physics.setEnable(false);
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        c.emission.getBursts().add(b);
        return e;
    }

    private static void flipbook(ParticleEmitter e, int columns, int rows) {
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(columns, rows));
        e.config.uvAnimation.setStartFrame(NumberFunction.constant(0));
        e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, columns * rows - 1, "age", "frame",
                new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        e.config.sizeOverLifetime.setEnable(false);
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }

    // ---- custom geometry material ---------------------------------------------------------------

    private static RenderType material(String name, String texture, String define) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/lightning_ray_" + name)).withFragmentShader(id("core/lightning_ray_line"))
                .withShaderDefine(define).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING));
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/lightning_ray/" + texture + ".png"));
        return RenderType.create("logos_lightning_ray_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
