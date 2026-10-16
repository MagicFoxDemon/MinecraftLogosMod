package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.basis;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.randomUnit;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The shared charge at the cast point ({@link ChargeCore}), by tier:
 * <ul>
 * <li>ordinary: Star aura's {@code Sparks} and {@code SubSparks}, motes drawn into the head; fewer and
 * fewer towards the end, none once full.</li>
 * <li>elaborate: as ordinary, with the gathering of {@code PS_UltimateBeam_*}'s first half, wind discs
 * shrinking onto the head and windy trails circling into it; fewer and thinner towards the end.</li>
 * <li>grand: all of Star aura: motes, long sparks, spiralling and straight trails, glass shards, and a
 * shockwave every half second; everything smaller towards the end.</li>
 * <li>colossal: motes, spiralling trails and shockwaves, and strips flowing in from far off (wider and
 * fainter the further out) that thin to lines and are gone as it fills, while a sphere of high energy
 * fades in round the head and closes onto it.</li>
 * </ul>
 * Held, it is a ball of energy, more elaborate each tier. Released, the colossal ball swells and bursts;
 * the others pop and leave the spell to speak. Dropped, it dims and leaks away. Offsets are kept in units
 * of the tier's radius and drawn round the head where it is this frame, so the charge follows the wand;
 * everything at the head is drawn at {@link LiveCharge#near()} size and kept off the camera.
 */
final class ChargeHead {
    /** Radius motes are drawn in from, by tier. */
    private static final float[] RADIUS = {.55f, .75f, 1f, 1.25f};
    /** Radius of the ball once full, by tier. */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};
    private static final int RELEASE = 30, FIZZLE = 20, STRIPS = 12;

    private enum Kind { SPARK, SUB, LONG, SPIRAL, SHARP, GLASS, WIND, OUT, LEAK }

    private record Mote(Kind kind, double born, float life, Vec3 dir, Vec3 a, Vec3 b, double theta0, double omega, float r0, float size) {
        float k(double now) { return (float) ((now - born) / life); }

        /** Offset from the head, in units of the tier's radius, at age {@code age}. */
        Vec3 at(double age) {
            float k = (float) Mth.clamp(age / life, 0, 1);
            return switch (kind) {
                case SPARK, LONG -> dir.scale(r0 * (1 - k));
                case SUB -> dir.scale(r0 * (1 - k)).add(a.scale(.06 * Math.sin(age * .9 + theta0)));
                case SPIRAL, SHARP, GLASS -> orbit(age, r0 * (1 - k));
                case WIND -> orbit(age, r0 * (1 - k * k));
                case OUT -> dir.scale(r0 + omega * (1 - Math.exp(-age / 5)));
                case LEAK -> dir.scale(r0 + .15 * k).add(0, .5 * k, 0);
            };
        }

        private Vec3 orbit(double age, double r) {
            double th = theta0 + omega * age;
            return a.scale(Math.cos(th) * r).add(b.scale(Math.sin(th) * r));
        }
    }

    private record Strip(Vec3 a, Vec3 b, double theta0, double twist, double length, double swirl, float width) {}

    private final ChargeStyle style;
    /** False when the element draws the ball and the colossal closing sphere itself ({@link ChargeCore#ChargeCore(ChargeStyle, boolean)}). */
    private final boolean sharedBall;
    private final List<Mote> motes = new ArrayList<>();
    private final List<Strip> strips = new ArrayList<>();
    private final float[] owed = new float[Kind.values().length];

    ChargeHead(ChargeStyle style, boolean sharedBall) {
        this.style = style;
        this.sharedBall = sharedBall;
    }

    // ---- simulation ----------------------------------------------------------------------------

    /** One client tick. False once whatever followed the end has played out. */
    boolean tick(RandomSource r, LiveCharge c) {
        double now = c.age(0);
        motes.removeIf(m -> now - m.born > m.life);
        int tier = c.tier();
        LiveCharge.Phase phase = c.phase();
        if (c.entered()) {
            if (phase == LiveCharge.Phase.CHARGING && tier == 3 && strips.isEmpty()) makeStrips(r);
            if (phase == LiveCharge.Phase.RELEASED && tier == 3) burst(r, now);
            if (phase == LiveCharge.Phase.FIZZLED) leak(r, now, tier);
        }
        if (phase == LiveCharge.Phase.CHARGING) {
            float p = c.progress(0), rate = rate(tier, p);
            spawn(r, now, Kind.SPARK, (4 + tier) * rate);
            spawn(r, now, Kind.SUB, (1.5f + tier * .5f) * rate);
            if (tier == 1 || tier == 2) spawn(r, now, Kind.WIND, (tier == 1 ? 1.1f : .5f) * rate);
            if (tier >= 2) spawn(r, now, Kind.SPIRAL, (tier == 2 ? 1.3f : 1.6f) * rate);
            if (tier == 2) {
                spawn(r, now, Kind.LONG, 3 * rate);
                spawn(r, now, Kind.SHARP, .9f * rate);
                spawn(r, now, Kind.GLASS, .7f * rate);
            }
        } else if (phase == LiveCharge.Phase.HELD && tier >= 2) {
            // Held big charges still draw a little in, close round the ball.
            spawn(r, now, Kind.SPIRAL, tier == 3 ? .5f : .3f);
            if (tier == 3) spawn(r, now, Kind.SPARK, 1.2f);
        }
        if (!phase.over()) return true;
        float since = c.phaseAge(0);
        return !motes.isEmpty() || since < (phase == LiveCharge.Phase.RELEASED ? RELEASE : FIZZLE);
    }

    /** How much keeps coming in at progress {@code p}: the small tiers dwindle to nothing as they fill. */
    private static float rate(int tier, float p) {
        return switch (tier) {
            case 0, 1 -> 1 - smooth(.6f, .96f, p);
            case 3 -> 1 - .6f * smooth(.7f, 1f, p);
            default -> 1;
        };
    }

    private void spawn(RandomSource r, double now, Kind kind, float perTick) {
        float n = owed[kind.ordinal()] += perTick;
        for (; n >= 1; n--) motes.add(mote(r, now + r.nextFloat(), kind));
        owed[kind.ordinal()] = n;
    }

    private static Mote mote(RandomSource r, double born, Kind kind) {
        Vec3 dir = randomUnit(r);
        Vec3[] plane = basis(randomUnit(r));
        double theta = r.nextDouble() * Math.PI * 2;
        double spin = (r.nextBoolean() ? 1 : -1);
        return switch (kind) {
            case SPARK -> new Mote(kind, born, 5 + r.nextInt(4), dir, plane[0], plane[1], 0, 0, .55f + .45f * r.nextFloat(), .03f + .04f * r.nextFloat());
            case SUB -> new Mote(kind, born, 6 + r.nextInt(4), dir, plane[0], plane[1], theta, 0, .08f + .25f * r.nextFloat(), .015f + .02f * r.nextFloat());
            case LONG -> new Mote(kind, born, 3 + r.nextInt(3), dir, plane[0], plane[1], 0, 0, 1, .02f + .015f * r.nextFloat());
            case SPIRAL -> new Mote(kind, born, 9 + r.nextInt(5), dir, plane[0], plane[1], theta, spin * (.13 + .1 * r.nextDouble()), .8f + .2f * r.nextFloat(), .05f + .03f * r.nextFloat());
            case SHARP -> new Mote(kind, born, 6 + r.nextInt(3), dir, plane[0], plane[1], theta, spin * .05 * r.nextDouble(), .9f + .1f * r.nextFloat(), .04f);
            case GLASS -> new Mote(kind, born, 8 + r.nextInt(4), dir, plane[0], plane[1], theta, spin * (.16 + .08 * r.nextDouble()), .75f + .25f * r.nextFloat(), .05f + .03f * r.nextFloat());
            case WIND -> new Mote(kind, born, 10 + r.nextInt(5), dir, plane[0], plane[1], theta, spin * (.27 + .09 * r.nextDouble()), 1.1f + .3f * r.nextFloat(), .17f + .09f * r.nextFloat());
            case OUT -> new Mote(kind, born, 14 + r.nextInt(10), dir, plane[0], plane[1], 0, 2.2 + 2.6 * r.nextDouble(), .2f, .05f + .05f * r.nextFloat());
            case LEAK -> new Mote(kind, born, 14 + r.nextInt(10), dir, plane[0], plane[1], 0, 0, .1f + .1f * r.nextFloat(), .03f + .03f * r.nextFloat());
        };
    }

    private void makeStrips(RandomSource r) {
        for (int i = 0; i < STRIPS; i++) {
            // Mostly from round about and above, as in the explosion spell's gathering; each bends a quarter to
            // an eighth of a turn on its way in, never wrapping round the caster.
            Vec3 n = randomUnit(r).add(0, .6, 0).normalize();
            Vec3[] plane = basis(n);
            strips.add(new Strip(plane[0], plane[1], r.nextDouble() * Math.PI * 2, (r.nextBoolean() ? 1 : -1) * (Math.PI / 4 + Math.PI / 4 * r.nextDouble()),
                    11 + 8 * r.nextDouble(), (r.nextBoolean() ? 1 : -1) * (.0015 + .002 * r.nextDouble()), .8f + .5f * r.nextFloat()));
        }
    }

    private void burst(RandomSource r, double now) {
        for (int i = 0; i < 46; i++) motes.add(mote(r, now + 4 + r.nextFloat() * 2, Kind.OUT));
    }

    private void leak(RandomSource r, double now, int tier) {
        for (int i = 0; i < 5 + 3 * tier; i++) motes.add(mote(r, now + r.nextFloat() * 6, Kind.LEAK));
    }

    // ---- drawing -------------------------------------------------------------------------------

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        int tier = c.tier();
        Vec3 tip = c.tip(pt);
        double now = c.age(pt);
        float near = c.near();
        float R = RADIUS[tier] * near;
        // Nothing at the head may sit on the camera: fade what comes within reach of it.
        float clear = fade(view.cam(), tip, .15, .45);
        LiveCharge.Phase phase = c.phase();
        float p = c.progress(pt), since = c.phaseAge(pt);
        float shrink = tier == 2 && phase == LiveCharge.Phase.CHARGING ? 1 - .55f * smooth(.6f, 1f, p) : 1;

        motes(stack, out, view, tip, now, R * shrink, near, clear);
        if (phase == LiveCharge.Phase.CHARGING) {
            if (tier == 1 || tier == 2) windDiscs(stack, out, c, tip, now, R, p, clear);
            if (tier >= 2) shockwaves(stack, out, view, tip, now, R * shrink, p, clear);
            if (tier == 3) {
                strips(stack, out, view, tip, now, p, near);
                if (sharedBall) closingSphere(stack, out, view, tip, now, p, near, c.firstPerson());
            }
        }
        if (phase == LiveCharge.Phase.FIZZLED && tier == 3 && since < 8) {
            // Strips caught part way just fall away.
            strips(stack, out, view, tip, now, c.progress(pt), near, 1 - since / 8);
        }
        ball(stack, out, view, c, tip, now, near, clear, pt);
    }

    private void motes(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, double now, float R, float near, float clear) {
        if (motes.isEmpty()) return;
        int light = style.light(), main = style.main();
        out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) -> {
            for (Mote m : motes) {
                float k = m.k(now);
                if (k < 0 || k > 1) continue;
                switch (m.kind) {
                    case SPARK, SUB -> {
                        Vec3 at = tip.add(m.at(now - m.born).scale(R));
                        float s = m.size * near * Mth.sin(k * Mth.PI) * (m.kind == Kind.SPARK ? 1.6f : 1.3f);
                        ChargeParts.sprite(pose, buf, view, at, s, 0, argb(clear * .95f, lerpColor(light, main, k * .7f)));
                    }
                    case OUT -> {
                        Vec3 at = tip.add(m.at(now - m.born).scale(near));
                        float s = m.size * near * (1 - k);
                        ChargeParts.sprite(pose, buf, view, at, s * 1.8f, 0, argb((1 - k) * clear, lerpColor(light, main, k)));
                    }
                    case LEAK -> {
                        Vec3 at = tip.add(m.at(now - m.born).scale(near));
                        float s = m.size * near * (1 - k * .5f);
                        float flicker = .55f + .45f * Mth.sin((float) (now * 1.7 + m.theta0 * 9));
                        ChargeParts.sprite(pose, buf, view, at, s, 0, argb(.6f * (1 - k) * flicker * clear, lerpColor(main, style.deep(), .5f)));
                    }
                    default -> {}
                }
            }
        });
        out.submitCustomGeometry(stack, ChargeParts.add("force/streak"), (pose, buf) -> {
            for (Mote m : motes) {
                float k = m.k(now);
                if (k < 0 || k > 1) continue;
                if (m.kind == Kind.LONG) {
                    Vec3 head = tip.add(m.at(now - m.born).scale(R));
                    Vec3 tail = head.add(m.dir.scale(.45 * R));
                    ChargeParts.streak(pose, buf, view, tail, head, m.size * near * 2, argb(clear * Mth.sin(k * Mth.PI), light));
                } else if (m.kind == Kind.OUT) {
                    Vec3 head = tip.add(m.at(now - m.born).scale(near));
                    Vec3 tail = tip.add(m.at(Math.max(0, now - m.born - 2.5)).scale(near));
                    ChargeParts.streak(pose, buf, view, tail, head, m.size * near * 1.4f, argb((1 - k) * clear, main));
                } else if (m.kind == Kind.SPIRAL || m.kind == Kind.SHARP) {
                    trail(pose, buf, view, tip, m, now, R, near, clear, m.kind == Kind.SPIRAL ? 5 : 3, light, main);
                }
            }
        });
        out.submitCustomGeometry(stack, ChargeParts.add("fire_beam/spiral"), (pose, buf) -> {
            for (Mote m : motes) if (m.kind == Kind.WIND && m.k(now) >= 0 && m.k(now) <= 1) trail(pose, buf, view, tip, m, now, R, near, clear * .8f, 6, light, main);
        });
        out.submitCustomGeometry(stack, ChargeParts.add("charge/romb"), (pose, buf) -> {
            for (Mote m : motes) {
                float k = m.k(now);
                if (m.kind != Kind.GLASS || k < 0 || k > 1) continue;
                Vec3 at = tip.add(m.at(now - m.born).scale(R));
                float roll = (float) (m.theta0 + m.omega * (now - m.born) * 3);
                ChargeParts.sprite(pose, buf, view, at, m.size * near * Mth.sin(k * Mth.PI), roll, argb(clear, lerpColor(light, main, .4f)));
            }
        });
    }

    /** A mote's trail: where it was over the last {@code length} ticks, bright and wide at the head, thinning away. */
    private static void trail(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 tip, Mote m, double now, float R, float near,
            float clear, float length, int light, int main) {
        int n = 8;
        Vec3[] pts = new Vec3[n];
        float[] half = new float[n], u = new float[n];
        int[] colors = new int[n];
        double age = now - m.born;
        float life = Mth.sin(Mth.clamp(m.k(now), 0, 1) * Mth.PI);
        for (int i = 0; i < n; i++) {
            float f = i / (float) (n - 1);
            double a = Math.max(0, age - length * (1 - f));
            pts[i] = tip.add(m.at(a).scale(R));
            half[i] = m.size * near * (.15f + .85f * f) * .5f;
            u[i] = f;
            colors[i] = argb(clear * life * f * f, lerpColor(main, light, f));
        }
        ChargeParts.ribbon(pose, buf, view, pts, half, colors, u, n);
    }

    /** Elaborate (and grand): wind discs facing along the look, shrinking onto the head one after another. */
    private void windDiscs(PoseStack stack, SubmitNodeCollector out, LiveCharge c, Vec3 tip, double now, float R, float p, float clear) {
        Vec3 look = c.look(0);
        Vec3[] plane = basis(look);
        float period = c.tier() == 1 ? 4 : 6, life = 7;
        float rate = c.tier() == 1 ? rate(1, p) : 1;
        if (rate <= .01f) return;
        out.submitCustomGeometry(stack, ChargeParts.add("heal/swirl"), (pose, buf) -> {
            for (int i = (int) Math.floor((now - life) / period) + 1; i <= Math.floor(now / period); i++) {
                double born = i * period;
                float k = (float) ((now - born) / life);
                if (born < 0 || k < 0 || k > 1) continue;
                float size = R * 1.5f * (float) Math.pow(1 - k, 1.3) * (.5f + .5f * rate);
                float alpha = clear * rate * smooth(0, .2f, k) * (1 - k * .4f) * .7f;
                ChargeParts.flat(pose, buf, tip, plane[0], plane[1], size, (float) (i * 1.3 + now * .35), argb(alpha, style.main()));
            }
        });
    }

    /**
     * Star aura's ShockWave, one at a time: a torn ring of light born at the head and running out
     * past it (an 8-frame flipbook baked from the prefab's shader, frames blended into each other), growing a
     * little as it goes. Fewer and fainter towards the end of the charge, none once it is nearly full.
     */
    private void shockwaves(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, double now, float R, float p, float clear) {
        // Each runs out over 24 ticks, and the next starts only once it is gone.
        float life = 24, period = life, dwindle = 1 - smooth(.6f, .85f, p);
        if (dwindle <= 0) return;
        int color = lerpColor(style.light(), style.main(), .5f);
        out.submitCustomGeometry(stack, ChargeParts.add("charge/shockwave"), (pose, buf) -> {
            for (int i = (int) Math.floor((now - life) / period) + 1; i <= Math.floor(now / period); i++) {
                float k = (float) ((now - i * period) / life);
                if (i < 0 || k < 0 || k > 1) continue;
                float size = R * 1.1f * (1 + .15f * k);
                float alpha = clear * dwindle * smooth(0, .18f, k) * (1 - smooth(.85f, 1, k)) * .9f;
                float frame = Math.min(k * 8, 7.999f);
                int f0 = (int) frame, f1 = Math.min(f0 + 1, 7);
                float mix = frame - f0;
                float roll = i * 2.1f;
                ChargeParts.frame(pose, buf, view, tip, size, roll, f0, 4, 2, argb(alpha * (1 - mix), color));
                if (mix > 0) ChargeParts.frame(pose, buf, view, tip, size, roll, f1, 4, 2, argb(alpha * mix, color));
            }
        });
    }

    /** Colossal: strips flowing in from far off, wide and faint out there, thinning to lines and gone as the charge fills. */
    private void strips(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, double now, float p, float near) {
        strips(stack, out, view, tip, now, p, near, 1);
    }

    private void strips(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, double now, float p, float near, float fadeOut) {
        if (strips.isEmpty()) return;
        float intro = smooth(0, .15f, p) * fadeOut;
        float thin = 1 - smooth(.7f, .97f, p);
        float gone = 1 - smooth(.95f, 1f, p);
        if (intro * gone <= 0) return;
        int n = 26;
        Vec3[] pts = new Vec3[n];
        float[] half = new float[n], u = new float[n];
        int[] colors = new int[n];
        // Each layer scrolls its texture towards the head (U falls along a strip as time goes on), the
        // noise fastest: wind streaks, a slow cloud of noise, fast veins of energy, and a bright core line.
        String[] texture = {"physical_ray/wind", "ward/noise", "void_beam/flow", "physical_ray/wind"};
        float[] tiles = {3, 2, 5, 4}, speed = {.3f, .25f, .75f, .5f};
        for (int pass = 0; pass < 4; pass++) {
            int layer = pass;
            out.submitCustomGeometry(stack, ChargeParts.add(texture[layer]), (pose, buf) -> {
                for (Strip s : strips) {
                    for (int i = 0; i < n; i++) {
                        double f = i / (double) (n - 1);
                        double reach = s.length * Math.pow(f, 1.15) + .02;
                        double th = s.theta0 + s.twist * f + s.swirl * now * (1 - .6 * f);
                        pts[i] = tip.add(s.a.scale(Math.cos(th) * reach)).add(s.b.scale(Math.sin(th) * reach));
                        float width = (float) (.015 + 1.25 * Math.pow(f, 1.3)) * s.width * (.06f + .94f * thin);
                        u[i] = (float) (f * tiles[layer] + now * speed[layer] + s.theta0);
                        float alpha = (float) (Math.pow(1 - f, 1.5) * smooth(0, .04, f)) * intro * gone * fade(view.cam(), pts[i], .25, 1.5);
                        switch (layer) {
                            case 0 -> {
                                half[i] = width;
                                colors[i] = argb(alpha * .45f, lerpColor(style.main(), style.deep(), (float) f));
                            }
                            case 1 -> {
                                half[i] = width * 1.1f;
                                colors[i] = argb(alpha * .35f, style.main());
                            }
                            case 2 -> {
                                half[i] = width * .75f;
                                colors[i] = argb(alpha * .8f, lerpColor(style.light(), style.main(), (float) f));
                            }
                            default -> {
                                half[i] = Math.max(.008f * near, width * .22f);
                                colors[i] = argb(alpha * (.6f + .4f * (1 - thin)), lerpColor(style.light(), style.main(), (float) f * 1.5f));
                            }
                        }
                    }
                    ChargeParts.ribbon(pose, buf, view, pts, half, colors, u, n);
                }
            });
        }
    }

    /** Colossal: a sphere of high energy fading in round the head and closing onto it as the charge fills. */
    private void closingSphere(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, double now, float p, float near, boolean firstPerson) {
        float show = smooth(.45f, .65f, p);
        if (show <= 0) return;
        float k = smooth(.45f, 1f, p);
        float r = Mth.lerp((float) Math.pow(k, 1.4), 4.2f, BALL[3]) * (near < 1 ? .7f : 1);
        // From inside, a sphere is a wall over the whole view: show it only once it is smaller than the distance to it.
        float seen = show * fade(view.cam(), tip, r, r * 2 + .1);
        if (seen <= 0) return;
        float spin = (float) (now * .05);
        if (!firstPerson) out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, r * 1.9f, 0, argb(seen * .35f, style.deep())));
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_impact/flash"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, r, 0, argb(seen * .6f, style.main())));
        out.submitCustomGeometry(stack, ChargeParts.add("charge/plasma"), (pose, buf) -> {
            ChargeParts.sprite(pose, buf, view, tip, r * .96f, spin, argb(seen * .55f, style.light()));
            ChargeParts.sprite(pose, buf, view, tip, r * .9f, -spin * 1.4f + 1, argb(seen * .4f, style.main()));
        });
    }

    /** The ball at the head: growing as it charges, held, popped or burst on release, dimming out if dropped. */
    private void ball(PoseStack stack, SubmitNodeCollector out, View view, LiveCharge c, Vec3 tip, double now, float near, float clear, float pt) {
        int tier = c.tier();
        LiveCharge.Phase phase = c.phase();
        float since = c.phaseAge(pt), p = c.progress(pt);
        float r = BALL[tier] * near, alpha = 1;
        switch (phase) {
            case CHARGING -> {
                float grow = tier == 3 ? smooth(.8f, 1f, p) : (float) Math.pow(p, 1.4);
                r *= .25f + .75f * grow;
                alpha = tier == 3 ? .3f + .7f * grow : .35f + .65f * grow;
                // A soft glow gathers from the first moment, so the head never looks empty.
                float soft = BALL[tier] * near * (2.5f + 2.5f * p);
                out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) ->
                        ChargeParts.sprite(pose, buf, view, tip, soft, 0, argb(clear * (.25f + .35f * p), style.deep())));
            }
            case HELD -> r *= 1 + .06f * Mth.sin((float) now * .5f);
            case RELEASED -> {
                if (tier == 3) {
                    float k = since / 7;
                    if (k > 1) {
                        explosion(stack, out, view, tip, since - 5, near, clear, c.firstPerson());
                        return;
                    }
                    r = Mth.lerp(1 - (1 - k) * (1 - k), r, 1.5f * near);
                    alpha = 1 - k * k;
                    if (since > 5) explosion(stack, out, view, tip, since - 5, near, clear, c.firstPerson());
                } else {
                    float k = since / 4;
                    if (k > 1) return;
                    r *= 1 + .8f * k;
                    alpha = 1 - k;
                    float f = r * (3 + tier);
                    float a = (1 - k) * clear;
                    out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/flash"), (pose, buf) ->
                            ChargeParts.sprite(pose, buf, view, tip, f, 0, argb(a * .8f, style.light())));
                }
            }
            case FIZZLED -> {
                float k = since / FIZZLE;
                if (k > 1) return;
                float flicker = .6f + .4f * Mth.sin(since * 2.3f);
                r *= Math.max(.05f, (.25f + .75f * c.progress(pt)) * (1 - k * .8f));
                alpha = (1 - k) * flicker * .7f;
            }
        }
        // In first person the head is an arm's length away: nothing there may grow past it.
        if (c.firstPerson()) r = Math.min(r, (float) view.cam().distanceTo(tip) * .3f);
        drawBall(stack, out, view, tip, now, tier, r, alpha * clear, phase == LiveCharge.Phase.FIZZLED);
    }

    private void drawBall(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, double now, int tier, float r, float a, boolean dim) {
        if (!sharedBall || a <= .003f || r <= 0) return;
        int light = dim ? lerpColor(style.light(), style.deep(), .5f) : style.light();
        int main = dim ? style.deep() : style.main();
        float spin = (float) now * .08f;
        out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, Math.min(r * 4.2f, r * 1.6f + .6f), 0, argb(a * .4f, main)));
        if (tier == 3) {
            float twinkle = .75f + .25f * Mth.sin((float) now * .7f);
            out.submitCustomGeometry(stack, ChargeParts.add("lightning_beam/rays"), (pose, buf) ->
                    ChargeParts.sprite(pose, buf, view, tip, r * 3.6f, spin * .5f, argb(a * .45f, main)));
            out.submitCustomGeometry(stack, ChargeParts.add("arcane_impact/star"), (pose, buf) ->
                    ChargeParts.sprite(pose, buf, view, tip, r * 6 * twinkle, 0, argb(a * .7f, light)));
        }
        if (tier >= 1) {
            out.submitCustomGeometry(stack, ChargeParts.add("void_ray/ring"), (pose, buf) -> {
                Vec3[] tilt = basis(new Vec3(Math.sin(now * .03), 1.2, Math.cos(now * .03)).normalize());
                ChargeParts.flat(pose, buf, tip, tilt[0], tilt[1], r * 2.1f, spin, argb(a * .7f, main));
                if (tier >= 2) {
                    // A gyroscope of rings in three planes, turning against each other.
                    for (int i = 0; i < 3; i++) {
                        double t = now * (.05 + .015 * i) + i * 2.1;
                        Vec3 n = new Vec3(Math.cos(t), Math.sin(t * 1.3 + i), Math.sin(t)).normalize();
                        Vec3[] q = basis(n);
                        ChargeParts.flat(pose, buf, tip, q[0], q[1], r * (1.55f + .2f * i), -spin * (1 + i * .3f), argb(a * .55f, lerpColor(light, main, .5f)));
                    }
                }
            });
        }
        out.submitCustomGeometry(stack, ChargeParts.add("arcane_impact/flash"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, r * 1.15f, 0, argb(a * .9f, main)));
        if (tier >= 1) out.submitCustomGeometry(stack, ChargeParts.add("charge/plasma"), (pose, buf) -> {
            ChargeParts.sprite(pose, buf, view, tip, r * 1.02f, spin * 2, argb(a * .7f, light));
            ChargeParts.sprite(pose, buf, view, tip, r * .95f, -spin * 2.7f + 1, argb(a * .5f, main));
        });
        out.submitCustomGeometry(stack, ChargeParts.add("fire_beam/dot"), (pose, buf) ->
                ChargeParts.sprite(pose, buf, view, tip, r * .62f, 0, argb(a, light)));
    }

    /** Colossal release: the swollen ball bursts in a flash, two rings racing out and sparks flung every way. */
    private void explosion(PoseStack stack, SubmitNodeCollector out, View view, Vec3 tip, float t, float near, float clear, boolean firstPerson) {
        if (t < 0 || t > 22) return;
        float flashK = t / 7;
        if (flashK < 1) {
            // Seen from the caster's own eyes the flash stays a burst at the head, not a white screen.
            float f = firstPerson ? Math.min(.35f, (1.2f + 1.8f * flashK) * near) : (1.2f + 1.8f * flashK) * near;
            float a = (1 - flashK) * (1 - flashK) * (firstPerson ? .35f : 1) * clear;
            out.submitCustomGeometry(stack, ChargeParts.add("fire_impact/flash"), (pose, buf) ->
                    ChargeParts.sprite(pose, buf, view, tip, f, t * .05f, argb(a, style.light())));
            out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) ->
                    ChargeParts.sprite(pose, buf, view, tip, f * 1.2f, 0, argb(a * .45f, style.main())));
        }
        out.submitCustomGeometry(stack, ChargeParts.add("heal/ring"), (pose, buf) -> {
            for (int i = 0; i < 2; i++) {
                float k = (t - i * 3) / 16;
                if (k < 0 || k > 1) continue;
                float size = (.6f + 6.5f * (1 - (1 - k) * (1 - k))) * near;
                float a = (1 - k) * smooth(0, .1f, k) * fade(view.cam(), tip, .2, size * .8 + .2) * (firstPerson ? .3f : 1);
                ChargeParts.sprite(pose, buf, view, tip, size, i, argb(a * .6f, i == 0 ? style.light() : style.main()));
            }
        });
    }
}
