package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.lerpColor;
import static dev.logos.client.ChargeParts.smooth;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ChargeParts.View;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The shared charge round the caster ({@link ChargeCore}), by tier: nothing for an ordinary charge; a
 * simple magic circle under an elaborate one; a richer circle with a counter-turning inner one and a pillar
 * of light round the caster for a grand one; and for a colossal one three circles on the ground and one
 * turning at the waist, a taller pillar, waves running out over the ground (after NS_Explosion_Wind_1), a
 * wind swirling at the feet, and the ground's own pieces lifted into the air and carried round with motes
 * of light (after NS_Tornado_under). Released, it flashes and is gone; dropped, it flickers out. Sizes
 * follow the caster's height. The caster's own pillar is not drawn in first person, where it would be a
 * wall over the whole view.
 */
final class ChargeBody {
    private static final int RELEASE = 26, FIZZLE = 16, WAVE_PERIOD = 18, WAVE_LIFE = 24;

    /** A piece of the ground (or a mote of light, when {@code sprite} is null) carried up and round the caster. */
    private record Lifted(double born, float life, double angle, double omega, float radius, float rise, float size,
            Quaternionf spin, Quaternionf turn, TextureAtlasSprite sprite, int light) {
        Vec3 at(Vec3 feet, double now, float scale) {
            double a = now - born, k = Mth.clamp(a / life, 0, 1);
            double th = angle + omega * a;
            double r = radius * (1 - .3 * k) * scale;
            double y = rise * scale * (1 - Math.exp(-a / (life * .45)));
            return feet.add(Math.cos(th) * r, y + .05, Math.sin(th) * r);
        }
    }

    private final ChargeStyle style;
    private final List<Lifted> lifted = new ArrayList<>();
    private float owedPieces, owedMotes;

    ChargeBody(ChargeStyle style) {
        this.style = style;
    }

    /** One client tick. False once whatever followed the end has played out. */
    boolean tick(Level level, RandomSource r, LiveCharge c) {
        double now = c.age(0);
        lifted.removeIf(l -> now - l.born > l.life);
        LiveCharge.Phase phase = c.phase();
        if (c.tier() == 3 && !phase.over()) {
            float p = c.progress(0);
            float more = phase == LiveCharge.Phase.HELD ? 1 : smooth(.05f, .4f, p);
            float scale = c.height() / 1.8f;
            for (owedPieces += 1.1f * more; owedPieces >= 1; owedPieces--) piece(level, r, c.feet(0), now, scale);
            for (owedMotes += 2.2f * more; owedMotes >= 1; owedMotes--) mote(r, now);
        }
        if (!phase.over()) return true;
        return !lifted.isEmpty() || c.phaseAge(0) < (phase == LiveCharge.Phase.RELEASED ? RELEASE : FIZZLE);
    }

    private void piece(Level level, RandomSource r, Vec3 feet, double now, float scale) {
        if (lifted.size() > 70) return;
        double angle = r.nextDouble() * Math.PI * 2;
        float radius = 1.6f + 3.4f * r.nextFloat();
        BlockPos at = BlockPos.containing(feet.x + Math.cos(angle) * radius * scale, feet.y - .5, feet.z + Math.sin(angle) * radius * scale);
        BlockState ground = level.getBlockState(at);
        if (ground.isAir() || ground.getRenderShape() != RenderShape.MODEL) return;
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(ground).sprite();
        if (!sprite.atlasLocation().getPath().contains("blocks")) return;
        Quaternionf spin = new Quaternionf().rotateXYZ(r.nextFloat() * .08f, r.nextFloat() * .08f, r.nextFloat() * .08f);
        Quaternionf turn = new Quaternionf().rotateXYZ(r.nextFloat() * 6.3f, r.nextFloat() * 6.3f, r.nextFloat() * 6.3f);
        lifted.add(new Lifted(now + r.nextFloat(), 50 + r.nextInt(30), angle, .025 + .02 * r.nextDouble(),
                radius, 1.4f + 2.4f * r.nextFloat(), .05f + .07f * r.nextFloat(), spin, turn, sprite, 0));
    }

    private void mote(RandomSource r, double now) {
        lifted.add(new Lifted(now + r.nextFloat(), 26 + r.nextInt(22), r.nextDouble() * Math.PI * 2, .05 + .05 * r.nextDouble(),
                .7f + 2.8f * r.nextFloat(), 2.2f + 2.4f * r.nextFloat(), .04f + .05f * r.nextFloat(), null, null, null,
                r.nextInt(3) == 0 ? style.light() : style.main()));
    }

    void submit(LiveCharge c, View view, float pt, PoseStack stack, SubmitNodeCollector out) {
        int tier = c.tier();
        Vec3 feet = c.feet(pt);
        double now = c.age(pt);
        float scale = c.height() / 1.8f;
        LiveCharge.Phase phase = c.phase();
        float since = c.phaseAge(pt), p = c.progress(pt);
        // How strongly it shows: building up while charging, steady once held, a flash then gone, or flickering out.
        float show, swell = 1;
        switch (phase) {
            case CHARGING -> show = smooth(0, .2f, p) * smooth(0, 6, (float) now);
            case HELD -> show = 1;
            case RELEASED -> {
                float k = since / 10;
                show = k < .2f ? 1 + k * 1.5f : Math.max(0, 1.3f * (1 - k) / .8f);
                swell = 1 + .1f * Math.min(1, k);
            }
            default -> show = Math.max(0, 1 - since / 12) * (.6f + .4f * Mth.sin(since * 2.1f));
        }
        if (tier >= 1 && show > 0) circles(stack, out, feet, now, tier, scale * swell, Math.min(1.2f, show), pt, c);
        if (tier >= 2 && show > 0 && !c.firstPerson()) pillar(stack, out, feet, now, tier, scale, phase, p, since);
        if (tier >= 2 && phase == LiveCharge.Phase.CHARGING) risingSparks(stack, out, view, feet, now, tier, scale, show);
        if (tier == 3) {
            waves(stack, out, view, feet, now, scale, c, since);
            if (show > 0) swirl(stack, out, feet, now, scale, Math.min(1, show));
            lifted(stack, out, view, feet, now, scale, phase.over() ? Math.max(0, 1 - since / 12) : 1);
        }
    }

    private void circles(PoseStack stack, SubmitNodeCollector out, Vec3 feet, double now, int tier, float scale, float show, float pt, LiveCharge c) {
        Vec3 ground = feet.add(0, .03, 0);
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        int main = lerpColor(style.main(), style.light(), .25f);
        float pulse = .85f + .15f * Mth.sin((float) now * .2f);
        float a = show * pulse;
        float turn = (float) now * .012f;
        out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) ->
                ChargeParts.flat(pose, buf, ground, x, z, (tier == 3 ? 3.6f : tier == 2 ? 2.2f : 1.5f) * scale, 0, argb(a * .3f, style.deep())));
        switch (tier) {
            case 1 -> circle(stack, out, style.elaborate(), ground, x, z, 1.25f * scale, turn, argb(a * .85f, main));
            case 2 -> {
                circle(stack, out, style.grandOuter(), ground, x, z, 1.8f * scale, turn, argb(a * .85f, main));
                circle(stack, out, style.grandInner(), ground.add(0, .01, 0), x, z, 1.05f * scale, -turn * 1.8f, argb(a * .75f, style.light()));
            }
            default -> {
                circle(stack, out, style.colossalOuter(), ground, x, z, 3.4f * scale, turn * .7f, argb(a * .85f, main));
                circle(stack, out, style.colossalMiddle(), ground.add(0, .01, 0), x, z, 2.3f * scale, -turn * 1.3f, argb(a * .75f, main));
                circle(stack, out, style.colossalInner(), ground.add(0, .02, 0), x, z, 1.3f * scale, turn * 2.2f, argb(a * .8f, style.light()));
                // Layers above the ground: one turning at the waist, a small one over the head.
                float h = c.height();
                float bob = .04f * Mth.sin((float) now * .1f);
                circle(stack, out, style.raised(), feet.add(0, h * .5f + bob, 0), x, z, 1.15f * scale, -turn * 3, argb(a * .55f, main));
                circle(stack, out, style.elaborate(), feet.add(0, h * 1.18f - bob, 0), x, z, .7f * scale, turn * 4, argb(a * .5f, style.light()));
            }
        }
    }

    private static void circle(PoseStack stack, SubmitNodeCollector out, String texture, Vec3 c, Vec3 x, Vec3 z, float r, float turn, int color) {
        out.submitCustomGeometry(stack, ChargeParts.add(texture), (pose, buf) -> ChargeParts.flat(pose, buf, c, x, z, r, turn, color));
    }

    private void pillar(PoseStack stack, SubmitNodeCollector out, Vec3 feet, double now, int tier, float scale, LiveCharge.Phase phase, float p, float since) {
        float strength, widen = 1;
        switch (phase) {
            case CHARGING -> strength = smooth(.05f, .45f, p);
            case HELD -> strength = .9f + .1f * Mth.sin((float) now * .3f);
            case RELEASED -> {
                float k = since / 9;
                if (k >= 1) return;
                strength = (1 - k) * (k < .2f ? 1.3f : 1);
                widen = 1 + .5f * k;
            }
            default -> {
                float k = since / 10;
                if (k >= 1) return;
                strength = (1 - k) * .7f;
            }
        }
        float r = (tier == 3 ? 1.25f : .95f) * scale * widen, h = (tier == 3 ? 4.4f : 2.8f) * scale;
        float v = (float) (-now * .035);
        float s = strength;
        out.submitCustomGeometry(stack, ChargeParts.add("charge/pillar"), (pose, buf) -> {
            ChargeParts.cylinder(pose, buf, feet, r, h, 28, (float) (now * .004), 3, v, 1.2f, argb(s * .55f, style.main()), argb(0, style.deep()));
            ChargeParts.cylinder(pose, buf, feet, r * .82f, h * .8f, 24, .5f - (float) (now * .006), 2, v * 1.6f, 1, argb(s * .45f, style.light()), argb(0, style.main()));
            if (tier == 3) ChargeParts.cylinder(pose, buf, feet, r * 1.35f, h * 1.15f, 32, (float) (now * .002), 4, v * .7f, 1.4f, argb(s * .25f, style.deep()), argb(0, style.deep()));
        });
        // A glow round the foot of the pillar.
        out.submitCustomGeometry(stack, ChargeParts.add("heal/ring"), (pose, buf) ->
                ChargeParts.flat(pose, buf, feet.add(0, .04, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r * 1.15f, 0, argb(s * .5f, style.light())));
    }

    /** Streaks of light rising up the pillar. Deterministic from the time, so nothing needs keeping. */
    private void risingSparks(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, int tier, float scale, float show) {
        int count = tier == 3 ? 14 : 8;
        float h = (tier == 3 ? 4.4f : 2.8f) * scale, r = (tier == 3 ? 1.2f : .9f) * scale;
        out.submitCustomGeometry(stack, ChargeParts.add("force/streak"), (pose, buf) -> {
            for (int i = 0; i < count; i++) {
                double period = 22 + (i * 7 % 11);
                double k = ((now + i * 13.7) % period) / period;
                double th = i * 2.399 + now * .01;
                double rr = r * (.55 + .4 * ((i * 37 % 10) / 10.0));
                Vec3 head = feet.add(Math.cos(th) * rr, h * k, Math.sin(th) * rr);
                Vec3 tail = head.add(0, -.5 * scale, 0);
                float a = show * (float) (Math.sin(k * Math.PI)) * .8f;
                ChargeParts.streak(pose, buf, view, tail, head, .07f * scale, argb(a, style.light()));
            }
        });
    }

    /** Colossal: waves of wind running out over the ground from the caster, a last big one on release. */
    private void waves(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, LiveCharge c, float since) {
        LiveCharge.Phase phase = c.phase();
        boolean running = phase == LiveCharge.Phase.HELD || phase == LiveCharge.Phase.CHARGING && c.progress(0) > .15f;
        List<float[]> live = new ArrayList<>();
        if (running) {
            for (int i = (int) Math.floor((now - WAVE_LIFE) / WAVE_PERIOD) + 1; i <= Math.floor(now / WAVE_PERIOD); i++) {
                float k = (float) ((now - i * WAVE_PERIOD) / WAVE_LIFE);
                if (i >= 1 && k >= 0 && k <= 1) live.add(new float[] {k, 1});
            }
        }
        if (phase == LiveCharge.Phase.RELEASED && since < 30) live.add(new float[] {since / 30, 1.6f});
        if (live.isEmpty()) return;
        out.submitCustomGeometry(stack, ChargeParts.add("physical_ray/wind"), (pose, buf) -> {
            for (float[] w : live) {
                float k = w[0], big = w[1];
                float r = (1 + 6.5f * big * (1 - (1 - k) * (1 - k))) * scale;
                float h = (.15f + .75f * (1 - k)) * scale * big;
                float a = (1 - k) * smooth(0, .12f, k) * .7f;
                ChargeParts.cylinder(pose, buf, feet.add(0, .02, 0), r, h, 40, (float) (now * .02), 6, 0, 1, argb(a, style.main()), argb(0, style.light()));
            }
        });
        out.submitCustomGeometry(stack, ChargeParts.add("heal/ring"), (pose, buf) -> {
            for (float[] w : live) {
                float k = w[0], big = w[1];
                float r = (1 + 6.5f * big * (1 - (1 - k) * (1 - k))) * scale;
                ChargeParts.flat(pose, buf, feet.add(0, .05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r * 1.08f, k,
                        argb((1 - k) * smooth(0, .1f, k) * .8f, style.light()));
            }
        });
    }

    /** Colossal: a wind swirling round the feet. */
    private void swirl(PoseStack stack, SubmitNodeCollector out, Vec3 feet, double now, float scale, float show) {
        out.submitCustomGeometry(stack, ChargeParts.add("heal/swirl"), (pose, buf) -> {
            ChargeParts.flat(pose, buf, feet.add(0, .08, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 2.6f * scale, (float) (now * .09), argb(show * .35f, style.main()));
            ChargeParts.flat(pose, buf, feet.add(0, .1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 1.7f * scale, (float) (now * .13 + 1), argb(show * .3f, style.light()));
        });
    }

    private void lifted(PoseStack stack, SubmitNodeCollector out, View view, Vec3 feet, double now, float scale, float fadeOut) {
        if (lifted.isEmpty()) return;
        out.submitCustomGeometry(stack, ChargeParts.BLOCKS, (pose, buf) -> {
            for (Lifted l : lifted) {
                if (l.sprite == null) continue;
                Vec3 at = l.at(feet, now, scale);
                // A piece flying past right in front of the camera would fill the view.
                float a = alpha(l, now) * fadeOut * ChargeParts.fade(view.cam(), at, 1.2, 3);
                if (a <= 0) continue;
                Quaternionf turn = new Quaternionf(l.turn);
                // Tumbling as it rises.
                float age = (float) (now - l.born);
                turn.rotateXYZ(age * .05f, age * .07f, age * .03f);
                TextureAtlasSprite s = l.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                ChargeParts.cube(pose, buf, at, l.size * scale, turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 2.5f,
                        s.getV0() + dv * 2.5f, 0xFFFFFF, a);
            }
        });
        out.submitCustomGeometry(stack, ChargeParts.add("heal/glow"), (pose, buf) -> {
            for (Lifted l : lifted) {
                if (l.sprite != null) continue;
                Vec3 at = l.at(feet, now, scale);
                float a = alpha(l, now) * fadeOut * ChargeParts.fade(view.cam(), at, .8, 2.5);
                if (a > 0) ChargeParts.sprite(pose, buf, view, at, l.size * scale * 1.6f, 0, argb(a, l.light));
            }
        });
    }

    private static float alpha(Lifted l, double now) {
        double k = (now - l.born) / l.life;
        if (k <= 0 || k >= 1) return 0;
        return (float) Math.min(1, Math.min(k / .1, (1 - k) / .2));
    }
}
