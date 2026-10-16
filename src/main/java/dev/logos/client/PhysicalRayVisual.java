package dev.logos.client;

import static dev.logos.client.LogosFx.range;
import static dev.logos.client.ProjectileParticles.configureTexture;
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
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;

/**
 * A physical {@code ray}, after EVA's Ramiel: one needle of white light punched through in a tick and a half,
 * a cross flash where it leaves, cones of shocked air (the sonic boom) and vapour rings pushed out along its
 * length, a burst of compressed air where it lands on a block or a body, and then a hair-thin line that
 * stutters on and off and is gone within about two seconds. Rebuilt from the burst half of Piloto Studio's
 * {@code PS_UltimateBeam_Wind} / {@code _Light}; textures in {@code physical_ray/}.
 */
final class PhysicalRayVisual implements RayVisual {
    private static final RenderType LINE = material("line", "glow", "RAY_LINE", true);
    private static final RenderType FLASH = material("flash", "cross", "RAY_FLASH", true);
    private static final RenderType GLOW = material("glow", "glow", "RAY_FLASH", true);
    private static final RenderType AIR = material("air", "air", "RAY_AIR", false);
    private static final FX IMPACT = impact();

    /** Ticks for the tip to cross the whole length. */
    private static final float SHOOT = 1.5f;
    /** The bright needle, then it narrows to the afterline. */
    private static final float HOT = 6, AFTER = 8;
    /** The afterline stutters for about five and a half seconds and is gone by here; nothing is left after {@link #LIFE}. */
    private static final float GONE = 118, LIFE = 120;
    /** Ticks each blink of the afterline holds (scaled with its length). */
    private static final float BLINK = 4.5f;
    private static final float RING_LIFE = 12, RING_SPACING = 3;

    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO, axis = new Vec3(0, 0, 1), right = new Vec3(1, 0, 0), up = new Vec3(0, 1, 0);
    private float length;
    private boolean strikes;
    private long born;
    private int seed;
    private FXRuntime burst;
    /** Only the narrowing needle and the stuttering afterline, with no launch, landing or sound: a physical beam's wake. */
    private final boolean afterlineOnly;

    PhysicalRayVisual() { this(false); }

    private PhysicalRayVisual(boolean afterlineOnly) { this.afterlineOnly = afterlineOnly; }

    /** The afterline alone, joining the ray's life where its needle starts to narrow ({@link PhysicalBeamWake}). */
    static PhysicalRayVisual afterline() { return new PhysicalRayVisual(true); }

    @Override public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        this.to = to;
        Vec3 delta = to.subtract(from);
        length = (float) delta.length();
        axis = length < 1e-4 ? new Vec3(0, 0, 1) : delta.scale(1 / length);
        right = perpendicular(axis, new Vec3(0, 1, 0));
        up = axis.cross(right);
        strikes = end == Vfx.RayEnd.BLOCK || end == Vfx.RayEnd.CREATURE;
        born = level.getGameTime();
        seed = level.getRandom().nextInt();
        if (afterlineOnly) {
            born -= (long) HOT;
            return;
        }
        level.playLocalSound(from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, .45f, 1.9f, false);
        level.playLocalSound(from.x, from.y, from.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .5f, .6f, false);
        kickGround(level);
        if (!strikes) return;
        Vec3 at = impactPoint();
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, .55f, 1.25f, false);
        burst = IMPACT.createRuntime();
        for (var object : burst.objects.values()) if (object instanceof ParticleEmitter e)
            e.config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
        // Photon cones open along local +Y: turn it back toward the caster so the struck body does not hide it.
        burst.getRoot().updatePos(at.toVector3f());
        burst.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.scale(-1).toVector3f()));
        burst.emit(new Executor(level));
        if (end == Vfx.RayEnd.BLOCK) debris(level, at);
    }

    @Override
    public boolean tick(Level level) {
        long age = level.getGameTime() - born;
        if (burst != null && age > 40) { burst.destroy(false); burst = null; }
        return age < LIFE;
    }

    @Override
    public void end() {
        if (burst != null) { burst.destroy(false); burst = null; }
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack stack, SubmitNodeCollector out) {
        if (length < .05f) return;
        float t = Math.max(0, level.getGameTime() - born + partialTick);
        if (t >= GONE) return;
        Vec3 eye = camera.pos;
        // From behind the wand (first person) the launch dressing moves a little way out and keeps small.
        float near = (float) Math.min(1, Math.max(0, (eye.distanceTo(from) - .8) / 3));
        float reach = Math.min(1, t / SHOOT);
        Vec3 tip = from.add(axis.scale(length * (1 - (1 - reach) * (1 - reach))));
        Vec3 at = impactPoint();
        stack.pushPose();
        stack.translate(-eye.x, -eye.y, -eye.z);
        out.submitCustomGeometry(stack, LINE, (pose, buf) -> {
            if (t < AFTER) {
                float k = smooth(Math.max(0, t - 1) / (AFTER - 1));
                needle(pose, buf, from, tip, eye, .17f + (.05f - .17f) * k, t < HOT ? 1 : 1 - (t - HOT) / (AFTER - HOT) * .45f);
            } else afterline(pose, buf, eye, t);
        });
        if (afterlineOnly) {
            stack.popPose();
            return;
        }
        out.submitCustomGeometry(stack, GLOW, (pose, buf) -> {
            float flash = flashCurve(t, 10);
            if (flash > 0) {
                Vec3 c = muzzle(near);
                float r = (.8f + 1.6f * near) * flash;
                flare(pose, buf, c, camera, Math.min(r, .3f * (float) eye.distanceTo(c)), 0, alpha(.5f * (.6f + .4f * near)));
            }
            if (strikes) {
                float hit = flashCurve(t - SHOOT, 9);
                if (hit > 0) flare(pose, buf, at, camera, 2.6f * hit, 0, alpha(.6f));
            }
        });
        out.submitCustomGeometry(stack, FLASH, (pose, buf) -> {
            float flash = flashCurve(t, 9);
            if (flash > 0) {
                Vec3 c = muzzle(near);
                float r = (.9f + 2f * near) * flash;
                // Never more than about a fifth of the view across, however close the camera.
                r = Math.min(r, .4f * (float) eye.distanceTo(c));
                flare(pose, buf, c, camera, r, .05f + t * .01f, alpha(.95f * (.65f + .35f * near)));
            }
            if (strikes) {
                float hit = flashCurve(t - SHOOT, 7);
                if (hit > 0) flare(pose, buf, at, camera, 1.9f * hit, -.12f, alpha(.95f));
            }
        });
        out.submitCustomGeometry(stack, AIR, (pose, buf) -> {
            // The sonic boom: two cones of shocked air flung forward off the muzzle.
            // Seen from the caster's own eyes they start further out, smaller and fainter, so the view stays clear.
            Vec3 base = from.add(axis.scale(.25 + 1.6 * (1 - near)));
            float own = .5f + .5f * near;
            boom(pose, buf, base, axis, t, 10, 2.4f * own, own, seed);
            boom(pose, buf, base, axis, t - 2, 9, 1.5f * own, .75f * own, seed + 7);
            // Vapour rings pushed out along the line as the tip passes.
            int rings = Math.min(20, (int) ((length - 1.5f) / RING_SPACING));
            for (int i = 1; i <= rings; i++) {
                float along = Math.min(length - .8f, i * RING_SPACING + .5f * hash(seed, i, 3));
                float age = t - SHOOT * (along / length) - .4f * hash(seed, i, 5);
                if (age < 0 || age > RING_LIFE) continue;
                float p = age / RING_LIFE, e = 1 - (1 - p) * (1 - p) * (1 - p);
                float tiltX = (hash(seed, i, 7) - .5f) * .5f, tiltY = (hash(seed, i, 11) - .5f) * .5f;
                Vec3 n = axis.add(right.scale(tiltX)).add(up.scale(tiltY)).normalize();
                Vec3 c = from.add(axis.scale(along + .6 * e));
                float a = (float) Math.pow(1 - p, 1.5);
                ring(pose, buf, c, n, .15f + 1.25f * e, .08f + .2f * e, a, .3f * e, seed + i * 13, 40);
            }
            if (strikes) {
                Vec3 back = axis.scale(-1);
                float h = t - SHOOT;
                if (h >= 0 && h < 14) {
                    float p = h / 14, e = 1 - (1 - p) * (1 - p) * (1 - p);
                    ring(pose, buf, at, back, .3f + 3.4f * e, .2f + .45f * e, (1 - p) * .95f, .35f * e, seed + 101, 64);
                }
                if (h >= 2 && h < 14) {
                    float p = (h - 2) / 12, e = 1 - (1 - p) * (1 - p);
                    ring(pose, buf, at.add(back.scale(.15)), back, .2f + 2.1f * e, .14f + .3f * e, (1 - p) * .7f, .2f * e, seed + 211, 56);
                }
                boom(pose, buf, at.add(back.scale(.1)), back, h, 11, 2.6f, .9f, seed + 307);
            }
        });
        stack.popPose();
    }

    // ---- shapes ----------------------------------------------------------------------------------

    /** Where the ray stops on what it hit, pulled back a hair so the burst is not inside the block. */
    private Vec3 impactPoint() {
        return to.subtract(axis.scale(Math.min(.12, length * .5)));
    }

    /** Where the cross flash sits: on the muzzle from outside, a little way down the line from the caster's own eyes. */
    private Vec3 muzzle(float near) {
        return from.add(axis.scale(Math.min(length * .5, 1.4 * (1 - near))));
    }

    /** Snaps on within a third of a tick, then decays to 0 at {@code life} like an exposure flash. */
    private static float flashCurve(float t, float life) {
        if (t < 0 || t >= life) return 0;
        if (t < .35f) return .55f + .45f * t / .35f;
        float p = (t - .35f) / (life - .35f);
        return (float) Math.pow(1 - p, 1.4);
    }

    /** The needle from {@code a} to {@code b}, cut finer near the start so its width can follow the camera distance. */
    private void needle(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 eye, float width, float alpha) {
        double len = a.distanceTo(b);
        if (len < 1e-3) return;
        double[] cuts = {.3, .8, 1.6, 3.2, 6};
        double prev = 0;
        for (double cut : cuts) {
            if (cut >= len) break;
            band(pose, buf, a.add(axis.scale(prev)), a.add(axis.scale(cut)), eye, width, alpha);
            prev = cut;
        }
        band(pose, buf, a.add(axis.scale(prev)), b, eye, width, alpha);
    }

    /** What the ray leaves: a hairline broken into dashes that blink out one by one. */
    private void afterline(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, float t) {
        float p = (t - AFTER) / (GONE - AFTER);
        float fade = 1 - p * p;
        int frame = (int) (t / BLINK);
        // Over its life fewer dashes stay lit.
        float keep = .9f - .75f * p;
        double at = 0;
        int i = 0;
        while (at < length && i < 160) {
            double dash = .6 + 1.4 * hash(seed, i, 17);
            double gap = .15 + .5 * hash(seed, i, 19) * (.4 + p);
            double end = Math.min(length, at + dash);
            if (hash(seed + frame * 31, i, 23) < keep) {
                float flicker = .55f + .45f * hash(seed + frame * 7, i, 29);
                band(pose, buf, from.add(axis.scale(at)), from.add(axis.scale(end)), eye, .05f, fade * flicker);
            }
            at = end + gap;
            i++;
        }
    }

    private void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 eye, float width, float alpha) {
        Vec3 side = perpendicular(axis, eye.subtract(a.add(b).scale(.5)));
        float wa = width(a, eye, width), wb = width(b, eye, width);
        int color = alpha(alpha);
        vertex(pose, buf, a.add(side.scale(wa)), 0, 0, color);
        vertex(pose, buf, a.subtract(side.scale(wa)), 0, 1, color);
        vertex(pose, buf, b.subtract(side.scale(wb)), 1, 1, color);
        vertex(pose, buf, b.add(side.scale(wb)), 1, 0, color);
    }

    /** Half-width at {@code p}: thinner right at the camera, never thinner than about a pixel and a half far off. */
    private static float width(Vec3 p, Vec3 eye, float width) {
        float d = (float) p.distanceTo(eye);
        return Math.max(width * Math.min(1, .08f + d / 6), d * .0055f);
    }

    private static void flare(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, CameraRenderState camera, float radius, float roll, int color) {
        if (radius <= 0) return;
        Vector3f x = new Vector3f((float) Math.cos(roll), (float) Math.sin(roll), 0);
        Vector3f y = new Vector3f(-(float) Math.sin(roll), (float) Math.cos(roll), 0);
        Vec3 r = new Vec3(camera.orientation.transform(x)).scale(radius), u = new Vec3(camera.orientation.transform(y)).scale(radius);
        vertex(pose, buf, at.subtract(r).add(u), 0, 0, color);
        vertex(pose, buf, at.subtract(r).subtract(u), 0, 1, color);
        vertex(pose, buf, at.add(r).subtract(u), 1, 1, color);
        vertex(pose, buf, at.add(r).add(u), 1, 0, color);
    }

    /**
     * A cone of shocked air: a ring whose outer edge is swept back against {@code dir}, flung forward and widening.
     * {@code t} is its own age; it is drawn for {@code life} ticks.
     */
    private static void boom(PoseStack.Pose pose, VertexConsumer buf, Vec3 base, Vec3 dir, float t, float life, float size, float alpha, int seed) {
        if (t < 0 || t >= life) return;
        float p = t / life, e = 1 - (1 - p) * (1 - p) * (1 - p);
        Vec3 c = base.add(dir.scale(.2 + 1.2 * e * size / 2.2));
        float inner = .1f + .45f * size * e, outer = .25f + size * e;
        float sweep = (.15f + .55f * e) * size;
        int color = alpha((1 - p) * (1 - p) * alpha) & 0xFF000000 | 0xA6B0BC;
        Vec3 rr = perpendicular(dir, new Vec3(0, 1, 0)), uu = dir.cross(rr);
        int segments = 48, rows = 4;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            for (int j = 0; j < rows; j++) {
                float v0 = j / (float) rows, v1 = (j + 1) / (float) rows;
                vertex(pose, buf, conePoint(c, dir, rr, uu, a0, v0, inner, outer, sweep, seed), i / (float) segments, v0, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a1, v0, inner, outer, sweep, seed), (i + 1) / (float) segments, v0, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a1, v1, inner, outer, sweep, seed), (i + 1) / (float) segments, v1, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a0, v1, inner, outer, sweep, seed), i / (float) segments, v1, color);
            }
        }
    }

    private static Vec3 conePoint(Vec3 c, Vec3 dir, Vec3 rr, Vec3 uu, double angle, float v, float inner, float outer, float sweep, int seed) {
        double ruffle = 1 + .06 * Math.sin(angle * 6 + seed) + .03 * Math.sin(angle * 13 - seed * .7);
        double r = (inner + (outer - inner) * v) * ruffle;
        return c.add(rr.scale(Math.cos(angle) * r)).add(uu.scale(Math.sin(angle) * r)).subtract(dir.scale(sweep * v * v));
    }

    /** A pressure ring in the plane across {@code n}, its edge ruffled and its rim curled back by {@code curl}. */
    private static void ring(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 n, float radius, float width, float alpha,
            float curl, int seed, int segments) {
        if (alpha <= .004f) return;
        Vec3 rr = perpendicular(n, new Vec3(0, 1, 0)), uu = n.cross(rr);
        int color = alpha(Math.min(1, alpha)) & 0xFF000000 | 0x9AA6B4;
        int rows = 3;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            for (int j = 0; j < rows; j++) {
                float v0 = j / (float) rows, v1 = (j + 1) / (float) rows;
                vertex(pose, buf, ringPoint(c, n, rr, uu, a0, v0, radius, width, curl, seed), i / (float) segments, v0, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a1, v0, radius, width, curl, seed), (i + 1) / (float) segments, v0, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a1, v1, radius, width, curl, seed), (i + 1) / (float) segments, v1, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a0, v1, radius, width, curl, seed), i / (float) segments, v1, color);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 c, Vec3 n, Vec3 rr, Vec3 uu, double angle, float v, float radius, float width, float curl, int seed) {
        double ruffle = .07 * Math.sin(angle * 5 + seed) + .03 * Math.sin(angle * 11 - seed * .3);
        double r = Math.max(.02, radius * (1 + ruffle) + (v * 2 - 1) * width);
        return c.add(rr.scale(Math.cos(angle) * r)).add(uu.scale(Math.sin(angle) * r)).subtract(n.scale(curl * (v * 2 - 1) * (v * 2 - 1)));
    }

    // ---- particles -------------------------------------------------------------------------------

    /** Where the line runs low over the ground, the pressure tears up a little of it. */
    private void kickGround(Level level) {
        RandomSource r = level.getRandom();
        int samples = Math.min(24, (int) (length / 1.5f));
        for (int i = 1; i <= samples; i++) {
            Vec3 at = from.add(axis.scale(i * 1.5));
            for (int depth = 0; depth <= 2; depth++) {
                BlockPos ground = BlockPos.containing(at).below(depth);
                BlockState state = level.getBlockState(ground);
                var shape = state.getCollisionShape(level, ground);
                if (shape.isEmpty()) continue;
                double top = ground.getY() + shape.max(Direction.Axis.Y);
                if (at.y - top > 1.6 || at.y < top) break;
                for (int k = 0; k < 2; k++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        at.x + (r.nextDouble() - .5) * .4, top + .05, at.z + (r.nextDouble() - .5) * .4,
                        axis.x * .25 + (r.nextDouble() - .5) * .2, .15 + r.nextDouble() * .2, axis.z * .25 + (r.nextDouble() - .5) * .2);
                break;
            }
        }
    }

    /** Chips of the struck block thrown back toward the caster. */
    private void debris(Level level, Vec3 at) {
        BlockState state = level.getBlockState(BlockPos.containing(to.add(axis.scale(.05))));
        if (state.isAir()) return;
        RandomSource r = level.getRandom();
        for (int i = 0; i < 14; i++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z,
                -axis.x * .3 + (r.nextDouble() - .5) * .5, .12 + r.nextDouble() * .3, -axis.z * .3 + (r.nextDouble() - .5) * .5);
    }

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** The landing: cel puffs of compressed air and white splinters, both thrown back toward the caster. */
    private static FX impact() {
        FX fx = new FX();
        fx.getFxData().objects().add(puffs());
        fx.getFxData().objects().add(splinters());
        return fx;
    }

    private static ParticleEmitter puffs() {
        ParticleEmitter e = oneShot("smoke", false, 10, range(.7f, 1.2f), range(14, 22), cone(.25f, 75));
        ParticleConfig c = e.config;
        c.setStartColor(NumberFunction.color(0xE0EEF0F2));
        c.setStartSpeed(range(2.5f, 5));
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.uvAnimation.setEnable(true);
        c.uvAnimation.setTiles(new Vector2i(4, 4));
        c.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 15.99f, "age", "frame", new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
        Curve grow = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .4f, .3f, .85f, .6f, 1, 1, 1));
        c.sizeOverLifetime.setSize(new NumberFunction3(grow, grow, grow));
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF)));
        slowDown(e, .1f);
        return e;
    }

    private static ParticleEmitter splinters() {
        ParticleEmitter e = oneShot("glow", true, 18, range(.06f, .1f), range(5, 9), cone(.15f, 70));
        ParticleConfig c = e.config;
        c.setStartColor(NumberFunction.color(0xFFFFFFF6));
        c.setStartSpeed(range(10, 18));
        c.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        c.renderer.setLengthScale(6);
        c.renderer.setVelocityScale(.04f);
        c.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFFFFF, 0x00FFFFFF)));
        slowDown(e, .15f);
        return e;
    }

    private static ParticleEmitter oneShot(String texture, boolean additive, int count, RandomConstant size, RandomConstant life, Cone shape) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "physical_ray/" + texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(32);
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartLifetime(life);
        c.shape.setShape(shape);
        c.colorOverLifetime.setEnable(true);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        c.emission.getBursts().add(b);
        return e;
    }

    private static Cone cone(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    /** Full speed at birth, {@code end} of it at death. */
    private static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .25f, .4f, .6f, end, 1, end)));
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** A stable pseudo-random number in [0, 1) for (seed, i, salt). */
    private static float hash(int seed, int i, int salt) {
        int h = seed * 0x9E3779B1 + i * 0x85EBCA6B + salt * 0xC2B2AE35;
        h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12; h *= 0x297A2D39; h ^= h >>> 15;
        return (h >>> 8) / (float) (1 << 24);
    }

    private static int alpha(float a) {
        return Math.max(0, Math.min(255, (int) (a * 255))) << 24 | 0xFFFFFF;
    }

    private static Vec3 perpendicular(Vec3 axis, Vec3 view) {
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(1, 0, 0));
        return side.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : side.normalize();
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType material(String name, String texture, String define, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/physical_ray_" + name)).withFragmentShader(id("core/physical_ray_fx"))
                .withShaderDefine(define).withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (name.equals("air")) pipeline.withSampler("NoiseTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/physical_ray/" + texture + ".png"));
        if (name.equals("air")) setup.withTexture("NoiseTex", id("textures/vfx/physical_ray/wind.png"));
        return RenderType.create("logos_physical_ray_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
