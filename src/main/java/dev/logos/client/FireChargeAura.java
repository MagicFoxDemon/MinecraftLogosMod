package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The colossal fire charge's own aura, drawn in place of the shared pillar (CHARGE_VFX.md), after the Unreal
 * system NS_Charge_AuraBrust_Root (its breakdown: art/charge/gather/fire/notes.md). Round the caster's feet:
 * <ul>
 * <li>shells of flame: the system's sphere mesh, stretched tall and turned at random, growing over a second or
 * so while they burn out, five emitters of them as in Niagara (the hard-edged "Burn", the soft "head" ones, the
 * tall forked "Flip2" ones); their material (fire_charge_aura_flame) is M_Vfx_FightAura's: noise times a band
 * mask panning up, a fresnel rim, fading towards the top;</li>
 * <li>two spiral bands winding up round the caster (SM_SpiralStreak), spinning so they seem to climb;</li>
 * <li>a flickering glow at the feet, embers swirling up off the ground, sparks shooting straight up, a faint
 * dark smoke behind (for contrast in daylight), and rings of heat running out over the ground;</li>
 * <li>on release, three great fans of rays turning as it goes.</li>
 * </ul>
 * Fire's own touches: the shells burn from gold to orange to a dark red as they age and towards white-gold where
 * they are densest, the embers and sparks are the fire impact's, the smoke the fire damage's. Strength and time
 * follow {@link ChargeBody#aura}; sizes the caster's height. Counts are capped (several casters may charge at
 * once). In first person the shells, bands and smoke round the camera are left out, as the pillar was.
 */
final class FireChargeAura {
    private static final float DT = 1 / 20f;
    /**
     * The whole aura's size against Niagara's (cm / 100 on a 1.8-block caster): positions, sizes, heights, radii and
     * speeds alike, so it keeps its shape (the maintainer asked for it half as big again round the body).
     */
    static final float AURA_SCALE = 1.5f;
    /**
     * The share of Niagara's spawn rates kept for the shells and sprites: the shells are 500-triangle meshes, and there
     * may be several casters (the 96-triangle spiral bands keep their full rate).
     */
    private static final float RATE = .4f;
    /**
     * The material's fade towards the top is softened by this: without Unreal's bloom and with fewer shells, the
     * faint upper flames of pow(v, 7..12) vanish and the shells shrink to a bowl at the feet.
     */
    private static final float SOFTER_TOP = .3f;
    /** The tall, faint Flip2 shells are lifted by this for the same reason. */
    private static final float FAINT_LIFT = 3;
    /** Vertex alpha carries the material's factors divided by this (the shader multiplies it back). */
    private static final float GAIN = 32;

    private static final RenderType FLIP_MASK = flame("flip_mask", "noise_03", "uvs_band", 2, 1, true);
    private static final RenderType HEAD_LICK = flame("head_lick", "noise_03", "uvs_band", 1.5f, 1, false);
    private static final RenderType HEAD_CORE = flame("head_core", "noise_03", "uvs_band", 2, 1, false);
    private static final RenderType FLIP2_FORK = flame("flip2_fork", "noise_02", "uvs_lightning014_d", 1, 5, false);
    private static final RenderType FLIP2_SPIRE = flame("flip2_spire", "noise_02", "uvs_lightning014_d", 2, 5, false);
    private static final RenderType SPIRAL = flame("spiral", "noise_03", null, 1.5f, 1, false);

    /**
     * A Niagara mesh emitter: its renderer's material, spawn rate (a second) and lifetime (seconds), the mesh scale
     * it starts with (XY across, Z up, both picked between bounds) and the factor its scale modules grow it by
     * (straight or eased over its life), the material's fresnel power and fade towards the top, the particle's
     * alpha and colour scale, how far its U and V offsets run over its life, and how many may live at once.
     * Spiral bands also spin (turns a second) and open out by {@code taper} blocks (the material's offset).
     */
    private record Kind(RenderType type, boolean spiral, float rate, float lifeMin, float lifeMax, float xyMin, float xyMax,
            float zMin, float zMax, float k, boolean ease, float edge, float top, float amp, float bright,
            float vMin, float vMax, float uMin, float uMax, int cap, float spin, float taper) {}

    // NE_FightAura family of NS_Charge_AuraBrust_Root, values as in the system (AllAlpha 2.5 folded into amp).
    private static final Kind BURN = new Kind(FLIP_MASK, false, 21, 1, 1.5f, 3, 5, 1, 10, .825f, false, .8f, 7, 32.5f, 2, 0, 1, .5f, 1, 8, 0, 0);
    private static final Kind LICK = new Kind(HEAD_LICK, false, 10, 1.1f, 1.9f, 1.5f, 3, 4, 5, 1.1f, true, .8f, 9, 32.5f, 1, 0, 1, 1, 2, 6, 0, 0);
    private static final Kind FORK = new Kind(FLIP2_FORK, false, 5, 1.1f, 1.9f, 4, 6, .5f, 22, .495f, true, .8f, 10, 7.5f, 1, 1, 2, .5f, 1, 3, 0, 0);
    private static final Kind SPIRE = new Kind(FLIP2_SPIRE, false, 10, 1.1f, 1.9f, 1, 4, 1, 50, .55f, true, .8f, 12, 3.75f, 1, 1, 2, .5f, 1, 6, 0, 0);
    private static final Kind CORE = new Kind(HEAD_CORE, false, 21, 1.1f, 1.9f, 3, 5, 2, 4, .825f, false, .8f, 7, 37.5f, 2, 0, 1, .5f, 1, 8, 0, 0);
    private static final Kind COIL = new Kind(SPIRAL, true, 2, 1.1f, 1.9f, .725f, 1.885f, 4.725f, 8.1f, .66f, true, .5f, 7, 75, 1, 10, 50, 1, 2, 4, .975f, .45f);
    private static final Kind COIL_WIDE = new Kind(SPIRAL, true, 2, 1.1f, 1.9f, 2.175f, 2.61f, 4.7f, 6.26f, .66f, true, .8f, 7, 100, 1, 0, 0, .5f, 1, 4, 1.1f, .18f);
    private static final Kind[] KINDS = {BURN, CORE, LICK, FORK, SPIRE, COIL, COIL_WIDE};

    /** A shell or band: born and life in ticks; its picked scale, turn and offset rates. */
    private record Shell(Kind kind, float born, float life, float xy, float z, float yaw, float uRate, float vRate) {}

    private enum MoteKind { EMBER, SPARK, SMOKE, GLOW }

    /** A sprite particle, in blocks relative to the caster's feet (Niagara's local space), its velocity in blocks a second. */
    private static final class Mote {
        final MoteKind kind;
        final float born, life, size, seed;
        double x, y, z, px, py, pz, vx, vy, vz;

        Mote(MoteKind kind, float born, float life, float size, float seed, double x, double y, double z, double vx, double vy, double vz) {
            this.kind = kind;
            this.born = born;
            this.life = life;
            this.size = size;
            this.seed = seed;
            this.x = px = x;
            this.y = py = y;
            this.z = pz = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
        }
    }

    /** A ring of heat on the ground (the system's decal), or a fan of rays on release. */
    private record Ring(float born, float life, float radius, float turn) {}

    private record Fan(float born, float life, float size, Quaternionf turn, float spin) {}

    private static final int MOTE_CAP = 110;
    private static final Map<Float, float[]> POW = new HashMap<>();

    private final ChargeStyle style;
    private final List<Shell> shells = new ArrayList<>();
    private final List<Mote> motes = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<Fan> fans = new ArrayList<>();
    private final float[] owed = new float[KINDS.length];
    private float owedEmbers, owedSparks, owedSmoke, owedGlow, owedRings;

    FireChargeAura(ChargeStyle style) {
        this.style = style;
    }

    /** One client tick. False once nothing is left to play out. */
    boolean tick(RandomSource r, LiveCharge c) {
        if (c.tier() != 3) return false;
        double now = c.age(0);
        shells.removeIf(s -> now - s.born >= s.life);
        motes.removeIf(m -> now - m.born >= m.life);
        rings.removeIf(g -> now - g.born >= g.life);
        fans.removeIf(f -> now - f.born >= f.life);
        ChargeBody.Aura aura = ChargeBody.aura(c, 0);
        LiveCharge.Phase phase = c.phase();
        float scale = scale(c);
        if (aura == null) {
            shells.clear();
            motes.clear();
            rings.clear();
        } else if (!phase.over() && aura.strength() > .02f) {
            float more = .25f + .75f * Math.min(1, aura.strength());
            for (int i = 0; i < KINDS.length; i++) {
                Kind k = KINDS[i];
                for (owed[i] += k.rate * (k.spiral ? 1 : RATE) * more * DT; owed[i] >= 1; owed[i]--) if (count(k) < k.cap) shell(r, k, now);
            }
            for (owedEmbers += 50 * RATE * more * DT; owedEmbers >= 1; owedEmbers--) ember(r, now, scale);
            for (owedSparks += 52 * RATE * more * DT; owedSparks >= 1; owedSparks--) spark(r, now, scale);
            for (owedSmoke += 25 * .3f * more * DT; owedSmoke >= 1; owedSmoke--) smoke(r, now, scale);
            for (owedGlow += 50 * RATE * more * DT; owedGlow >= 1; owedGlow--) glow(r, now, scale);
            for (owedRings += 5 * .8f * more * DT; owedRings >= 1; owedRings--) {
                if (rings.size() < 4) rings.add(new Ring((float) now, 8 + r.nextInt(3), (4.4f + .6f * r.nextFloat()) * .5f, r.nextFloat() * 6.28f));
            }
        }
        // Released: the system's final boost, three fans of rays turning as the aura goes.
        if (phase == LiveCharge.Phase.RELEASED && c.entered()) {
            for (int i = 0; i < 3; i++) {
                Quaternionf turn = new Quaternionf().rotateXYZ(r.nextFloat() * 6.28f, r.nextFloat() * 6.28f, r.nextFloat() * 6.28f);
                float spin = (float) Math.toRadians(120 + 60 * r.nextFloat()) * (r.nextBoolean() ? 1 : -1);
                fans.add(new Fan((float) now, 12, (5.5f + r.nextFloat()) * .14f * 5.5f * .5f, turn, spin));
            }
        }
        move(now, phase.over() ? Math.max(0, 1 - c.phaseAge(0) / 10) : 1, scale);
        return !shells.isEmpty() || !motes.isEmpty() || !rings.isEmpty() || !fans.isEmpty();
    }

    void end() {
        shells.clear();
        motes.clear();
        rings.clear();
        fans.clear();
    }

    private int count(Kind k) {
        int n = 0;
        for (Shell s : shells) if (s.kind == k) n++;
        return n;
    }

    private void shell(RandomSource r, Kind k, double now) {
        float life = (k.lifeMin + (k.lifeMax - k.lifeMin) * r.nextFloat()) * 20;
        shells.add(new Shell(k, (float) now, life, k.xyMin + (k.xyMax - k.xyMin) * r.nextFloat(), k.zMin + (k.zMax - k.zMin) * r.nextFloat(),
                r.nextFloat() * Mth.TWO_PI, k.uMin + (k.uMax - k.uMin) * r.nextFloat(), k.vMin + (k.vMax - k.vMin) * r.nextFloat()));
    }

    /** NE_noise_burst001: embers thrown up off a sphere round the shins, then caught by a strong curl noise and drag. */
    private void ember(RandomSource r, double now, float s) {
        if (motes.size() >= MOTE_CAP) return;
        Vec3 d = ChargeParts.randomUnit(r).scale(Math.cbrt(r.nextDouble()) * .66 * s);
        motes.add(new Mote(MoteKind.EMBER, (float) now, 20 + r.nextInt(10), (.025f + .02f * r.nextFloat()) * s, r.nextFloat() * 100,
                d.x, .2 * s + d.y * .6, d.z, r.nextDouble() * .055 * s, (3.3 + .8 * r.nextDouble()) * s, 0));
    }

    /** NE_noise_burst002: sparks shooting straight up from close round the feet. */
    private void spark(RandomSource r, double now, float s) {
        if (motes.size() >= MOTE_CAP) return;
        Vec3 d = ChargeParts.randomUnit(r).scale(Math.cbrt(r.nextDouble()) * .3 * s);
        motes.add(new Mote(MoteKind.SPARK, (float) now, 20 + r.nextInt(5), (.03f + .03f * r.nextFloat()) * s, r.nextFloat() * 100,
                d.x, Math.abs(d.y) * .5, d.z, r.nextDouble() * .055 * s, (1.65 + 1.1 * r.nextDouble()) * s, 0));
    }

    /** "black": dark smoke rising behind the flames. */
    private void smoke(RandomSource r, double now, float s) {
        if (motes.size() >= MOTE_CAP) return;
        motes.add(new Mote(MoteKind.SMOKE, (float) now, 32 + r.nextInt(8), (1.1f + .7f * r.nextFloat()) * s, r.nextFloat() * 100,
                (r.nextDouble() - .5) * .3 * s, .3 * s, (r.nextDouble() - .5) * .3 * s, 0, (1.6 + .6 * r.nextDouble()) * s, 0));
    }

    /** "Root": short, quickly flickering glows at the feet. */
    private void glow(RandomSource r, double now, float s) {
        if (motes.size() >= MOTE_CAP) return;
        motes.add(new Mote(MoteKind.GLOW, (float) now, 4 + r.nextInt(5), (.06f + .06f * r.nextFloat()) * 16.5f * .5f * s, r.nextFloat() * 6.28f,
                0, .05 * s, 0, 0, 0, 0));
    }

    /** One tick of the sprites' motion: the embers' curl noise and drag (their pace dying with the aura after the end). */
    private void move(double now, float pace, float s) {
        float t = (float) now * DT;
        for (Mote m : motes) {
            m.px = m.x;
            m.py = m.y;
            m.pz = m.z;
            float k = (float) ((now - m.born) / m.life);
            if (m.kind == MoteKind.EMBER) {
                // Curl noise (strength scaled down from Niagara's 82 m/s^2 with its drag of up to 50 a second).
                // The field is sampled in the aura's own units, so its swirls and their strength grow with it.
                double x = m.x / s, y = m.y / s, z = m.z / s;
                double ax = Math.sin(2.1 * y + 1.3 * t + 1.7 * z + m.seed) + .5 * Math.sin(3.7 * z - 2.2 * t);
                double ay = Math.sin(2.5 * z + 1.1 * t + 1.3 * x + m.seed) * .6;
                double az = Math.sin(2.3 * x - 1.6 * t + 1.9 * y + m.seed) + .5 * Math.sin(3.1 * x + 1.7 * t);
                float force = 26 * s;
                m.vx += ax * force * DT;
                m.vy += ay * force * DT;
                m.vz += az * force * DT;
                double drag = Math.exp(-22 * ease(k) * DT);
                m.vx *= drag;
                m.vy *= drag;
                m.vz *= drag;
            } else if (m.kind == MoteKind.GLOW) {
                continue;
            }
            m.x += m.vx * DT * pace;
            m.y += m.vy * DT * pace;
            m.z += m.vz * DT * pace;
        }
    }

    // ---- drawing -------------------------------------------------------------------------------

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        if (c.tier() != 3) return;
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        double now = c.age(pt);
        Vec3 feet = c.feet(pt);
        float s = scale(c);
        boolean fp = c.firstPerson();
        if (aura != null) {
            float strength = aura.strength();
            // While it gathers it also grows; let go, it widens as it flashes out.
            float grow = c.phase() == LiveCharge.Phase.CHARGING ? .55f + .45f * Math.min(1, strength) : 1;
            if (!fp) smoke(stack, out, view, feet, now, pt, s, strength);
            rings(stack, out, feet, now, s, strength * (fp ? .7f : 1));
            glows(stack, out, view, feet, now, s, strength * (fp ? .35f : 1), fp);
            if (!fp) {
                shells(stack, out, view, feet, now, s, strength, grow, aura.widen());
                bands(stack, out, view, feet, now, s, strength, grow, aura.widen());
            }
            embers(stack, out, view, feet, now, pt, s, strength);
        }
        fans(stack, out, feet.add(0, .81 * s, 0), now, s, fp ? .3f : 1);
    }

    private void shells(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float s, float strength, float grow, float widen) {
        ChargeAuraMesh mesh = ChargeAuraMesh.get("charge_aura/sphere");
        Vec3 cam = view.cam().subtract(feet);
        for (RenderType type : new RenderType[] {FLIP_MASK, HEAD_CORE, HEAD_LICK, FLIP2_FORK, FLIP2_SPIRE}) {
            out.submitCustomGeometry(stack, type, (pose, buf) -> {
                for (Shell sh : shells) {
                    Kind k = sh.kind;
                    if (k.type != type) continue;
                    float t = (float) ((now - sh.born) / sh.life);
                    if (t < 0 || t >= 1) continue;
                    float g = k.ease ? ease(t) : t;
                    float sx = sh.xy * k.k * g * s * grow * widen, sy = sh.z * k.k * g * s * grow;
                    if (sx < .01f || sy < .01f) continue;
                    float amp = k.amp * (k.type == FLIP2_FORK || k.type == FLIP2_SPIRE ? FAINT_LIFT : 1) * (1 - t) * strength / GAIN;
                    if (amp < .002f) continue;
                    float cos = Mth.cos(sh.yaw), sin = Mth.sin(sh.yaw);
                    float[] rim = pow(k.edge), fade = pow(k.top * SOFTER_TOP);
                    int tint = tint(t, k.bright);
                    float cx = (float) cam.x, cy = (float) cam.y, cz = (float) cam.z;
                    Matrix4f m = new Matrix4f().rotateY(sh.yaw).scale(sx, sy, sx).translate(0, .037f, 0);
                    mesh.draw(pose, buf, feet, m, false, t * sh.uRate, t * sh.vRate, 1, 1, (x, y, z, u, v) -> {
                        // Where the vertex is and which way it faces (the squashed sphere's normal), round the feet.
                        float lx = x * sx, ly = (y + .037f) * sy, lz = z * sx;
                        float wx = lx * cos + lz * sin, wz = -lx * sin + lz * cos;
                        float nx0 = x * 4 / sx, ny = (y - .2525f) * 11.9f / sy, nz0 = z * 4 / sx;
                        float nx = nx0 * cos + nz0 * sin, nz = -nx0 * sin + nz0 * cos;
                        float a = amp * fresnel(rim, nx, ny, nz, cx - wx, cy - ly, cz - wz) * fade[index(v)];
                        return argb(a, tint);
                    });
                }
            });
        }
    }

    private void bands(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float s, float strength, float grow, float widen) {
        ChargeAuraMesh mesh = ChargeAuraMesh.get("charge_aura/spiral_streak");
        Vec3 cam = view.cam().subtract(feet);
        out.submitCustomGeometry(stack, SPIRAL, (pose, buf) -> {
            for (Shell sh : shells) {
                Kind k = sh.kind;
                if (!k.spiral) continue;
                float t = (float) ((now - sh.born) / sh.life);
                if (t < 0 || t >= 1) continue;
                float g = ease(t);
                // The scale curves over its life (wider at first, taller later) and the material's taper opening it out.
                float sx = (sh.xy * (1.13f - .26f * t) * k.k * g + 2 * k.taper) * s * grow * widen;
                float sy = sh.z * (.87f + .13f * t) * k.k * g * s * grow;
                if (sy < .01f) continue;
                float amp = k.amp * (1 - t) * strength / GAIN;
                float yaw = sh.yaw + k.spin * Mth.TWO_PI * (float) (now - sh.born) / 20;
                float cos = Mth.cos(yaw), sin = Mth.sin(yaw);
                float[] rim = pow(k.edge), fade = pow(k.top);
                int tint = tint(t, k.bright);
                float cx = (float) cam.x, cy = (float) cam.y, cz = (float) cam.z;
                Matrix4f m = new Matrix4f().rotateY(yaw).scale(sx, sy, sx).translate(-.15f, .037f, 0);
                mesh.draw(pose, buf, feet, m, false, t * sh.uRate, t * sh.vRate, 1, 1, (x, y, z, u, v) -> {
                    float lx = (x - .15f) * sx, ly = (y + .037f) * sy, lz = z * sx;
                    float wx = lx * cos + lz * sin, wz = -lx * sin + lz * cos;
                    // The band stands upright: it faces out from the axis.
                    float nx = x * cos + z * sin, nz = -x * sin + z * cos;
                    // Use_MaskU: the band fades out towards its top end.
                    float a = amp * fresnel(rim, nx, 0, nz, cx - wx, cy - ly, cz - wz) * fade[index(v)] * u;
                    return argb(a, tint);
                });
            }
        });
    }

    private void smoke(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float pt, float s, float strength) {
        // Pushed back from the camera (the system's camera offset), so it stays behind the flames.
        Vec3 back = feet.subtract(view.cam()).normalize().scale(.3 * s);
        int dark = lerpColor(0, style.deep(), .14f);
        out.submitCustomGeometry(stack, ChargeParts.blend("fire_damage/smoke"), (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != MoteKind.SMOKE) continue;
                float k = (float) ((now - m.born) / m.life);
                if (k < 0 || k >= 1) continue;
                Vec3 at = at(m, feet, pt).add(back);
                float a = .26f * (1 - k) * smooth(0, .15f, k) * strength;
                ChargeParts.frame(pose, buf, view, at, m.size * (1 - .55f * k), m.seed, (int) m.seed & 3, 2, 2, argb(a, dark));
            }
        });
    }

    private void rings(PoseStack stack, SubmitNodeCollector out, Vec3 feet, double now, float s, float strength) {
        if (rings.isEmpty()) return;
        out.submitCustomGeometry(stack, ChargeParts.add("charge_aura/circle_fist"), (pose, buf) -> {
            for (Ring g : rings) {
                float k = (float) ((now - g.born) / g.life);
                if (k < 0 || k >= 1) continue;
                // DecalFade: up to the middle of its life and back; brightest early.
                float a = .55f * Mth.sin(k * Mth.PI) * strength;
                int color = lerpColor(style.main(), style.deep(), k);
                ChargeParts.flat(pose, buf, feet.add(0, .05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), Math.max(.05f, g.radius * k * s), g.turn, argb(a, color));
            }
        });
    }

    private void glows(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float s, float strength, boolean fp) {
        Vec3 toward = view.cam().subtract(feet).normalize().scale(.3 * s);
        int color = lerpColor(style.main(), style.light(), .35f);
        out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/glow"), (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != MoteKind.GLOW) continue;
                float k = (float) ((now - m.born) / m.life);
                if (k < 0 || k >= 1) continue;
                float bell = Mth.sin(k * Mth.PI);
                ChargeParts.sprite(pose, buf, view, feet.add(toward).add(0, m.y, 0), m.size * bell * (fp ? .6f : 1), m.seed,
                        argb(.42f * bell * strength, color));
            }
        });
    }

    private void embers(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float pt, float s, float strength) {
        out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/spark"), (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != MoteKind.EMBER && m.kind != MoteKind.SPARK) continue;
                float k = (float) ((now - m.born) / m.life);
                if (k < 0 || k >= 1) continue;
                Vec3 at = at(m, feet, pt);
                float a = alpha(m, k) * strength * ChargeParts.fade(view.cam(), at, .8, 2.5);
                if (a <= .01f) continue;
                double speed = Math.sqrt(m.vx * m.vx + m.vy * m.vy + m.vz * m.vz);
                if (speed < 1e-3) continue;
                // Velocity-aligned, stretched by its speed (ScaleSpriteSizeBySpeed).
                double len = Mth.clamp(speed * (m.kind == MoteKind.SPARK ? .07 : .05), .04 * s, .35 * s);
                Vec3 dir = new Vec3(m.vx, m.vy, m.vz).scale(len / speed);
                ChargeParts.streak(pose, buf, view, at.subtract(dir), at.add(dir.scale(.25)), m.size * 1.4f * (1 - .5f * k), argb(a, heat(k)));
            }
        });
        out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/ember"), (pose, buf) -> {
            for (Mote m : motes) {
                if (m.kind != MoteKind.EMBER && m.kind != MoteKind.SPARK) continue;
                float k = (float) ((now - m.born) / m.life);
                if (k < 0 || k >= 1) continue;
                Vec3 at = at(m, feet, pt);
                float a = alpha(m, k) * strength * ChargeParts.fade(view.cam(), at, .8, 2.5);
                if (a > .01f) ChargeParts.sprite(pose, buf, view, at, m.size * (1 - .6f * k), 0, argb(a * .85f, heat(k * .7f)));
            }
        });
    }

    private void fans(PoseStack stack, SubmitNodeCollector out, Vec3 at, double now, float s, float dim) {
        if (fans.isEmpty()) return;
        int color = lerpColor(style.main(), style.light(), .3f);
        out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/rays"), (pose, buf) -> {
            for (Fan f : fans) {
                float k = (float) ((now - f.born) / f.life);
                if (k < 0 || k >= 1) continue;
                // Growing to its full size at .6 of the way and gone again by the end; brightest early.
                float size = f.size * s * (k < .6f ? k / .6f : (1 - k) / .4f);
                float a = .4f * (k < .14f ? k / .14f : (1 - k) / .86f) * dim;
                Quaternionf turn = new Quaternionf(f.turn).rotateZ(f.spin * (float) (now - f.born) / 20);
                Vector3f ax = turn.transform(new Vector3f(1, 0, 0)), bx = turn.transform(new Vector3f(0, 1, 0));
                ChargeParts.quad(pose, buf, at, new Vec3(ax.x, ax.y, ax.z).scale(size), new Vec3(bx.x, bx.y, bx.z).scale(size), argb(a, color));
            }
        });
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Blocks per Niagara metre for this caster: its height against a player's, times {@link #AURA_SCALE}. */
    private static float scale(LiveCharge c) {
        return c.height() / 1.8f * AURA_SCALE;
    }

    private static Vec3 at(Mote m, Vec3 feet, float pt) {
        return feet.add(Mth.lerp(pt, m.px, m.x), Mth.lerp(pt, m.py, m.y), Mth.lerp(pt, m.pz, m.z));
    }

    private static float alpha(Mote m, float k) {
        // Embers: up by .14 of the way, then out; sparks: bright at once and fading.
        return m.kind == MoteKind.EMBER ? (k < .14f ? k / .14f : (1 - k) / .86f) : Math.min(1, k / .05f) * (1 - k);
    }

    /** Ember heat: white-gold, orange, then a dark red as it cools. */
    private int heat(float k) {
        int hot = lerpColor(style.light(), style.main(), .35f);
        return k < .35f ? lerpColor(hot, style.main(), k / .35f) : lerpColor(style.main(), style.deep(), (k - .35f) / .65f);
    }

    /** A shell's tint over its life: gold while young (the hue curve), orange, dark red; darkening with its colour scale. */
    private int tint(float t, float bright) {
        int young = lerpColor(style.main(), style.light(), .45f);
        int c = t < .5f ? lerpColor(young, style.main(), t / .5f) : lerpColor(style.main(), style.deep(), (t - .5f) / .5f);
        return lerpColor(0, c, Math.min(1, (1 - t) * bright * 1.4f));
    }

    /** The curve the eased emitters grow by: .62 of the way at a fifth of their life. */
    private static float ease(float t) {
        if (t < .204f) {
            float k = t / .204f;
            return .623f * k * (2 - k);
        }
        float k = (t - .204f) / .796f;
        return .623f + .377f * k * (2 - k);
    }

    /** UE's Fresnel node (exponent 5, base .04) on a two-sided surface, raised to the material's edge power. */
    private static float fresnel(float[] power, float nx, float ny, float nz, float vx, float vy, float vz) {
        float d = (nx * nx + ny * ny + nz * nz) * (vx * vx + vy * vy + vz * vz);
        if (d < 1e-12f) return 0;
        float c = 1 - Math.abs(nx * vx + ny * vy + nz * vz) / Mth.sqrt(d);
        float c2 = c * c;
        return power[index(.04f + .96f * c2 * c2 * c)];
    }

    private static int index(float x) {
        return Mth.clamp((int) (x * 255 + .5f), 0, 255);
    }

    /** x to the power {@code p} for x in [0, 1], tabled (looked up per vertex). */
    private static float[] pow(float p) {
        return POW.computeIfAbsent(p, e -> {
            float[] table = new float[256];
            for (int i = 0; i < 256; i++) table[i] = (float) Math.pow(i / 255.0, e);
            return table;
        });
    }

    private static RenderType flame(String name, String noise, String mask, float noiseV, float alphaPow, boolean hard) {
        var builder = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/fire_charge_aura_" + name)).withFragmentShader(id("core/fire_charge_aura_flame"))
                .withShaderDefine("NOISE_V", noiseV).withShaderDefine("ALPHA_POW", alphaPow).withShaderDefine("GAIN", GAIN)
                .withSampler("NoiseTex").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING));
        if (mask != null) builder.withShaderDefine("MASK");
        if (hard) builder.withShaderDefine("HARD_EDGE");
        return RenderType.create("logos_fire_charge_aura_" + name, RenderSetup.builder(builder.build())
                .withTexture("Sampler0", id("textures/vfx/charge_aura/" + (mask != null ? mask : noise) + ".png"))
                .withTexture("NoiseTex", id("textures/vfx/charge_aura/" + noise + ".png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
