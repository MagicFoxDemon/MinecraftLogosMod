package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import static dev.logos.client.FrostBeamDraw.*;

/**
 * A lasting frost beam after Piloto Studio {@code Frost/FrostRay}: a streaming ice body (the FrostBeam line
 * renderer's IceBeam mask) around a white-hot core in a cold haze, a starry band and two strands of frost
 * vapour twisting around it, and what the prefab's beam systems shed along the line: harsh cold smoke racing
 * outward, glinting dots and shiny ice flecks, ice crystals forming and spinning off the line. The wand head
 * jets small crystals forward under a restrained soft cross; the end races out as a bright frost spearhead
 * and, once it holds, bursts into ice spikes, a turning frost sigil, soft crosses, pulsing rings, mist and
 * snow. Everything is stateless in time (slots that recycle), custom geometry submitted through {@code out}.
 */
final class FrostBeamVisual {
    /** The radius of the last frost beam drawn, for the wake it leaves (the wake is only handed the line). */
    static float lastRadius = .35f;

    private Vec3 lastFrom;
    private double prevLength = -1, prevTime, lastGrowth = Double.NaN;
    private long checkedAt = Long.MIN_VALUE;
    private boolean blocked;

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera, PoseStack stack, SubmitNodeCollector out) {
        lastFrom = from;
        lastRadius = radius;
        Line line = new Line(from, to);
        double length = line.length;
        if (length < .05) return;
        double time = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);

        // The public reach races the end out; the hit at the end waits until it holds still.
        if (Double.isNaN(lastGrowth)) lastGrowth = time;
        if (prevLength >= 0 && time > prevTime + 1e-4) {
            if ((length - prevLength) / (time - prevTime) > 1.5) lastGrowth = time;
        }
        if (time != prevTime) { prevLength = length; prevTime = time; }
        float held = smooth((float) (time - lastGrowth - .5) / 4);
        if (level.getGameTime() != checkedAt) {
            checkedAt = level.getGameTime();
            blocked = blocked(level, line);
        }

        Vec3 eye = camera.pos;
        Vec3 camRight = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0)));
        Vec3 camUp = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0)));
        // A first-person caster has the wand head right beside the camera.
        boolean close = eye.distanceTo(from) < 1.6;
        float k = Math.max(.4f, Math.min(3, radius / .35f));
        double r = .35 * k;
        float t = (float) (time % 24000);

        // Thin at the wand head, full a block and a half out; the racing tip narrows to a spearhead.
        Width taper = s -> {
            double head = Math.min(1, s / 1.4);
            double tip = Math.max(0, Math.min(1, (length - s) / .9));
            return (.28 + .72 * head * head * (3 - 2 * head)) * (1 - (1 - held) * (1 - (.35 + .65 * tip)));
        };

        // ---- the body ------------------------------------------------------------------------------
        // Beside a first-person camera the first blocks of the beam fade out entirely, so it does not
        // pile up over the view; seen from elsewhere only a hint of that fade is kept.
        double near = close ? 1.1 : .5, far = close ? 4.5 : 2.4;
        float floor = close ? 0 : .25f;
        out.submitCustomGeometry(stack, HAZE, (p, buf) -> {
            ribbon(p, buf, eye, line, 0, length, s -> 1.9 * r * taper.at(s),
                    s -> argb(.3f, mix(0xCFE6FF, 0x9DBCE4, (float) (s / Math.max(8, length)))), -t * .035f, 1 / 4.5f, near, far, 0);
            ribbon(p, buf, eye, line, 0, length, s -> 1.4 * r * taper.at(s),
                    s -> argb(.22f, 0xE4F3FF), .41f + t * .02f, 1 / 6f, near, far, 0);
        });
        out.submitCustomGeometry(stack, ICE, (p, buf) -> {
            Tint ice = s -> argb(.72f, gradient((float) (s / Math.max(6, length))));
            ribbon(p, buf, eye, line, 0, length, s -> 1.05 * r * taper.at(s), ice, -t * .11f, 1 / 7f, near, far, floor);
            ribbon(p, buf, eye, line, 0, length, s -> .75 * r * taper.at(s), s -> argb(.55f, PALE), .37f - t * .07f, 1 / 5f, near, far, floor);
        });
        out.submitCustomGeometry(stack, STARRY, (p, buf) -> {
            ribbon(p, buf, eye, line, 0, length, s -> .95 * r * taper.at(s), s -> argb(.36f, 0xB8E4FF), -t * .06f, 1 / 3.5f, near, far, floor);
            ribbon(p, buf, eye, line, 0, length, s -> .65 * r * taper.at(s), s -> argb(.22f, PALE), .5f - t * .025f, 1 / 2.3f, near, far, floor);
        });
        out.submitCustomGeometry(stack, LASER, (p, buf) -> {
            // A breath of cold runs out along the core.
            Width pulse = s -> taper.at(s) * (1 + .14 * Math.sin(s * 2.2 - t * 1.3));
            ribbon(p, buf, eye, line, 0, length, s -> .5 * r * pulse.at(s), s -> argb(.38f, DEEP), 0, 1, near, far, floor);
            ribbon(p, buf, eye, line, 0, length, s -> .17 * r * pulse.at(s), s -> argb(.85f, ICY), 0, 1, near * .8, far * .8, floor);
        });
        // Two strands of frost vapour twisting around the beam.
        out.submitCustomGeometry(stack, WISP, (p, buf) -> {
            int segments = clamp((int) (length * 8), 24, 320);
            for (int strand = 0; strand < 2; strand++) {
                double phase = strand * Math.PI;
                int rgb = strand == 0 ? 0xCDEBFF : 0x9CD2FF;
                double pitch = strand == 0 ? 2.3 : -1.7;
                // Wandering radius and pitch, so the strands read as vapour rather than a coil.
                Offset helix = s -> line.radial(s * pitch + Math.sin(s * .7 + phase) * .9 - t * .2 + phase)
                        .scale(r * taper.at(s) * (.7 + .35 * Math.sin(s * 1.1 + phase * 1.3 - t * .05)));
                ribbon(p, buf, eye, line, .3, length, helix, s -> .13 * r * taper.at(s),
                        s -> argb(.38f * (float) (.55 + .45 * Math.sin(s * .9 - t * .15 + phase)), rgb), -t * .09f + strand * .5f, 1 / 2.5f, near + .2, far, 0, segments);
            }
        });

        // ---- what the beam sheds along the line ---------------------------------------------------
        double clear = Math.min(length * .5, close ? 1.4 : .6);
        out.submitCustomGeometry(stack, FLECKS, (p, buf) -> flecks(p, buf, eye, camRight, camUp, line, clear, r, t));
        out.submitCustomGeometry(stack, CRYSTAL, (p, buf) -> {
            crystals(p, buf, eye, line, clear, r, t);
            jets(p, buf, eye, line, r, t);
        });
        out.submitCustomGeometry(stack, SMOKE, (p, buf) -> smoke(p, buf, eye, camRight, camUp, line, clear, r, t));
        out.submitCustomGeometry(stack, GLINTS, (p, buf) -> glints(p, buf, eye, camRight, camUp, line, clear, r, t));

        // ---- the wand head: a restrained soft cross and frost sigil, small and pushed ahead in first person ----
        Vec3 head = close ? from.add(line.axis.scale(Math.min(1, length * .5))) : from.add(line.axis.scale(.05));
        float headScale = close ? .35f : 1, headAlpha = close ? .35f : 1;
        out.submitCustomGeometry(stack, CROSS, (p, buf) ->
                sprite(p, buf, eye, camRight, camUp, head, .55 * k * headScale * (1 + .08 * Math.sin(t * .9)), t * .03, 0, 0, 1, 1,
                        argb(.75f * headAlpha, 0x9FE8FF)));
        out.submitCustomGeometry(stack, GLOW, (p, buf) ->
                sprite(p, buf, eye, camRight, camUp, head, .5 * k * headScale, 0, 0, 0, 1, 1, argb(.7f * headAlpha, BLUE)));
        if (!close) out.submitCustomGeometry(stack, RUNE, (p, buf) ->
                disc(p, buf, eye, from.add(line.axis.scale(Math.min(.45, length * .4))), line.right, line.up, line.axis,
                        .32 * k, t * .05, argb(.6f, BLUE)));

        // ---- the end: a racing spearhead, then the hit once it holds ----
        Vec3 end = to.subtract(line.axis.scale(Math.min(.15, length * .2)));
        float hit = held * (blocked ? 1 : .45f);
        out.submitCustomGeometry(stack, GLOW, (p, buf) ->
                sprite(p, buf, eye, camRight, camUp, end, (.55 + .75 * hit) * k, 0, 0, 0, 1, 1, argb(.85f, BLUE)));
        out.submitCustomGeometry(stack, STAR, (p, buf) -> {
            float a = (1 - held) * .95f;
            sprite(p, buf, eye, camRight, camUp, to, .7 * k, t * .2, 0, 0, 1, 1, argb(a, PALE));
            sprite(p, buf, eye, camRight, camUp, to, .45 * k, -t * .13 + .8, 0, 0, 1, 1, argb(a * .8f, ICY));
        });
        if (hit > .01) terminus(out, stack, eye, camRight, camUp, line, end, k, t, hit);
    }

    void end() {}

    /** The FrostBeam line colours, pulled into the frost palette: pale cyan at the wand, deep ice blue far out. */
    private static int gradient(float s) {
        return s < .58f ? mix(0x7FD3F2, 0x5AAEF2, s / .58f) : mix(0x5AAEF2, 0x3479E0, (s - .58f) / .42f);
    }

    /** Ice spikes, a turning frost sigil, soft crosses, a pulsing ring, mist and snow at the end. */
    private static void terminus(SubmitNodeCollector out, PoseStack stack, Vec3 eye, Vec3 camRight, Vec3 camUp, Line line,
            Vec3 end, float k, float t, float hit) {
        out.submitCustomGeometry(stack, SPIKES, (p, buf) -> {
            for (int i = 0; i < 9; i++) {
                float life = 5 + 4 * hash(i, 0, 71);
                float x = (t + hash(i, 1, 71) * life) / life;
                int cycle = (int) Math.floor(x);
                float age = x - cycle;
                double angle = hash(i, cycle, 72) * Math.PI * 2;
                // Thrown back toward the caster and out to the sides, never through the wall.
                Vec3 dir = line.radial(angle).scale(.8).subtract(line.axis.scale(.25 + .6 * hash(i, cycle, 73))).normalize();
                double len = (.45 + .55 * hash(i, cycle, 74)) * k * (.55 + .45 * smooth(age * 3));
                int frame = (int) (hash(i, cycle, 75) * 4);
                float u = frame % 2 * .5f, v = frame / 2 * .5f;
                streak(p, buf, eye, end, dir, len, len * .55, u, v, u + .5f, v + .5f,
                        argb(hit * envelope(age, .15f, .5f), mix(PALE, BLUE, hash(i, cycle, 76))));
            }
        });
        out.submitCustomGeometry(stack, CROSS, (p, buf) -> {
            sprite(p, buf, eye, camRight, camUp, end, 1.05 * k * (.6 + .4 * hit), t * .012, 0, 0, 1, 1, argb(.6f * hit, 0x9FFEFF));
            sprite(p, buf, eye, camRight, camUp, end, .8 * k * (.6 + .4 * hit), -t * .019 + 1, 0, 0, 1, 1, argb(.55f * hit, 0xC8F4FF));
        });
        out.submitCustomGeometry(stack, RUNE, (p, buf) ->
                disc(p, buf, eye, end, line.right, line.up, line.axis, (.55 + .1 * Math.sin(t * .2)) * k * hit, -t * .045, argb(.8f * hit, BLUE)));
        out.submitCustomGeometry(stack, RING, (p, buf) -> {
            for (int i = 0; i < 2; i++) {
                float age = ((t / 14f) + i * .5f) % 1;
                sprite(p, buf, eye, camRight, camUp, end, (.25 + 1.05 * Math.sqrt(age)) * k, i * 1.7, 0, 0, 1, 1,
                        argb(.5f * hit * (1 - smooth(age)) * smooth(age * 6), ICY));
            }
        });
        out.submitCustomGeometry(stack, MIST, (p, buf) -> {
            for (int i = 0; i < 6; i++) {
                float life = 26 + 14 * hash(i, 0, 81);
                float x = (t + hash(i, 1, 81) * life) / life;
                int cycle = (int) Math.floor(x);
                float age = x - cycle;
                Vec3 drift = line.radial(hash(i, cycle, 82) * Math.PI * 2).scale(.9).subtract(line.axis.scale(.5)).add(0, .25, 0);
                Vec3 at = end.add(drift.scale(age * .8 * k));
                int frame = (int) (hash(i, cycle, 83) * 4);
                cell(p, buf, eye, camRight, camUp, at, (.35 + .45 * age) * k, hash(i, cycle, 84) * 6 + age * .6, frame, 2, 2,
                        fadeNear(argb(.42f * hit * envelope(age, .2f, .6f), mix(0xD4E8FF, 0xA3BEE0, age)), at, eye, 1.5, 3.5, 0));
            }
        });
        out.submitCustomGeometry(stack, SNOW, (p, buf) -> {
            for (int i = 0; i < 7; i++) {
                float life = 30 + 20 * hash(i, 0, 91);
                float x = (t + hash(i, 1, 91) * life) / life;
                int cycle = (int) Math.floor(x);
                float age = x - cycle;
                Vec3 thrown = line.radial(hash(i, cycle, 92) * Math.PI * 2).scale(.5 + .6 * hash(i, cycle, 93)).subtract(line.axis.scale(.4));
                Vec3 at = end.add(thrown.scale(k * Math.min(1, age * 3))).add(0, -age * 1.1 * k, 0)
                        .add(line.right.scale(Math.sin(age * 9 + i) * .08));
                sprite(p, buf, eye, camRight, camUp, at, (.06 + .06 * hash(i, cycle, 94)) * k, age * 3 + i, 0, 0, 1, 1,
                        fadeNear(argb(.9f * hit * envelope(age, .1f, .35f), 0xE6F6FF), at, eye, 1.5, 3.5, 0));
            }
        });
    }

    /** Harsh cold smoke racing outward along the beam (Smoke_Harsh_Disolve: speed along the line, 2x2 sheet). */
    private static void smoke(PoseStack.Pose p, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Line line,
            double clear, double r, float t) {
        int slots = clamp((int) (line.length * 1.6), 6, 96);
        for (int i = 0; i < slots; i++) {
            float life = 16 + 14 * hash(i, 0, 11);
            float x = (t + hash(i, 1, 11) * life) / life;
            int cycle = (int) Math.floor(x);
            float age = x - cycle;
            double s = clear + (line.length - clear) * hash(i, cycle, 12) + age * life * .16;
            if (s > line.length) continue;
            Vec3 at = line.at(s).add(line.radial(hash(i, cycle, 13) * Math.PI * 2).scale(r * (.15 + .5 * hash(i, cycle, 14)) * (1 + age)));
            float c = hash(i, cycle, 15);
            int rgb = c < .5f ? mix(CYAN, 0x61A0BE, c * 2) : mix(0x61A0BE, 0x2F7DFF, c * 2 - 1);
            int color = fadeNear(argb(.32f * envelope(age, .08f, .2f), rgb), at, eye, .9, 3, 0);
            cell(p, buf, eye, camRight, camUp, at, r * (.6 + .35 * hash(i, cycle, 16)),
                    hash(i, cycle, 17) * 6.28 + age * (hash(i, cycle, 18) - .5) * 3, (int) (hash(i, cycle, 19) * 4), 2, 2, color);
        }
    }

    /** Glinting dots on the beam's edge (Flare_Glowdot / Glowdots_Speckles), twinkling as they come and go. */
    private static void glints(PoseStack.Pose p, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Line line,
            double clear, double r, float t) {
        int slots = clamp((int) (line.length * 2.2), 8, 128);
        for (int i = 0; i < slots; i++) {
            float life = 20 + 20 * hash(i, 0, 21);
            float x = (t + hash(i, 1, 21) * life) / life;
            int cycle = (int) Math.floor(x);
            float age = x - cycle;
            double s = clear + (line.length - clear) * hash(i, cycle, 22);
            Vec3 at = line.at(s).add(line.radial(hash(i, cycle, 23) * Math.PI * 2 + age * .6)
                    .scale(r * (.7 + .7 * hash(i, cycle, 24) + .4 * age)));
            float twinkle = .45f + .55f * (float) Math.pow(Math.max(0, Math.sin(age * 14 + i)), 3);
            int color = fadeNear(argb(envelope(age, .15f, .4f) * twinkle, hash(i, cycle, 25) < .5f ? PALE : 0x9FE8FF), at, eye, 1, 3, 0);
            cell(p, buf, eye, camRight, camUp, at, r * (.25 + .55 * hash(i, cycle, 26)), 0, (int) (hash(i, cycle, 27) * 9), 3, 3, color);
        }
    }

    /** Shiny ice flecks hanging on the beam's edge (Flecks_Shiny_Alpha), drifting out and turning. */
    private static void flecks(PoseStack.Pose p, VertexConsumer buf, Vec3 eye, Vec3 camRight, Vec3 camUp, Line line,
            double clear, double r, float t) {
        int slots = clamp((int) (line.length * 1.5), 6, 96);
        for (int i = 0; i < slots; i++) {
            float life = 20 + 20 * hash(i, 0, 31);
            float x = (t + hash(i, 1, 31) * life) / life;
            int cycle = (int) Math.floor(x);
            float age = x - cycle;
            double s = clear + (line.length - clear) * hash(i, cycle, 32);
            Vec3 at = line.at(s).add(line.radial(hash(i, cycle, 33) * Math.PI * 2).scale(r * (.8 + .5 * hash(i, cycle, 34) + .7 * age)))
                    .add(0, -age * .15, 0);
            int color = fadeNear(argb(.85f * envelope(age, .1f, .35f), hash(i, cycle, 35) < .5f ? 0xF2FBFF : 0xC2E6FF), at, eye, 1, 3, 0);
            cell(p, buf, eye, camRight, camUp, at, r * (.28 + .2 * hash(i, cycle, 36)), hash(i, cycle, 37) * 6.28 + age * 2,
                    (int) (hash(i, cycle, 38) * 4), 2, 2, color);
        }
    }

    /** Ice crystals forming on the line, spinning slowly off it and melting away. */
    private static void crystals(PoseStack.Pose p, VertexConsumer buf, Vec3 eye, Line line, double clear, double r, float t) {
        int slots = clamp((int) (line.length * 1.3), 6, 80);
        for (int i = 0; i < slots; i++) {
            float life = 26 + 20 * hash(i, 0, 41);
            float x = (t + hash(i, 1, 41) * life) / life;
            int cycle = (int) Math.floor(x);
            float age = x - cycle;
            double s = clear + (line.length - clear) * hash(i, cycle, 42) + age * .4;
            if (s > line.length) continue;
            double angle = hash(i, cycle, 43) * Math.PI * 2 + age * 1.2;
            Vec3 radial = line.radial(angle);
            Vec3 centre = line.at(s).add(radial.scale(r * (.75 + .45 * hash(i, cycle, 44) + .6 * age)));
            Vec3 dir = line.axis.scale(.6).add(radial.scale(.8 * (hash(i, cycle, 45) - .5)))
                    .add(radial.cross(line.axis).scale(Math.sin(age * 5 + i))).normalize();
            double size = r * (.13 + .14 * hash(i, cycle, 46)) * smooth(age * 5) * smooth((1 - age) * 3.5f);
            crystal(p, buf, eye, centre, dir, size, age * 4 + i, (int) (215 * nearFactor(centre, eye, 1.2, 3)));
        }
    }

    /** Small crystals jetting forward from the wand head down the beam (Ice_Crystal: a cone, fast and short-lived). */
    private static void jets(PoseStack.Pose p, VertexConsumer buf, Vec3 eye, Line line, double r, float t) {
        for (int i = 0; i < 12; i++) {
            float life = 10 + 4 * hash(i, 0, 51);
            float x = (t + hash(i, 1, 51) * life) / life;
            int cycle = (int) Math.floor(x);
            float age = x - cycle;
            double speed = (.2 + .25 * hash(i, cycle, 52)) * life;
            double s = .3 + speed * age;
            if (s > line.length - .3) continue;
            Vec3 spread = line.radial(hash(i, cycle, 53) * Math.PI * 2).scale(.22 * hash(i, cycle, 54) * s + r * .3);
            Vec3 centre = line.at(s).add(spread);
            Vec3 dir = line.axis.add(spread.scale(.3)).normalize();
            double size = r * (.12 + .1 * hash(i, cycle, 55)) * smooth(age * 6) * smooth((1 - age) * 4);
            crystal(p, buf, eye, centre, dir, size, age * 6, (int) (220 * nearFactor(centre, eye, 1.4, 3.2)));
        }
    }
}
