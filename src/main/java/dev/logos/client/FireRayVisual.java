package dev.logos.client;

import static dev.logos.client.LogosFx.range;
import static dev.logos.client.ProjectileParticles.configureTexture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;

/**
 * A fire {@code ray}: the burst half of Piloto's PS_UltimateBeam_Fire (the charge is the wand's). A shot
 * races down the line in a tick and a half and the line erupts: a churning flame band around a white-gold
 * core, flame tongues and streaks rushing toward the target, a flowy ring and a forward flame spray at
 * the muzzle, and on a block or creature a fire splash thrown back toward the caster. The flame is gone
 * within half a second; it leaves a band of dark smoke on the line that rises, widens, thins and is
 * eaten away over about six and a half seconds, with a few embers drifting up out of it.
 */
final class FireRayVisual implements RayVisual {
    /** Ticks until the last of the smoke is gone; {@link #tick} drops the ray then. */
    static final int LIFE = 134;
    /** The shot takes this long (ticks) to reach the end of the line. */
    private static final float SHOT = 1.5f;
    /** The smoke band starts thickening this many ticks in, as the flame burns down. */
    private static final float SMOKE_AFTER = 4;

    private static final RenderType FLAME = beamMaterial(false);
    private static final RenderType CORE = beamMaterial(true);
    private static final RenderType SMOKE = smokeMaterial();
    private static final RenderType GLOW = glowMaterial();
    private static final FX MUZZLE = muzzle(false), MUZZLE_CLOSE = muzzle(true), IMPACT = impact();
    /** Body effects by rounded length (and whether seen down the line): the emission volume and counts follow the line. */
    private static final Map<Integer, FX> BODIES = new HashMap<>();

    private final List<FXRuntime> playing = new ArrayList<>();
    private Vec3 from = Vec3.ZERO, to = Vec3.ZERO, dir = new Vec3(0, 0, 1), drift = new Vec3(1, 0, 0);
    private double length;
    private Vfx.RayEnd end = Vfx.RayEnd.OPEN;
    /** How lit the line is (0 dark – 1 full); the smoke is lit by the world, not glowing. */
    private float light = 1;
    private float seed;
    private int age;

    @Override
    public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) {
        this.from = from;
        this.to = to;
        this.end = end;
        length = from.distanceTo(to);
        if (length > 1e-4) dir = to.subtract(from).scale(1 / length);
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        drift = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
        seed = level.getRandom().nextFloat() * 100;
        Vec3 mid = from.add(to).scale(.5);
        light = Math.max(level.getMaxLocalRawBrightness(BlockPos.containing(mid)),
                level.getMaxLocalRawBrightness(BlockPos.containing(from))) / 15f;
        if (length < .3) return;

        // In first person the muzzle sits just under the camera: only a small flame spray, pushed out ahead,
        // and nothing along the first blocks of the line, so neither flash nor smoke covers the view.
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        boolean close = !camera.isDetached() && camera.position().distanceTo(from) < 1.6;
        if (close) playing.add(emit(MUZZLE_CLOSE, level, from.add(dir.scale(Math.min(2.5, length * .5))), dir, 1));
        else playing.add(emit(MUZZLE, level, from.add(dir.scale(.12)), dir, 1));
        double trim = Math.min(close ? 2.5 : .6, length * .5);
        Vec3 bodyFrom = from.add(dir.scale(trim));
        int rounded = Mth.clamp((int) Math.round(length - trim), 1, 64);
        FX body = BODIES.computeIfAbsent(rounded * 2 + (close ? 1 : 0), key -> body(key / 2, close));
        playing.add(emit(body, level, bodyFrom.add(to).scale(.5), dir, 1));
        if (end == Vfx.RayEnd.BLOCK || end == Vfx.RayEnd.CREATURE)
            playing.add(emit(IMPACT, level, to.subtract(dir.scale(.12)), dir.scale(-1), 1));
    }

    @Override
    public boolean tick(Level level) {
        return ++age < LIFE;
    }

    @Override
    public void end() {
        playing.forEach(fx -> fx.destroy(true));
        playing.clear();
    }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        if (length < .3) return;
        float t = age + partialTick;
        float reveal = Math.min(1, t / SHOT);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        Vec3 eye = camera.pos;
        if (t > SMOKE_AFTER) out.submitCustomGeometry(pose, SMOKE, (p, buf) -> smoke(p, buf, eye, t - SMOKE_AFTER));
        float flame = envelope(t, .7f, 3.5f, 9);
        if (flame > 0) out.submitCustomGeometry(pose, FLAME, (p, buf) -> ribbon(p, buf, eye, reveal, .34f * flame, 1));
        float core = envelope(t, .4f, 4, 10);
        if (core > 0) out.submitCustomGeometry(pose, CORE, (p, buf) -> ribbon(p, buf, eye, reveal, .1f * (.6f + .4f * core), core));
        if (t < SHOT + 2.5f) {
            Vec3 head = from.add(dir.scale(length * reveal));
            float a = t < SHOT ? 1 : 1 - (t - SHOT) / 2.5f;
            float size = end == Vfx.RayEnd.OPEN || end == Vfx.RayEnd.LINK ? .45f : .7f + .5f * (1 - a);
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> sprite(p, buf, camera, head, size, a * nearFade(eye, head, .8, 2.5)));
        }
        pose.popPose();
    }

    /** 0 → 1 by {@code rise}, held until {@code hold}, back to 0 at {@code gone} (ticks). */
    private static float envelope(float t, float rise, float hold, float gone) {
        if (t >= gone) return 0;
        if (t < rise) return t / rise;
        if (t < hold) return 1;
        return 1 - smooth((t - hold) / (gone - hold));
    }

    /** How much of something at {@code p} to show this close to the camera: nothing within {@code near}. */
    private static float nearFade(Vec3 eye, Vec3 p, double near, double far) {
        return (float) Mth.clamp((eye.distanceTo(p) - near) / (far - near), 0, 1);
    }

    /**
     * Seen end-on (down the line, as the caster sees it) the far part of the strip piles up on itself into
     * one bright spot over the target; thin it out there.
     */
    private float endOn(Vec3 eye, Vec3 p) {
        Vec3 view = p.subtract(eye);
        double d = view.length();
        if (d < 1e-4) return 1;
        double along = Math.abs(view.dot(dir)) / d;
        double k = Mth.clamp((along - .9) / .1, 0, 1);
        return (float) (1 - .7 * k * k);
    }

    /** How wide the line is {@code s} blocks from the muzzle: it opens out of the wand, and an open ray burns out at its tip. */
    private float taper(double s) {
        double open = Math.min(1, s / 1.2);
        double tip = length - s;
        double close = switch (end) {
            case OPEN -> Math.min(1, tip / Math.min(2.5, length * .4));
            case LINK -> Math.min(1, tip);
            default -> 1;
        };
        return (float) ((.35 + .65 * open) * Math.max(0, close));
    }

    /** A camera-facing strip along the revealed part of the line; U runs in blocks / 4. */
    private void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, float reveal, float halfWidth, float alpha) {
        double shown = length * reveal;
        int n = Mth.clamp((int) Math.ceil(shown * 2), 4, 128);
        Vec3 prevA = null, prevB = null;
        float prevU = 0, prevAlpha = 0;
        for (int i = 0; i <= n; i++) {
            double s = shown * i / n;
            Vec3 p = from.add(dir.scale(s));
            Vec3 side = side(p, eye).scale(halfWidth * taper(s));
            float a = alpha * nearFade(eye, p, .6, 2.6) * endOn(eye, p);
            // The head of a shot still racing down the line is its brightest part.
            if (reveal < 1) a *= (float) (.55 + .45 * (s / Math.max(shown, 1e-3)));
            Vec3 pa = p.add(side), pb = p.subtract(side);
            float u = (float) (s / 4);
            if (prevA != null) {
                vertex(pose, buf, prevA, prevU, 0, 1, 1, 1, prevAlpha);
                vertex(pose, buf, prevB, prevU, 1, 1, 1, 1, prevAlpha);
                vertex(pose, buf, pb, u, 1, 1, 1, 1, a);
                vertex(pose, buf, pa, u, 0, 1, 1, 1, a);
            }
            prevA = pa;
            prevB = pb;
            prevU = u;
            prevAlpha = a;
        }
    }

    /**
     * The smoke band {@code ts} ticks after the flame: warm and dense at first, cooling to grey as it
     * rises about a block, widens, wanders sideways and is eaten away.
     */
    private void smoke(PoseStack.Pose pose, VertexConsumer buf, Vec3 eye, float ts) {
        float span = LIFE - SMOKE_AFTER;
        float left = smooth(Mth.clamp(ts / 6, 0, 1)) * (1 - smooth(Mth.clamp((ts - 6) / (span - 8), 0, 1)));
        if (left <= 0) return;
        float halfWidth = .28f + .7f * (1 - (float) Math.exp(-ts / 32));
        double rise = .9 * (1 - Math.exp(-ts / 60));
        float warm = smooth(Mth.clamp(ts / 6, 0, 1));
        float grey = smooth(Mth.clamp((ts - 4) / 64, 0, 1));
        float lit = .25f + .75f * light;
        float r = lerp(grey, lerp(warm, .66f, .26f), .58f) * lit;
        float g = lerp(grey, lerp(warm, .30f, .24f), .57f) * lit;
        float b = lerp(grey, lerp(warm, .12f, .23f), .56f) * lit;
        int n = Mth.clamp((int) Math.ceil(length * 1.5), 4, 96);
        Vec3 prevA = null, prevB = null;
        float prevU = 0, prevAlpha = 0;
        for (int i = 0; i <= n; i++) {
            double s = length * i / n;
            double wave = Math.sin(s * .55 + seed + ts * .032) * .5 + Math.sin(s * 1.3 - seed * .7 + ts * .019) * .25;
            Vec3 p = from.add(dir.scale(s)).add(0, rise * (.75 + .25 * wave), 0).add(drift.scale(wave * .35 * ts / LIFE));
            double ends = Math.min(1, Math.min(s / .8, end == Vfx.RayEnd.OPEN ? (length - s) / 2 : 1));
            Vec3 side = side(p, eye).scale(halfWidth * (.4 + .6 * ends));
            float a = (float) (left * (.3 + .7 * ends)) * nearFade(eye, p, 2, 6);
            Vec3 pa = p.add(side), pb = p.subtract(side);
            float u = (float) (s / 3) + seed;
            if (prevA != null) {
                vertex(pose, buf, prevA, prevU, 0, r, g, b, prevAlpha);
                vertex(pose, buf, prevB, prevU, 1, r, g, b, prevAlpha);
                vertex(pose, buf, pb, u, 1, r, g, b, a);
                vertex(pose, buf, pa, u, 0, r, g, b, a);
            }
            prevA = pa;
            prevB = pb;
            prevU = u;
            prevAlpha = a;
        }
    }

    /** Unit vector across the line at {@code p}, square to both the line and the view. */
    private Vec3 side(Vec3 p, Vec3 eye) {
        Vec3 s = dir.cross(eye.subtract(p));
        if (s.lengthSqr() < 1e-8) s = dir.cross(new Vec3(0, 1, 0));
        if (s.lengthSqr() < 1e-8) s = new Vec3(1, 0, 0);
        return s.normalize();
    }

    private static void sprite(PoseStack.Pose pose, VertexConsumer buf, CameraRenderState camera, Vec3 at, float r, float alpha) {
        if (alpha <= 0) return;
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(r);
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(r);
        vertex(pose, buf, at.subtract(right).add(up), 0, 0, 1, .55f, .14f, alpha);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1, 1, .55f, .14f, alpha);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1, 1, .55f, .14f, alpha);
        vertex(pose, buf, at.add(right).add(up), 1, 0, 1, .55f, .14f, alpha);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, float r, float g, float b, float a) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(Mth.clamp(r, 0, 1), Mth.clamp(g, 0, 1), Mth.clamp(b, 0, 1), Mth.clamp(a, 0, 1))
                .setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static float smooth(float k) { return k * k * (3 - 2 * k); }

    private static float lerp(float k, float a, float b) { return a + (b - a) * k; }

    // ---- Photon layers --------------------------------------------------------------------------

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    /** Plays {@code fx} at {@code at} with its local +Y turned onto {@code axis}. */
    private static FXRuntime emit(FX fx, Level level, Vec3 at, Vec3 axis, float scale) {
        FXRuntime runtime = fx.createRuntime();
        runtime.getRoot().updatePos(new Vector3f((float) at.x, (float) at.y, (float) at.z));
        runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) axis.x, (float) axis.y, (float) axis.z)));
        runtime.getRoot().updateScale(new Vector3f(scale));
        runtime.emit(new Executor(level));
        return runtime;
    }

    /**
     * UB_Ring_Flowy, AlphaFuzzball_NoDark and MA_UB_DecalFire: the muzzle's ring, glow and forward flame
     * spray. Seen from right behind the wand ({@code close}) only a small spray remains, flying away.
     */
    private static FX muzzle(boolean close) {
        if (close) {
            ParticleEmitter spray = layer("flames", true, 5, range(.14f, .22f), range(4, 6), cone(.04f, 12), 0xFFFFFFFF);
            spray.config.setStartSpeed(range(6, 9));
            flipbook(spray);
            fade(spray, 0x00FFD070, 0xA0FFB040, 0x70FF7A18, 0x00E03A08);
            return fx(spray);
        }
        ParticleEmitter ring = layer("ring", true, 1, range(.6f, .6f), range(5, 5), sphere(0), 0x80FFAE55);
        envelope(ring, new Curve(0, 1, 0, 2, "age", "size", new ECBCurves(0, .25f, .4f, .8f, .7f, .95f, 1, 1)));
        fade(ring, 0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF);
        ParticleEmitter glow = layer("glow", true, 1, range(.6f, .7f), range(6, 6), sphere(0), 0x90FF6A10);
        envelope(glow, new Curve(0, 1, 0, 1.4f, "age", "size", new ECBCurves(0, .5f, .3f, 1, .6f, .8f, 1, .1f)));
        fade(glow, 0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF);
        ParticleEmitter spray = layer("flames", true, 7, range(.32f, .5f), range(5, 8), cone(.08f, 18), 0xFFFFFFFF);
        spray.config.setStartSpeed(range(4, 7));
        flipbook(spray);
        fade(spray, 0x00FFD070, 0xF0FFB040, 0xC0FF7A18, 0x00E03A08);
        slowDown(spray, .25f);
        return fx(ring, glow, spray);
    }

    /**
     * Along the line, scaled to its {@code length}: Fire_Disolvy_Red_Soft's flame tongues and
     * Flare_Ultrawide_Intense's streaks rushing toward the target, MFX_Glow_ConeAttack02's embers that
     * outlive the flame, and smoke puffs that give the band left behind its volume. A box emits along
     * its local +Y, which {@link #emit} turns toward the target. Seen down the line ({@code endOn}, the
     * caster in first person) the far flames all land on the crosshair, so there are fewer and fainter.
     */
    private static FX body(int length, boolean endOn) {
        ParticleEmitter flames = layer("flames", true, Math.min(56, Math.round(length * (endOn ? .5f : 1.1f)) + 1),
                range(.55f, .9f), range(5, 9), new Box(), 0xFFFFFFFF);
        flames.config.shape.setScale(new NumberFunction3(.22f, length, .22f));
        flames.config.setStartDelay(range(0, SHOT));
        flames.config.setStartSpeed(range(1.5f, 4));
        flipbook(flames);
        envelope(flames, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .55f, .3f, .95f, .7f, 1, 1, .8f)));
        if (endOn) fade(flames, 0x00FFD27A, 0x70FFA338, 0x50FF6A14, 0x00C82A06);
        else fade(flames, 0x00FFD27A, 0xE0FFA338, 0xB0FF6A14, 0x00C82A06);

        ParticleEmitter streaks = layer("streak", true, Math.min(40, Math.round(length * .7f) + 1),
                range(.5f, .8f), range(3, 5), new Box(), 0xE0FFB24A);
        streaks.config.shape.setScale(new NumberFunction3(.22f, length, .22f));
        streaks.config.setStartDelay(range(0, SHOT + 1));
        streaks.config.setStartSpeed(range(30, 50));
        streaks.config.setStartSize(new NumberFunction3(range(.5f, .8f), range(.06f, .1f), NumberFunction.constant(1)));
        streaks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        streaks.config.renderer.setLengthScale(3);
        streaks.config.renderer.setVelocityScale(0);
        fade(streaks, 0xFFFFFFFF, 0xC0FFFFFF, 0x00FFFFFF);

        ParticleEmitter embers = layer("dot", true, Math.min(36, Math.round(length * .6f) + 1),
                range(.05f, .09f), range(18, 34), new Box(), 0xFFFFB347);
        embers.config.shape.setScale(new NumberFunction3(.4f, length, .4f));
        embers.config.setStartDelay(range(1, 4));
        embers.config.setStartSpeed(range(.3f, 1.2f));
        embers.config.physics.setEnable(true);
        embers.config.physics.setGravity(NumberFunction.constant(-.004f));
        fade(embers, 0xFFFFF0C0, 0xFFFFB347, 0xA0FF6A1C, 0x00FF3A0A);

        ParticleEmitter puffs = layer("puff", false, Math.min(64, Math.round(length * 1.2f) + 1),
                range(.45f, .7f), range(60, 100), new Box(), 0xFF5C5652);
        puffs.config.shape.setScale(new NumberFunction3(.3f, length, .3f));
        puffs.config.lights.setEnable(false);
        puffs.config.setStartDelay(range(4, 10));
        puffs.config.setStartSpeed(range(.05f, .3f));
        puffs.config.physics.setEnable(true);
        puffs.config.physics.setGravity(NumberFunction.constant(-.0009f));
        sheet(puffs);
        puffs.config.rotationOverLifetime.setEnable(true);
        puffs.config.rotationOverLifetime.setRoll(range(-1.5f, 1.5f));
        envelope(puffs, new Curve(0, 1, 0, 2.4f, "age", "size", new ECBCurves(0, .42f, .3f, .7f, .7f, .92f, 1, 1)));
        fade(puffs, 0x00FFFFFF, 0x48FFFFFF, 0x38D8D8D8, 0x18C0C0C0, 0x00A0A0A0);
        return fx(flames, streaks, embers, puffs);
    }

    /** A fire splash where the ray strikes, thrown back toward the caster so the struck body does not hide it. */
    private static FX impact() {
        ParticleEmitter flash = layer("cross", true, 1, range(1.3f, 1.5f), range(5, 5), sphere(0), 0xE0FFD58A);
        envelope(flash, new Curve(0, 1, 0, 1.3f, "age", "size", new ECBCurves(0, .6f, .25f, 1, .6f, .6f, 1, .2f)));
        fade(flash, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF);
        ParticleEmitter glow = layer("glow", true, 1, range(1.8f, 2.1f), range(8, 8), sphere(0), 0xB0FF6A10);
        envelope(glow, new Curve(0, 1, 0, 1.2f, "age", "size", new ECBCurves(0, .55f, .25f, 1, .6f, .8f, 1, .15f)));
        fade(glow, 0xFFFFFFFF, 0xA0FFFFFF, 0x00FFFFFF);
        ParticleEmitter flames = layer("flames", true, 11, range(.5f, .8f), range(7, 11), cone(.15f, 60), 0xFFFFFFFF);
        flames.config.setStartSpeed(range(2.5f, 4.5f));
        flipbook(flames);
        envelope(flames, new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .6f, .3f, 1, .7f, 1, 1, .7f)));
        fade(flames, 0x00FFD27A, 0xF0FFA338, 0xC0FF6A14, 0x00C82A06);
        slowDown(flames, .15f);
        ParticleEmitter sparks = layer("dot", true, 16, range(.05f, .09f), range(8, 14), cone(.12f, 75), 0xFFFFC064);
        sparks.config.setStartSpeed(range(4, 8));
        sparks.config.physics.setEnable(true);
        sparks.config.physics.setGravity(NumberFunction.constant(.01f));
        sparks.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        sparks.config.renderer.setLengthScale(3);
        sparks.config.renderer.setVelocityScale(.05f);
        fade(sparks, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FF6A1C);
        slowDown(sparks, .3f);
        ParticleEmitter smoke = layer("puff", false, 3, range(.4f, .6f), range(48, 74), cone(.2f, 50), 0xFF5C5652);
        smoke.config.lights.setEnable(false);
        smoke.config.setStartDelay(range(2, 5));
        smoke.config.setStartSpeed(range(.3f, .8f));
        smoke.config.physics.setEnable(true);
        smoke.config.physics.setGravity(NumberFunction.constant(-.0012f));
        sheet(smoke);
        envelope(smoke, new Curve(0, 1, 0, 2.2f, "age", "size", new ECBCurves(0, .45f, .3f, .75f, .7f, .95f, 1, 1)));
        fade(smoke, 0x00FFFFFF, 0x70FFFFFF, 0x40C8C8C8, 0x00A0A0A0);
        slowDown(smoke, .2f);
        return fx(flash, glow, flames, sparks, smoke);
    }

    private static FX fx(ParticleEmitter... layers) {
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(layers));
        return fx;
    }

    /** One non-looping burst of {@code count} particles of {@code fire_ray/<texture>}, full-bright. */
    private static ParticleEmitter layer(String texture, boolean additive, int count, RandomConstant size, RandomConstant life,
            IShape shape, int color) {
        ParticleEmitter e = new ParticleEmitter();
        ParticleConfig c = e.config;
        configureTexture(e, "fire_ray/" + texture, additive);
        c.lights.setEnable(true);
        c.setSimulationSpace(ParticleConfig.Space.World);
        c.setDuration(20);
        c.setLooping(false);
        c.setMaxParticles(Math.max(16, count));
        c.setStartColor(NumberFunction.color(color));
        c.setStartSize(new NumberFunction3(size, size, size));
        c.setStartSpeed(NumberFunction.constant(0));
        c.setStartLifetime(life);
        c.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        c.shape.setShape(shape);
        c.emission.setEmissionRate(NumberFunction.constant(0));
        EmissionSetting.Burst b = new EmissionSetting.Burst();
        b.time = 0;
        b.setCount(NumberFunction.constant(count));
        c.emission.getBursts().add(b);
        return e;
    }

    private static Sphere sphere(float radius) {
        Sphere s = new Sphere();
        s.setRadius(radius);
        return s;
    }

    /** A cone opening along local +Y (the root's axis). */
    private static Cone cone(float radius, float angle) {
        Cone c = new Cone();
        c.setRadius(radius);
        c.setAngle(angle);
        return c;
    }

    /** Plays the 4×4 dissolving flame sheet once over each particle's life. */
    private static void flipbook(ParticleEmitter e) {
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(4, 4));
        e.config.uvAnimation.setStartFrame(NumberFunction.constant(0));
        e.config.uvAnimation.setFrameOverTime(new Curve(0, 1, 0, 15.99f, "age", "frame",
                new ECBCurves(0, 0, .33f, .33f, .67f, .67f, 1, 1)));
    }

    /** One random fixed frame of a 2×2 sheet. */
    private static void sheet(ParticleEmitter e) {
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
    }

    private static void envelope(ParticleEmitter e, Curve size) {
        e.config.sizeOverLifetime.setEnable(true);
        e.config.sizeOverLifetime.setSize(new NumberFunction3(size, size, size));
    }

    private static void fade(ParticleEmitter e, int... keys) {
        e.config.colorOverLifetime.setEnable(true);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(keys)));
    }

    /** Unity's speed modifier: full speed at birth, {@code end} of it at death. */
    private static void slowDown(ParticleEmitter e, float end) {
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setSpeedModifier(new Curve(0, 1, 0, 1, "age", "speed", new ECBCurves(0, 1, .3f, .45f, .6f, end, 1, end)));
    }

    // ---- custom geometry materials --------------------------------------------------------------

    private static RenderType beamMaterial(boolean core) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/fire_ray_" + (core ? "core" : "flame")))
                .withFragmentShader(id("core/fire_ray_beam"))
                .withSampler("NoiseTex").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING));
        if (core) pipeline.withShaderDefine("FIRE_RAY_CORE");
        return RenderType.create("logos_fire_ray_" + (core ? "core" : "flame"), RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", id("textures/vfx/fire_ray/fire_trail.png"))
                .withTexture("NoiseTex", id("textures/vfx/fire_ray/noise.png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType smokeMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/fire_ray_smoke")).withFragmentShader(id("core/fire_ray_smoke"))
                .withSampler("NoiseTex").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_fire_ray_smoke", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/fire_ray/smoke_trail.png"))
                .withTexture("NoiseTex", id("textures/vfx/fire_ray/noise.png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType glowMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/fire_ray_glow")).withFragmentShader(id("core/fire_ray_glow"))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
        return RenderType.create("logos_fire_ray_glow", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/fire_ray/glow.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
