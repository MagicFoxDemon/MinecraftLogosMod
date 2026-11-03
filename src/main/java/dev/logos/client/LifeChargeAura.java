package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The colossal life charge's aura in place of the shared pillar (CHARGE_VFX.md), after Unreal _ChargeGatherVFX
 * NS_Charge_InvokeWind (broken down in art/charge/gather/life/notes.md): wind called up round the caster.
 * <ul>
 * <li>Long wind spirals (emitter {@code v}): a two-turn spiral mesh whose tail is flung out sideways and fanned
 * wide, a bright stretch of panned noise running in from the tail and up round the body (M_Vfx_FocusBelt).</li>
 * <li>Short wider spirals ({@code Fountain003}): one turn further out, their noise racing faster.</li>
 * <li>Motes ({@code Fountain008}): small specks swirled round and out by a vortex, stretched along their motion,
 * in life's teal, green and gold (the life beam's motes, {@code life_ray/dot}).</li>
 * <li>On release, five big spirals sweep their light from the ground to over the head once, growing as they go
 * ({@code v001}, the system's "final boost" when it is deactivated).</li>
 * <li>Life's own touch: now and then a leaf (the life beam's, {@code life_ray/leaves}) rides the light of a long
 * spiral, whirled up round the caster with it.</li>
 * </ul>
 * Opening bursts play when the charge begins; the rest keeps coming while it charges and is held, as strong as
 * {@link ChargeBody#aura} says. Sizes follow the caster's height, times {@link #AURA_SCALE}. In first person
 * whatever comes near the eyes fades out, so the spirals wrapping the head and the motes passing the camera do
 * not fill the view.
 */
final class LifeChargeAura {
    /** Wind over the panned noise (shaders/core/life_charge_aura_wind), blended premultiplied: covering and glowing. */
    static final RenderType WIND = wind();

    /**
     * The whole aura's size against the Niagara system on a caster of the player's height: every position, size,
     * height, radius, speed and drift (the system's origin included) is multiplied by it, so it keeps its shape.
     * First person still fades by the caster's own size: what comes near the eyes goes, whatever the aura's size.
     */
    static final float AURA_SCALE = 1.5f;
    private static final int SPIRAL_CAP = 16, STREAK_CAP = 14, MOTE_CAP = 130;
    /**
     * Where the Niagara system sits above the feet (the emitters' offsets are measured from it), before
     * {@link #AURA_SCALE}: chosen so the streaks span knees to over the head and the flung-out tails cross the
     * body, as in the reference.
     */
    private static final float ORIGIN = 1.25f;
    /** The motes: life's teal (most, like the reference's cyan specks), green, and now and then gold. */
    private static final int[] MOTE = {0x4BE8A8, 0x4BE8A8, 0x5CF0C0, 0x7FF04A, 0xE8D25A};

    private enum Kind {
        /** Emitter v: SM_SpiralStreak_prolong_Thick_UV_Smooth_new, flared (vfx_meshes/life_charge/wind_spiral). */
        SPIRAL,
        /** Emitter Fountain003: SM_SpiralStreak, its tail fanned (vfx_meshes/life_charge/wind_streak). */
        STREAK,
        /** Emitter v001: the long spiral again, lit only on release. */
        BOOST
    }

    /** One spiral mesh particle and the FocusBelt material's dynamic parameters it was born with. */
    private record Streak(Kind kind, double born, float life, float yaw, float base, float sxy, float sz, float trail,
            float colorPow, float tileU, float tileV, float panU, float seedU, float seedV, int tint, boolean opening,
            int leafTint, int leafFrame, float leafSize, float leafSpin, float leafRoll) {}

    /** A mote, as an offset from the caster's feet (now and a tick ago) and its velocity, in blocks and ticks. */
    private static final class Mote {
        double x, y, z, px, py, pz;
        final double born;
        final float life, width, swirl, out, phase;
        final int color;

        Mote(double x, double y, double z, double born, float life, float width, float swirl, float out, float phase, int color) {
            this.x = px = x;
            this.y = py = y;
            this.z = pz = z;
            this.born = born;
            this.life = life;
            this.width = width;
            this.swirl = swirl;
            this.out = out;
            this.phase = phase;
            this.color = color;
        }
    }

    private final ChargeStyle style;
    private final int[] leaves;
    private final List<Streak> streaks = new ArrayList<>();
    private final List<Streak> boost = new ArrayList<>();
    private final List<Mote> motes = new ArrayList<>();
    private boolean started;
    private float owedSpirals, owedStreaks, owedMotes;

    /** {@code leaves}: the tints of the leaves riding the wind. */
    LifeChargeAura(ChargeStyle style, int[] leaves) {
        this.style = style;
        this.leaves = leaves;
    }

    /** One client tick. False once there is nothing left to draw. */
    boolean tick(Level level, LiveCharge c) {
        if (c.tier() != 3) return false;
        double now = c.age(0);
        float scale = c.height() / 1.8f * AURA_SCALE;
        RandomSource r = level.getRandom();
        streaks.removeIf(s -> now - s.born > s.life);
        motes.removeIf(m -> now - m.born > m.life);
        for (Mote m : motes) step(m, now, scale);
        LiveCharge.Phase phase = c.phase();
        if (!started) {
            started = true;
            // v001 is spawned with the system and waits, dark, for the deactivation that lights it.
            for (int i = 0; i < 5; i++) boost.add(streak(r, Kind.BOOST, now, (i + r.nextFloat() * .6f) / 5, false));
            // The opening bursts (SpawnBurst_Instantaneous at the system's start), for a charge seen from its beginning.
            if (phase == LiveCharge.Phase.CHARGING && c.progress(0) < .3f) {
                for (int i = 0; i < 5; i++) streaks.add(streak(r, Kind.SPIRAL, now, (i + r.nextFloat() * .5f) / 5, true));
                for (int i = 0; i < 5; i++) streaks.add(streak(r, Kind.STREAK, now, (i + r.nextFloat() * .5f) / 5, true));
                for (int i = 0; i < 40; i++) motes.add(mote(r, now, scale));
            }
        }
        if (phase.over()) return ChargeBody.aura(c, 0) != null;
        ChargeBody.Aura aura = ChargeBody.aura(c, 0);
        float more = aura == null ? 0 : Math.min(1, aura.strength());
        // SpawnRate 8/s (v), 7/s (Fountain003); the motes' 300/s thinned to 90/s for several casters at once.
        for (owedSpirals += .4f * more; owedSpirals >= 1; owedSpirals--)
            if (count(Kind.SPIRAL) < SPIRAL_CAP) streaks.add(streak(r, Kind.SPIRAL, now, r.nextFloat(), false));
        for (owedStreaks += .35f * more; owedStreaks >= 1; owedStreaks--)
            if (count(Kind.STREAK) < STREAK_CAP) streaks.add(streak(r, Kind.STREAK, now, r.nextFloat(), false));
        for (owedMotes += 4.5f * more; owedMotes >= 1; owedMotes--)
            if (motes.size() < MOTE_CAP) motes.add(mote(r, now, scale));
        return true;
    }

    void clear() {
        streaks.clear();
        boost.clear();
        motes.clear();
    }

    private int count(Kind kind) {
        int n = 0;
        for (Streak s : streaks) if (s.kind == kind) n++;
        return n;
    }

    /**
     * A spiral with its InitializeParticle and DynamicMaterialParameters values: lifetime 1.4-1.75 s, a turn
     * {@code turn} round (in turns), mesh scale (MeshScale x _Size 2.25 x 0.5 x 1-1.1), height on the emitter's
     * cylinder (offset and height x _Size, from the system's origin), Trail_Length_Control, ColorPOW's end value,
     * the noise's tiling and pan.
     */
    private Streak streak(RandomSource r, Kind kind, double now, float turn, boolean opening) {
        float grow = 1.125f * (1 + .1f * r.nextFloat());
        float yaw = turn * Mth.TWO_PI;
        float life = 28 + 7 * r.nextFloat();
        float lift = (r.nextFloat() - .5f) * 1.125f;
        int tint = lerpColor(style.main(), style.light(), r.nextInt(4) == 0 ? .3f + .15f * r.nextFloat() : .1f * r.nextFloat());
        return switch (kind) {
            case SPIRAL -> {
                boolean leaf = r.nextFloat() < .45f;
                yield new Streak(kind, now, life, yaw, Math.max(.02f, ORIGIN - .9f + lift), 1.2f * grow, .75f * grow,
                        1.5f + .5f * r.nextFloat(), 2 + 2 * r.nextFloat(), 1 + .5f * r.nextFloat(), .5f + .25f * r.nextFloat(),
                        .5f + .25f * r.nextFloat(), r.nextFloat(), r.nextFloat(), tint, opening,
                        leaf ? leaves[r.nextInt(5) == 0 ? leaves.length - 1 : r.nextInt(leaves.length - 1)] : -1, r.nextInt(4),
                        .1f + .05f * r.nextFloat(), (r.nextFloat() - .5f) * .3f, r.nextFloat() * Mth.TWO_PI);
            }
            case STREAK -> new Streak(kind, now, life, yaw, Math.max(.02f, ORIGIN - .45f + lift), 1.8f * grow, grow,
                    1.7f + .3f * r.nextFloat(), 1 + r.nextFloat(), 1 + .5f * r.nextFloat(), .5f + .25f * r.nextFloat(),
                    2 + 2 * r.nextFloat(), r.nextFloat(), r.nextFloat(), lerpColor(tint, style.deep(), .25f * r.nextFloat()),
                    opening, -1, 0, 0, 0, 0);
            case BOOST -> new Streak(kind, now, Float.MAX_VALUE, yaw, Mth.clamp(ORIGIN - 1.35f + lift, 0, .3f), 1.3f * grow, .75f * grow,
                    1.5f + .5f * r.nextFloat(), 2 + 2 * r.nextFloat(), 1 + .5f * r.nextFloat(), .5f + .25f * r.nextFloat(),
                    .5f + .25f * r.nextFloat(), r.nextFloat(), r.nextFloat(), lerpColor(style.main(), style.light(), .35f), false,
                    -1, 0, 0, 0, 0);
        };
    }

    /**
     * A mote (Fountain008): born in a cylinder 0.8 m round and 1.7 m tall about the system's origin, living
     * 0.5-1.5 s, carried round by the vortex and out by its falloff, stirred by curl noise.
     */
    private Mote mote(RandomSource r, double now, float scale) {
        double a = r.nextDouble() * Math.PI * 2, rr = .8 * Math.sqrt(r.nextDouble()) * scale;
        double y = Math.max(.05, ORIGIN - .225 + (r.nextDouble() - .5) * 1.7) * scale;
        return new Mote(Math.cos(a) * rr, y, Math.sin(a) * rr, now, 10 + 20 * r.nextFloat(), (.036f + .024f * r.nextFloat()) * scale,
                (.05f + .07f * r.nextFloat()) * scale, (.012f + .035f * r.nextFloat()) * scale, r.nextFloat() * 6.28f,
                MOTE[r.nextInt(MOTE.length)]);
    }

    private static void step(Mote m, double now, float scale) {
        m.px = m.x;
        m.py = m.y;
        m.pz = m.z;
        double r = Math.max(.05, Math.hypot(m.x, m.z));
        double tx = -m.z / r, tz = m.x / r, ox = m.x / r, oz = m.z / r;
        double t = now - m.born;
        // Vortex velocity round the upright axis, a drift outwards, and a slow curl-like wander.
        double wx = Math.sin(t * .21 + m.phase) * .012 * scale, wy = Math.sin(t * .17 + m.phase * 1.3) * .008 * scale;
        double wz = Math.cos(t * .19 + m.phase * .7) * .012 * scale;
        m.x += tx * m.swirl + ox * m.out + wx;
        m.z += tz * m.swirl + oz * m.out + wz;
        m.y = Math.max(.03 * scale, m.y + wy);
    }

    // ---- drawing -------------------------------------------------------------------------------

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        if (c.tier() != 3) return;
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        if (aura == null) return;
        Vec3 feet = c.feet(pt);
        float scale = c.height() / 1.8f * AURA_SCALE;
        double now = c.age(pt);
        float seconds = (float) (now / 20);
        boolean fp = c.firstPerson();
        float strength = aura.strength(), widen = aura.widen();
        // The release sweep's own clock: the aura's widening runs 1 to 1.5 over its last flash.
        float sweep = c.phase() == LiveCharge.Phase.RELEASED ? Mth.clamp((widen - 1) / .5f, 0, 1) : -1;
        out.submitCustomGeometry(stack, WIND, (pose, buf) -> {
            for (Streak s : streaks) {
                float k = (float) ((now - s.born) / s.life);
                float show = s.opening ? Math.max(strength, .6f) : strength;
                if (k >= 0 && k <= 1) wind(pose, buf, s, feet, scale, k, seconds, show, widen, view.cam(), fp);
            }
            if (sweep >= 0) for (Streak s : boost) wind(pose, buf, s, feet, scale, sweep, seconds, 1, 1, view.cam(), fp);
        });
        riders(stack, out, view, feet, scale, now, strength, widen, fp);
        motes(stack, out, view, feet, pt, now, strength, fp);
    }

    /** The FocusBelt material over one spiral, its curves read at {@code k} of its life. */
    private static void wind(PoseStack.Pose pose, VertexConsumer buf, Streak s, Vec3 feet, float scale, float k, float seconds,
            float show, float widen, Vec3 cam, boolean fp) {
        float alpha = alphaCurve(s.kind, k) * show;
        if (alpha < .002f) return;
        ChargeAuraMesh mesh = mesh(s.kind);
        Matrix4f m = transform(s, scale, k, widen);
        Vec3 origin = feet.add(0, s.base * scale, 0);
        float travel = travel(s.kind, k), bright = brightness(s.kind, k), sidePow = sideFade(s.kind, k), trail = 2 * s.trail;
        int colorPow = Math.round(s.colorPow * k * 4);
        float u0 = 8 * colorPow + frac(s.panU * seconds + s.seedU);
        // Near the eyes (first person) or right at the camera, the streaks fade out.
        double lo = fp ? 1.3 * scale / AURA_SCALE : .3, hi = fp ? 2.6 * scale / AURA_SCALE : .9;
        boolean near = fp || cam.distanceToSqr(origin) < 25;
        double cx = cam.x - origin.x, cy = cam.y - origin.y, cz = cam.z - origin.z;
        // Scale RGB is x13 on an HDR tint in Unreal: saturated as soon as it is more than a tenth of its peak.
        int glow = lerpColor(0, s.tint, Math.min(1, bright * 10));
        Vector3f at = new Vector3f();
        mesh.draw(pose, buf, origin, m, false, u0, s.seedV, s.tileU, s.tileV, (x, y, z, u, v) -> {
            float side = side(u, sidePow);
            float du = trail * u - travel, dv = v - .5f;
            float d = Mth.sqrt(du * du + dv * dv) * 2;
            if (d >= 1 || side <= 0) return 0;
            // RadialGradientExponential round (Travel, 0.5) in (2 U Trail_Length_Control, V), radius 0.5, density 2.33.
            float cover = alpha * (float) Math.pow(1 - d, 2.33) * side;
            if (near) {
                m.transformPosition(x, y, z, at);
                double dx = at.x - cx, dy = at.y - cy, dz = at.z - cz;
                cover *= (float) ChargeParts.smooth(lo, hi, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            return argb(cover / 4, glow);
        });
    }

    private static Matrix4f transform(Streak s, float scale, float k, float widen) {
        // v001's ScaleMeshSize curve runs 1 to 2 over the sweep.
        float grow = s.kind == Kind.BOOST ? 1 + k : 1;
        float xz = s.sxy * scale * widen * grow, y = s.sz * scale * grow;
        return new Matrix4f().rotateY(s.yaw).scale(xz, y, xz);
    }

    /** Leaves riding the bright stretch of a long spiral, alpha-blended like the life beam's. */
    private void riders(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, float scale, double now, float strength,
            float widen, boolean fp) {
        float[] line = centreLine(Kind.SPIRAL);
        if (line.length == 0) return;
        out.submitCustomGeometry(stack, LifeBeamParts.LEAVES, (pose, buf) -> {
            Vector3f at = new Vector3f();
            for (Streak s : streaks) {
                if (s.leafTint < 0) continue;
                float k = (float) ((now - s.born) / s.life);
                if (k < 0 || k > 1) continue;
                float u = travel(s.kind, k) / (2 * s.trail);
                if (u <= .02f || u >= .98f) continue;
                float show = s.opening ? Math.max(strength, .6f) : strength;
                float a = Math.min(1, alphaCurve(s.kind, k) / 2 * show) * side(u, sideFade(s.kind, k));
                int i = Math.min(line.length / 3 - 2, (int) (u * (line.length / 3 - 1)));
                float f = u * (line.length / 3 - 1) - i;
                transform(s, scale, k, widen).transformPosition(Mth.lerp(f, line[i * 3], line[i * 3 + 3]),
                        Mth.lerp(f, line[i * 3 + 1], line[i * 3 + 4]), Mth.lerp(f, line[i * 3 + 2], line[i * 3 + 5]), at);
                Vec3 p = feet.add(at.x, at.y + s.base * scale, at.z);
                a *= ChargeParts.fade(view.cam(), p, fp ? 1.2 * scale / AURA_SCALE : .5, fp ? 2.5 * scale / AURA_SCALE : 1.5);
                if (a <= .01f) continue;
                float age = (float) (now - s.born);
                float roll = s.leafRoll + s.leafSpin * age;
                // Turning over as the wind tumbles it.
                float flip = .35f + .65f * Math.abs(Mth.cos(age * .11f + s.leafRoll));
                Vec3 right = view.right().scale(Math.cos(roll)).add(view.up().scale(Math.sin(roll)));
                Vec3 up = view.up().scale(Math.cos(roll)).subtract(view.right().scale(Math.sin(roll))).scale(flip);
                float u0 = (s.leafFrame & 1) * .5f, v0 = (s.leafFrame >> 1) * .5f;
                LifeBeamParts.quad(pose, buf, p, right, up, s.leafSize * scale, u0, v0, u0 + .5f, v0 + .5f, argb(.95f * a, s.leafTint));
            }
        });
    }

    /** The motes: a tinted halo (alpha-blended, for daylight) and a bright core, each stretched along its motion. */
    private void motes(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, float pt, double now, float strength, boolean fp) {
        if (motes.isEmpty()) return;
        // As the charge's clock does, a frozen frame (a scene's screenshot) stays on the last tick.
        float t = ClientCharges.frozen() ? 0 : pt;
        out.submitCustomGeometry(stack, LifeBeamParts.DOT_TINT, (pose, buf) -> {
            for (Mote m : motes) mote(pose, buf, view, m, feet, t, now, strength, fp, true);
        });
        out.submitCustomGeometry(stack, LifeBeamParts.DOT, (pose, buf) -> {
            for (Mote m : motes) mote(pose, buf, view, m, feet, t, now, strength, fp, false);
        });
    }

    private static void mote(PoseStack.Pose pose, VertexConsumer buf, View view, Mote m, Vec3 feet, float pt, double now,
            float strength, boolean fp, boolean halo) {
        float k = (float) ((now - m.born) / m.life);
        if (k < 0 || k > 1) return;
        Vec3 head = feet.add(Mth.lerp(pt, m.px, m.x), Mth.lerp(pt, m.py, m.y), Mth.lerp(pt, m.pz, m.z));
        // Scale Alpha falls 1 to 0 over the life while ScaleSpriteSize grows the speck from nothing.
        float a = (1 - k) * Math.min(1, strength * 1.2f) * ChargeParts.fade(view.cam(), head, fp ? .9 : .4, fp ? 2.2 : 1.1);
        if (a <= .01f) return;
        float grow = .3f + .7f * k;
        Vec3 moved = new Vec3(m.x - m.px, m.y - m.py, m.z - m.pz);
        // Velocity-aligned: a little longer than it is wide, more so the faster it goes.
        Vec3 tail = head.subtract(moved.scale(1.6));
        if (moved.lengthSqr() < 1e-8) tail = head.add(0, -m.width * .5, 0);
        if (halo) ChargeParts.streak(pose, buf, view, tail, head, m.width * 2.2f * grow, argb(.75f * a, m.color));
        else ChargeParts.streak(pose, buf, view, tail, head, m.width * grow, argb(a, lerpColor(0xF0FFD8, m.color, .45f)));
    }

    // ---- the emitters' curves ------------------------------------------------------------------

    /** Travel (the gradient's centre along 2 U Trail_Length_Control): 4 to -0.5 (v, Fountain003), 4 to 0 (v001). */
    private static float travel(Kind kind, float k) {
        return hermite(4, -3.073118f, kind == Kind.BOOST ? 0 : -.5f, -7.108825f, k);
    }

    /** Scale Alpha (1 held, then dropping fast at the end) times the emitter's alpha factor (5.864 v and v001, 5 Fountain003). */
    private static float alphaCurve(Kind kind, float k) {
        return Math.max(0, hermite(1.011319f, 0, .001956f, -3.813111f, k)) * (kind == Kind.STREAK ? 5 : 5.864f);
    }

    /** Scale RGB over the life, out of its peak: rising (v), steady (Fountain003), rising then gone (v001). */
    private static float brightness(Kind kind, float k) {
        return switch (kind) {
            case STREAK -> 1;
            case SPIRAL -> k < .20422f ? hermite(.1f, 4.178524f * .20422f, .622821f, 1.11792f * .20422f, k / .20422f)
                    : hermite(.622821f, 1.11792f * .79578f, 1, .022386f * .79578f, (k - .20422f) / .79578f);
            case BOOST -> k < .20422f ? hermite(0, 4.178524f * .20422f, .622821f, 1.11792f * .20422f, k / .20422f)
                    : k < .8f ? hermite(.622821f, 1.11792f * .59578f, 1, 0, (k - .20422f) / .59578f)
                    : hermite(1, 0, 0, .022386f * .2f, (k - .8f) / .2f);
        };
    }

    /** SideFadePOW: 1.5 to 0.8 (v, v001), 1.5 to 0.5 (Fountain003). */
    private static float sideFade(Kind kind, float k) {
        return Mth.lerp(k, 1.5f, kind == Kind.STREAK ? .5f : .8f);
    }

    /** The material's fade towards both ends of a streak: sin(pi U) ^ SideFadePOW. */
    private static float side(float u, float pow) {
        float s = Mth.sin(u * Mth.PI);
        return s <= 0 ? 0 : (float) Math.pow(s, pow);
    }

    private static float hermite(float p0, float m0, float p1, float m1, float t) {
        t = Mth.clamp(t, 0, 1);
        float t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1;
    }

    private static float frac(float x) {
        return x - Mth.floor(x);
    }

    // ---- meshes and material -------------------------------------------------------------------

    private static ChargeAuraMesh mesh(Kind kind) {
        return ChargeAuraMesh.get(kind == Kind.STREAK ? "life_charge/wind_streak" : "life_charge/wind_spiral");
    }

    private static final float[][] LINES = new float[3][];

    /** The middle of a spiral mesh along U: x, y, z for U = 0, 1/63 ... 1, from the mean of its vertices at each U. */
    private static float[] centreLine(Kind kind) {
        float[] line = LINES[kind.ordinal()];
        if (line != null) return line;
        ChargeAuraMesh mesh = mesh(kind);
        int n = 64;
        double[] sum = new double[n * 3];
        int[] hits = new int[n];
        for (int i = 0; i < mesh.vertices(); i++) {
            int b = Mth.clamp((int) (mesh.uv[i * 2] * (n - 1) + .5f), 0, n - 1);
            for (int j = 0; j < 3; j++) sum[b * 3 + j] += mesh.pos[i * 3 + j];
            hits[b]++;
        }
        line = new float[mesh.vertices() == 0 ? 0 : n * 3];
        for (int b = 0; b < line.length / 3; b++) {
            // An empty bin takes the nearest filled one.
            int src = b;
            for (int d = 1; hits[src] == 0 && d < n; d++) src = b - d >= 0 && hits[b - d] > 0 ? b - d : Math.min(n - 1, b + d);
            if (hits[src] == 0) continue;
            for (int j = 0; j < 3; j++) line[b * 3 + j] = (float) (sum[src * 3 + j] / hits[src]);
        }
        return LINES[kind.ordinal()] = line;
    }

    private static RenderType wind() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/life_charge_aura_wind")).withFragmentShader(id("core/life_charge_aura_wind")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)).build();
        return RenderType.create("logos_life_charge_aura_wind", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/charge_aura/noise_disflow_07.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
