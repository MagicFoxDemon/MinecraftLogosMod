package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.world.phys.Vec3;

/**
 * The colossal arcane charge's aura round the caster, in place of the shared pillar (CHARGE_VFX.md): Unreal's
 * NS_Charge_MagicLift_Root (taken apart in {@code art/charge/gather/arcane/notes.md}) in arcane's colours. While
 * it charges and is held:
 * <ul>
 * <li>bands of light rising from the feet (emitter Fountain001): camera-facing quads standing on the ground, their
 * fill the reference material's two panned noises burnt into a heat that is hottest at the root (shader
 * {@code arcane_charge_aura_band});</li>
 * <li>thin streaks shooting up from round the feet (1001) and twinkling motes rising with them (1), slowed by a
 * drag that grows with age; a quarter of the motes are the arcane ray's four-point glints;</li>
 * <li>a faint dark smoke behind the caster (Black) that the light stands out against;</li>
 * <li>and, arcane's own, a few of the arcane ray's runes drifting up through the bands.</li>
 * </ul>
 * Let go, it plays the reference's final boost: a ring of light closing in on the feet and three fans of rays
 * turning as they open and close (shader {@code arcane_charge_aura_fan}); dropped, it just fades. Strength and
 * timing follow {@link ChargeBody#aura}; sizes the caster's height, Unreal's centimetres read as a 180 cm figure.
 * In first person the bands, the smoke and the boost are left out (they stand where the camera is) and the motes
 * and runes fade near the camera.
 */
final class ArcaneChargeAura {
    /** Caps per caster: several may charge at once. */
    private static final int MAX_STREAKS = 24, MAX_MOTES = 60, MAX_BANDS = 12, MAX_SMOKE = 8, MAX_RUNES = 6;
    /**
     * The whole aura's size against the reference read as a 180 cm figure: positions, sizes, heights, radii and speeds
     * alike, in {@link #tick} and {@link #submit}, so it keeps its shape (the maintainer asked for it half as big again).
     */
    static final float AURA_SCALE = 1.5f;
    /** Ticks the final boost takes (the reference's 0.3 s deactivation fade). */
    private static final float BOOST = 6;

    // Unreal curves (time, value, arrive tangent, leave tangent, 1 for cubic from that key / 0 linear).
    private static final float[] MOTE_ALPHA = {0, 0, 9.07248f, 9.07248f, 1, .1407f, .9846f, .33327f, .33327f, 1, 1, 0, -4.6228f, -4.6228f, 1};
    private static final float[] MOTE_SIZE = {0, 1, 0, 0, 0, .7008f, 1, -.0405f, -.0405f, 1, 1, 0, -8.0089f, -8.0089f, 1};
    private static final float[] BAND_SIZE = {0, 0, 4.17852f, 4.17852f, 1, .2042f, .6228f, 1.11792f, 1.11792f, 1, 1, 1, .02238f, .02238f, 1};
    private static final float[] BAND_ALPHA = {.2f, .9846f, -2.5249f, -2.5249f, 1, 1, 0, -.0405f, -.0405f, 1};
    private static final float[] SMOKE_ALPHA = {0, 0, 2.25227f, 2.25227f, 1, .5f, 1, .77395f, .77395f, 1, 1, 0, -4.4963f, 0, 1};
    private static final float[] FAN_SIZE = {0, 0, 6.55984f, 6.55984f, 1, .6f, 1, 0, 0, 1, 1, 0, -7.6798f, 0, 1};
    private static final float[] FAN_ALPHA = {0, 0, 9.07248f, 0, 0, .1407f, .9846f, .45923f, .45923f, 1, 1, 0, -3.1992f, 0, 1};
    private static final float[] FAN_WIDTH = {0, .2f, 4.17852f, 4.17852f, 1, .2042f, .6228f, 1.11792f, 1.11792f, 1, 1, 1, .02238f, .02238f, 1};

    private static RenderType band, bandUnder, fan;
    /** Ticks a band lives: the reference's 0.55 s, drawn out so several stand round the caster at once. */
    private static final float BAND_LIFE = 16;

    /** A streak (1001) or a mote (1), moved in {@link #tick}: positions in blocks, velocity in blocks per tick. */
    private static final class Mote {
        final boolean streak, glint;
        final float born, life, size, dragMax, spin;
        double x, y, z, px, py, pz, vx, vy, vz;

        Mote(boolean streak, boolean glint, float born, float life, float size, float dragMax, float spin) {
            this.streak = streak;
            this.glint = glint;
            this.born = born;
            this.life = life;
            this.size = size;
            this.dragMax = dragMax;
            this.spin = spin;
        }
    }

    private record Band(float born, Vec3 root, float size, float seed, boolean flip) {}

    private record Smoke(float born, Vec3 at, int frame) {}

    private record Rune(float born, float life, Vec3 at, float rise, int glyph, float roll) {}

    /** One of the boost's fans: the plane it lies in, its size, turn rate (radians a tick), blades and falloff. */
    private record Fan(Vec3 a, Vec3 b, float size, float turn, int blades, float density, float bright) {}

    private final ChargeStyle style;
    private final List<Mote> motes = new ArrayList<>();
    private final List<Band> bands = new ArrayList<>();
    private final List<Smoke> smoke = new ArrayList<>();
    private final List<Rune> runes = new ArrayList<>();
    private final List<Fan> fans = new ArrayList<>();
    private float owedStreaks, owedMotes, owedBands, owedSmoke, owedRunes;
    /** When it was let go (charge age), or negative before. */
    private float boostAt = -1;
    private float ringSize;

    ArcaneChargeAura(ChargeStyle style) {
        this.style = style;
    }

    /** One client tick. False once there is nothing left to draw. */
    boolean tick(RandomSource r, LiveCharge c) {
        if (c.tier() != 3) return false;
        float now = c.age(0);
        LiveCharge.Phase phase = c.phase();
        ChargeBody.Aura aura = ChargeBody.aura(c, 0);
        if (aura == null) {
            end();
            return false;
        }
        float scale = scale(c);
        Vec3 feet = c.feet(0);
        move(now, scale);
        bands.removeIf(b -> now - b.born > BAND_LIFE);
        smoke.removeIf(s -> now - s.born > 50);
        runes.removeIf(u -> now - u.born > u.life);
        if (!phase.over()) {
            // Spawn rates per tick (the reference's per second / 20), building with the aura.
            float k = Math.min(1, aura.strength());
            for (owedStreaks += 1.5f * k; owedStreaks >= 1; owedStreaks--) streak(r, feet, now, scale);
            for (owedMotes += 2f * k; owedMotes >= 1; owedMotes--) mote(r, feet, now, scale);
            for (owedBands += .6f * k; owedBands >= 1; owedBands--) band(r, feet, now, scale);
            for (owedSmoke += .1f * k; owedSmoke >= 1; owedSmoke--) smoke(r, feet, now, scale);
            for (owedRunes += .11f * k; owedRunes >= 1; owedRunes--) rune(r, feet, now, scale);
        } else if (phase == LiveCharge.Phase.RELEASED && boostAt < 0) {
            boostAt = now - c.phaseAge(0);
            boost(r, scale);
        }
        return true;
    }

    /** Blocks per Unreal metre: the caster's height against 180 cm, times {@link #AURA_SCALE}. */
    private static float scale(LiveCharge c) {
        return c.height() / 1.8f * AURA_SCALE;
    }

    void end() {
        motes.clear();
        bands.clear();
        smoke.clear();
        runes.clear();
        fans.clear();
    }

    // ---- emitters ------------------------------------------------------------------------------------------

    /** A point in a disc of {@code radius} round the feet, {@code 0..height} up (ShapeLocation cylinder). */
    private static Vec3 cylinder(RandomSource r, Vec3 feet, float radius, float height) {
        double a = r.nextDouble() * Math.PI * 2, d = radius * Math.sqrt(r.nextDouble());
        return feet.add(Math.cos(a) * d, r.nextDouble() * height, Math.sin(a) * d);
    }

    /** 1001: 30/s, 0.3–0.4 s, from a 66 cm disc 18 cm deep, straight up at (20–75) × 12 cm/s, drag 0 → 3.6. */
    private void streak(RandomSource r, Vec3 feet, float now, float scale) {
        if (count(true) >= MAX_STREAKS) return;
        float life = 20 * (.3f + .1f * r.nextFloat());
        Mote m = new Mote(true, false, now, life, base(r), 3.6f, 0);
        launch(m, cylinder(r, feet, .66f * scale, .18f * scale), (20 + 55 * r.nextFloat()) * 12, scale);
        motes.add(m);
    }

    /** 1: 60/s, 0.3–0.8 s, from an 84 cm disc 30 cm deep, straight up at (20–75) × 6 cm/s, drag 0 → 3. */
    private void mote(RandomSource r, Vec3 feet, float now, float scale) {
        if (motes.size() - count(true) >= MAX_MOTES) return;
        float life = 20 * (.3f + .5f * r.nextFloat());
        Mote m = new Mote(false, r.nextInt(4) == 0, now, life, base(r), 3, r.nextFloat() * Mth.TWO_PI);
        launch(m, cylinder(r, feet, .84f * scale, .3f * scale), (20 + 55 * r.nextFloat()) * 6, scale);
        motes.add(m);
    }

    /** The sprite size both start from: between a random 1–2 cm and 2.4 cm. */
    private static float base(RandomSource r) {
        float lo = 1 + r.nextFloat();
        return lo + (2.4f - lo) * r.nextFloat();
    }

    private static void launch(Mote m, Vec3 at, float cmPerSecond, float scale) {
        m.x = m.px = at.x;
        m.y = m.py = at.y;
        m.z = m.pz = at.z;
        m.vy = cmPerSecond * .01 * scale / 20;
    }

    private int count(boolean streaks) {
        int n = 0;
        for (Mote m : motes) if (m.streak == streaks) n++;
        return n;
    }

    /** Moves the motes a tick on: drag growing from nothing to its most over each one's life. */
    private void move(float now, float scale) {
        motes.removeIf(m -> now - m.born > m.life);
        for (Mote m : motes) {
            float k = (now - m.born) / m.life;
            double keep = Math.exp(-m.dragMax * k * .05);
            m.px = m.x;
            m.py = m.y;
            m.pz = m.z;
            m.vx *= keep;
            m.vy *= keep;
            m.vz *= keep;
            m.x += m.vx;
            m.y += m.vy;
            m.z += m.vz;
        }
    }

    /**
     * Fountain001, made the aura's main part: 12/s for 0.8 s (the reference's 5/s for 0.55 s from the feet), their roots
     * spread on a ring round the feet so the bands stand all round the body.
     */
    private void band(RandomSource r, Vec3 feet, float now, float scale) {
        if (bands.size() >= MAX_BANDS) return;
        double a = r.nextDouble() * Math.PI * 2, d = (.15 + .4 * r.nextDouble()) * scale;
        bands.add(new Band(now, feet.add(Math.cos(a) * d, 0, Math.sin(a) * d), 8 + 4 * r.nextFloat(), r.nextFloat(), r.nextBoolean()));
    }

    /** Black: 2/s for 2.5 s, 10 cm above the feet, a random frame to start from. */
    private void smoke(RandomSource r, Vec3 feet, float now, float scale) {
        if (smoke.size() >= MAX_SMOKE) return;
        smoke.add(new Smoke(now, feet.add(0, .1 * scale, 0), r.nextInt(64)));
    }

    /** Arcane's own: a rune rising slowly through the bands, glowing and dimming as it goes. */
    private void rune(RandomSource r, Vec3 feet, float now, float scale) {
        if (runes.size() >= MAX_RUNES) return;
        Vec3 at = cylinder(r, feet, .7f * scale, 1.2f * scale).add(0, .25 * scale, 0);
        runes.add(new Rune(now, 30 + 20 * r.nextFloat(), at, (.5f + .4f * r.nextFloat()) * scale / 20, r.nextInt(9),
                (r.nextFloat() - .5f) * .6f));
    }

    /** FinalBoostBillBorad and FinalBoostRay: a ring of 4–8 m and three fans of up to 6–7 m, turned every which way. */
    private void boost(RandomSource r, float scale) {
        ringSize = (4 + 4 * r.nextFloat()) * .522f * 2 * scale;
        fans.clear();
        for (int i = 0; i < 3; i++) {
            Vec3 n = ChargeParts.randomUnit(r);
            Vec3[] plane = ChargeParts.basis(n);
            float rate = (120 + 60 * r.nextFloat()) * (r.nextBoolean() ? .5f : -.5f);
            fans.add(new Fan(plane[0], plane[1], (5.5f + r.nextFloat()) * .57f * 2 * scale, rate * Mth.DEG_TO_RAD / 20,
                    3 + r.nextInt(3), 2 + r.nextFloat(), 1.5f + .5f * r.nextFloat()));
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------------

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        if (c.tier() != 3) return;
        ChargeBody.Aura aura = ChargeBody.aura(c, pt);
        if (aura == null) return;
        float now = c.age(pt), scale = scale(c);
        Vec3 feet = c.feet(pt);
        // A release flashes then dies away; the sustained layers never go past full.
        float show = Math.min(1.15f, aura.strength()), widen = aura.widen();
        boolean fp = c.firstPerson();
        if (!fp) {
            smoke(stack, out, view, now, scale, show);
            bands(stack, out, view, now, scale, show, widen);
        }
        motes(stack, out, view, feet, now, pt, scale, show, widen, fp);
        runes(stack, out, view, now, scale, show, fp);
        if (!fp && boostAt >= 0) boost(stack, out, view, feet, now, scale);
    }

    /** Pushed {@code by} blocks towards the camera (the reference's CameraOffset; negative pushes it away). */
    private static Vec3 toCamera(View view, Vec3 p, double by) {
        Vec3 d = view.cam().subtract(p);
        double len = d.length();
        return len < 1e-4 ? p : p.add(d.scale(Math.min(by, len * .5) / len));
    }

    private void smoke(PoseStack stack, SubmitNodeCollector out, View view, float now, float scale, float show) {
        if (smoke.isEmpty()) return;
        out.submitCustomGeometry(stack, ChargeParts.blend("charge_aura/stamp_steam_88"), (pose, buf) -> {
            for (Smoke s : smoke) {
                float k = (now - s.born) / 50;
                if (k < 0 || k > 1) continue;
                float a = curve(SMOKE_ALPHA, k) * .1f * Math.min(1, show);
                if (a <= .002f) continue;
                Vec3 at = toCamera(view, s.at, -.6 * scale);
                int frame = (s.frame + (int) (k * 64)) % 64;
                rect(pose, buf, at, view.right(), view.up(), 1.2f * scale, 2.4f * scale, frame % 8 / 8f, frame / 8 / 8f, .125f, .125f,
                        argb(a, 0));
            }
        });
    }

    /**
     * Fountain001's bands: standing on the ground, turned to face the camera, growing up to 2.2 x their width. First a
     * blended pass in the deep colour, so they still read against a bright sky (as the arcane ray's runes do), then the glow.
     */
    private void bands(PoseStack stack, SubmitNodeCollector out, View view, float now, float scale, float show, float widen) {
        if (bands.isEmpty()) return;
        if (band == null) {
            band = material("band", "charge_aura/noise_cell", "charge_aura/noise_07", false);
            bandUnder = material("band_under", "charge_aura/noise_cell", "charge_aura/noise_07", true);
        }
        out.submitCustomGeometry(stack, bandUnder, (pose, buf) -> bandQuads(pose, buf, view, now, scale, show, widen, style.deep()));
        out.submitCustomGeometry(stack, band, (pose, buf) -> bandQuads(pose, buf, view, now, scale, show, widen, style.main()));
    }

    private void bandQuads(PoseStack.Pose pose, VertexConsumer buf, View view, float now, float scale, float show, float widen, int rgb) {
        for (Band b : bands) {
            float k = (now - b.born) / BAND_LIFE;
            if (k < 0 || k > 1) continue;
            float a = curve(BAND_ALPHA, k) * show;
            if (a <= .002f) continue;
            float w = b.size * 12 * curve(BAND_SIZE, k) * .01f * scale;
            // The reference pushes them 80 cm at the camera; less here, so those behind the caster stay behind.
            Vec3 root = toCamera(view, b.root, .25 * scale);
            Vec3 face = view.cam().subtract(root);
            Vec3 side = new Vec3(face.z, 0, -face.x);
            if (side.lengthSqr() < 1e-8) continue;
            side = side.normalize().scale(w * widen * .5);
            Vec3 top = root.add(0, w * 2.2f, 0);
            int seed = (int) (b.seed * 1000);
            float v0 = b.flip ? 1 : 0, v1 = 1 - v0;
            int color = argb(Math.min(1, a), rgb);
            param(pose, buf, root.subtract(side), 0, v0, color, seed, 0);
            param(pose, buf, top.subtract(side), 1, v0, color, seed, 0);
            param(pose, buf, top.add(side), 1, v1, color, seed, 0);
            param(pose, buf, root.add(side), 0, v1, color, seed, 0);
        }
    }

    private void motes(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, float now, float pt, float scale, float show,
            float widen, boolean fp) {
        if (motes.isEmpty()) return;
        int light = style.light(), main = style.main(), deep = style.deep();
        int n = motes.size();
        Vec3[] at = new Vec3[n];
        float[] alpha = new float[n], size = new float[n];
        for (int i = 0; i < n; i++) {
            Mote m = motes.get(i);
            float k = Mth.clamp((now - m.born) / m.life, 0, 1);
            double x = Mth.lerp(pt, m.px, m.x), y = Mth.lerp(pt, m.py, m.y), z = Mth.lerp(pt, m.pz, m.z);
            Vec3 p = new Vec3(feet.x + (x - feet.x) * widen, y, feet.z + (z - feet.z) * widen);
            at[i] = p;
            float a = curve(MOTE_ALPHA, k) * show * (m.streak ? .5f : 1);
            if (fp) a *= ChargeParts.fade(view.cam(), p, .6, 1.6);
            alpha[i] = a;
            // ScaleSpriteSize (× 2 over the life curve); the motes also grow with age and twinkle (Sine, period 0.3).
            float s = m.size * 2 * curve(MOTE_SIZE, k);
            if (!m.streak) s *= k * Math.abs(Mth.sin(k / .3f * Mth.TWO_PI));
            size[i] = s * .01f * scale;
        }
        // Streaks: velocity aligned, 15–25 times as long as wide as they go from still to 10 m/s.
        out.submitCustomGeometry(stack, ChargeParts.add("arcane/spark"), (pose, buf) -> {
            for (int i = 0; i < n; i++) {
                Mote m = motes.get(i);
                if (!m.streak || alpha[i] <= .003f) continue;
                double speed = Math.sqrt(m.vx * m.vx + m.vy * m.vy + m.vz * m.vz);
                float f = (float) Mth.clamp(speed * 20 / scale, 0, 1);
                float len = size[i] * Mth.lerp(f, 15, 25), wide = Math.max(size[i] * Mth.lerp(f, .2f, .3f), .02f * scale);
                Vec3 dir = speed < 1e-6 ? new Vec3(0, 1, 0) : new Vec3(m.vx / speed, m.vy / speed, m.vz / speed);
                Vec3 half = dir.scale(len * .5);
                ChargeParts.streak(pose, buf, view, at[i].subtract(half), at[i].add(half), wide * 5, argb(alpha[i] * .45f, main));
                ChargeParts.streak(pose, buf, view, at[i].subtract(half), at[i].add(half), wide, argb(alpha[i], light));
            }
            // Motes: a soft glow in the element's colour round a hot core.
            for (int i = 0; i < n; i++) {
                Mote m = motes.get(i);
                if (m.streak || m.glint || alpha[i] <= .003f || size[i] <= 1e-4f) continue;
                ChargeParts.sprite(pose, buf, view, at[i], size[i] * 4.5f, m.spin, argb(alpha[i] * .35f, lerpColor(main, deep, .3f)));
                ChargeParts.sprite(pose, buf, view, at[i], size[i] * 2f, m.spin, argb(alpha[i] * .8f, light));
            }
        });
        // Arcane's own: a quarter of the motes are four-point glints, winking to the same beat.
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_ray/glint"), (pose, buf) -> {
            for (int i = 0; i < n; i++) {
                Mote m = motes.get(i);
                if (!m.glint || alpha[i] <= .003f || size[i] <= 1e-4f) continue;
                ChargeParts.sprite(pose, buf, view, at[i], size[i] * 8f, m.spin * .1f, argb(alpha[i], lerpColor(light, main, .25f)));
            }
        });
    }

    private void runes(PoseStack stack, SubmitNodeCollector out, View view, float now, float scale, float show, boolean fp) {
        if (runes.isEmpty()) return;
        int n = runes.size();
        Vec3[] at = new Vec3[n];
        float[] alpha = new float[n];
        for (int i = 0; i < n; i++) {
            Rune u = runes.get(i);
            float age = now - u.born, k = age / u.life;
            at[i] = u.at.add(0, u.rise * age, 0);
            float a = show * ChargeParts.smooth(0, .2f, k) * (1 - ChargeParts.smooth(.65f, 1, k)) * (.6f + .4f * Mth.sin(age * .25f + i));
            if (fp) a *= ChargeParts.fade(view.cam(), at[i], .8, 2);
            alpha[i] = Math.max(0, a);
        }
        float size = .11f * scale;
        int light = style.light(), main = style.main(), deep = style.deep();
        // As the arcane ray draws them: a blended copy in the deep colour, so they read against a bright sky, then the glow.
        out.submitCustomGeometry(stack, ChargeParts.blend("arcane_ray/runes"), (pose, buf) -> {
            for (int i = 0; i < n; i++) if (alpha[i] > .003f)
                ChargeParts.frame(pose, buf, view, at[i], size, runes.get(i).roll, runes.get(i).glyph, 3, 3, argb(alpha[i] * .45f, deep));
        });
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_ray/runes"), (pose, buf) -> {
            for (int i = 0; i < n; i++) if (alpha[i] > .003f)
                ChargeParts.frame(pose, buf, view, at[i], size, runes.get(i).roll, runes.get(i).glyph, 3, 3,
                        argb(alpha[i] * .9f, lerpColor(light, main, .4f)));
        });
    }

    /** The release: the ring closing in on the feet, alpha sin(π x), and the fans turning as they open and close. */
    private void boost(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, float now, float scale) {
        float x = (now - boostAt) / BOOST;
        if (x < 0 || x >= 1) return;
        int light = style.light(), main = style.main();
        Vec3 ring = toCamera(view, feet.add(0, .05 * scale, 0), 1.6 * scale);
        float r = ringSize * (1 - x) * .5f, ra = Mth.sin(x * Mth.PI) * .9f;
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_impact/halo"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, ring, r, x * .6f, argb(ra, lerpColor(light, main, .35f))));
        if (fan == null) fan = material("fan", "charge_aura/noise_cell", null, false);
        float width = curve(FAN_WIDTH, x), grow = curve(FAN_SIZE, x), fade = curve(FAN_ALPHA, x) * .13f;
        out.submitCustomGeometry(stack, fan, (pose, buf) -> {
            for (Fan f : fans) {
                float half = f.size * grow * .5f, turn = f.turn * (now - boostAt);
                float cos = Mth.cos(turn) * half, sin = Mth.sin(turn) * half;
                Vec3 a = f.a.scale(cos).add(f.b.scale(sin)), b = f.b.scale(cos).subtract(f.a.scale(sin));
                int color = argb(Math.min(1, fade * f.bright), main);
                int pw = (int) (width * 1000), pb = (int) ((f.blades + f.density / 10) * 1000);
                param(pose, buf, feet.subtract(a).add(b), 0, 0, color, pw, pb);
                param(pose, buf, feet.subtract(a).subtract(b), 0, 1, color, pw, pb);
                param(pose, buf, feet.add(a).subtract(b), 1, 1, color, pw, pb);
                param(pose, buf, feet.add(a).add(b), 1, 0, color, pw, pb);
            }
        });
    }

    // ---- geometry and materials ----------------------------------------------------------------------------

    /** A quad round {@code c} spanned by {@code right} × {@code w} and {@code up} × {@code h}, showing [u0, u0+du] × [v0, v0+dv]. */
    private static void rect(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 right, Vec3 up, float w, float h, float u0, float v0,
            float du, float dv, int color) {
        Vec3 a = right.scale(w), b = up.scale(h);
        ChargeParts.vertex(pose, buf, c.subtract(a).add(b), u0, v0, color);
        ChargeParts.vertex(pose, buf, c.subtract(a).subtract(b), u0, v0 + dv, color);
        ChargeParts.vertex(pose, buf, c.add(a).subtract(b), u0 + du, v0 + dv, color);
        ChargeParts.vertex(pose, buf, c.add(a).add(b), u0 + du, v0, color);
    }

    /** A vertex for the aura shaders: two numbers (thousandths) for the fragment stage ride in the second UV. */
    private static void param(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int p0, int p1) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setUv2(Mth.clamp(p0, 0, 32000), Mth.clamp(p1, 0, 32000)).setNormal(pose, 0, 1, 0);
    }

    /**
     * Through {@code core/arcane_charge_aura} and {@code core/arcane_charge_aura_<shader>}, over textures under
     * {@code textures/vfx/}: additive, or ({@code under}) alpha-blended with {@code ARCANE_AURA_UNDERLAY} defined.
     */
    private static RenderType material(String name, String texture, String detail, boolean under) {
        String shader = under ? name.substring(0, name.indexOf('_')) : name;
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/arcane_charge_aura_" + name)).withVertexShader(id("core/arcane_charge_aura"))
                .withFragmentShader(id("core/arcane_charge_aura_" + shader)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(under ? BlendFunction.TRANSLUCENT : BlendFunction.LIGHTNING));
        if (under) pipeline.withShaderDefine("ARCANE_AURA_UNDERLAY");
        if (detail != null) pipeline.withSampler("DetailTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/" + texture + ".png"));
        if (detail != null) setup.withTexture("DetailTex", id("textures/vfx/" + detail + ".png"));
        return RenderType.create("logos_arcane_charge_aura_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    /**
     * An Unreal float curve at {@code x}: keys of (time, value, arrive tangent, leave tangent, cubic), each segment
     * cubic Hermite or linear by its first key, held flat before the first key and after the last.
     */
    static float curve(float[] keys, float x) {
        int n = keys.length / 5;
        if (x <= keys[0]) return keys[1];
        for (int i = 0; i + 1 < n; i++) {
            float t0 = keys[i * 5], t1 = keys[i * 5 + 5];
            if (x > t1) continue;
            float v0 = keys[i * 5 + 1], v1 = keys[i * 5 + 6], dt = t1 - t0, s = (x - t0) / dt;
            if (keys[i * 5 + 4] == 0) return Mth.lerp(s, v0, v1);
            float s2 = s * s, s3 = s2 * s;
            return (2 * s3 - 3 * s2 + 1) * v0 + (s3 - 2 * s2 + s) * dt * keys[i * 5 + 3] + (-2 * s3 + 3 * s2) * v1
                    + (s3 - s2) * dt * keys[i * 5 + 7];
        }
        return keys[n * 5 - 4];
    }
}
