package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;
import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;

/** A needle-bright anime beam: one launch flash and pressure front, then restrained sonic wakes and violent contact. */
final class PhysicalBeamVisual {
    private static final RenderType CORE = material("core", "fire_beam/laser", "PHYSICAL_CORE", true);
    private static final RenderType GLOW = material("glow", "fire_beam/laser", "PHYSICAL_GLOW", true);
    private static final RenderType FLASH = material("flash", "physical_impact/cross", "PHYSICAL_FLASH", true);
    private static final RenderType AIR = material("air", "void_beam/aura", "PHYSICAL_AIR", false);
    private static final FX LAUNCH_DUST = smoke(false, false), WAKE = smoke(true, false), CONTACT_DUST = smoke(true, true);
    private record Wave(Vec3 origin, Vec3 axis, double born, float length, boolean launch) {}
    private record Puff(FXRuntime runtime, double born) {}
    private final List<Wave> waves = new ArrayList<>();
    private final List<Puff> puffs = new ArrayList<>();
    private final boolean defaultMotes;
    private FXRuntime wake, contact;
    private double started = Double.NaN, nextWave, impactBorn, nextImpactSound;
    private Vec3 lastFrom, lastImpact;
    private float wakeLength = -1;
    private long lastDustTick = Long.MIN_VALUE;
    private long lastWakeDustTick = Long.MIN_VALUE;

    PhysicalBeamVisual(boolean defaultMotes) { this.defaultMotes = defaultMotes; }

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < .0025) return;
        Vec3 axis = delta.normalize();
        float length = (float)delta.length();
        float scale = Math.max(.45f, Math.min(1.8f, (float)Math.sqrt(radius / .5f)));
        double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (Double.isNaN(started)) {
            started = now; nextWave = now + 9;
            waves.add(new Wave(from, axis, now, length, true));
            if (defaultMotes) puffs.add(new Puff(emit(LAUNCH_DUST, level, from, axis, scale), now));
            level.playLocalSound(from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, .6f, 1.6f, false);
        }
        if (now >= nextWave) {
            waves.add(new Wave(from, axis, now, length, false));
            nextWave = now + 9;
            level.playLocalSound(from.x, from.y, from.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .15f, .65f, false);
        }
        waves.removeIf(w -> now - w.born > 14 || (now - w.born) * (w.launch ? .85 : 1.1) > w.length);
        puffs.removeIf(p -> {
            if (now - p.born <= 40) return false;
            p.runtime.destroy(false); return true;
        });
        if (defaultMotes) updateWake(level, from, to, axis, scale, length);
        if (defaultMotes && level.getGameTime() != lastWakeDustTick && level.getGameTime() % 5 == 0) {
            lastWakeDustTick = level.getGameTime();
            // Airflow lifts material only where there is actually a surface close below the beam.
            for (int i = 1; i <= 2; i++) {
                Vec3 at = from.lerp(to, i / 3.0);
                for (int depth = 1; depth <= 3; depth++) {
                    BlockPos ground = BlockPos.containing(at).below(depth);
                    var state = level.getBlockState(ground);
                    var shape = state.getCollisionShape(level, ground);
                    if (shape.isEmpty()) continue;
                    for (int k = 0; k < 2; k++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                            at.x, ground.getY() + shape.max(Direction.Axis.Y) + .03, at.z,
                            axis.x * .16, .1 + level.getRandom().nextDouble() * .12, axis.z * .16);
                    break;
                }
            }
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to.add(axis.scale(.3)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, Minecraft.getInstance().player));
        boolean impacting = hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(to) < .65;
        Vec3 impactAt = impacting ? hit.getLocation().subtract(axis.scale(.07)) : to;
        if (impacting) {
            boolean fresh = lastImpact == null || lastImpact.distanceToSqr(impactAt) > 2.25;
            if (fresh) impactBorn = now;
            lastImpact = impactAt;
            if (defaultMotes) {
                Vec3 dustAt = impactAt.subtract(axis.scale(.2 * scale));
                if (contact == null) contact = emit(CONTACT_DUST, level, dustAt, axis.scale(-1), scale);
                locate(contact, dustAt, axis.scale(-1), scale);
            }
            if (now >= nextImpactSound) {
                level.playLocalSound(impactAt.x, impactAt.y, impactAt.z, SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.BLOCKS, .35f, .75f, false);
                nextImpactSound = now + 24;
            }
            if (defaultMotes && level.getGameTime() != lastDustTick && level.getGameTime() % 5 == 0) {
                lastDustTick = level.getGameTime();
                var state = level.getBlockState(hit.getBlockPos());
                for (int i = 0; i < 3; i++) {
                    var r = level.getRandom();
                    level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), impactAt.x, impactAt.y, impactAt.z,
                            (r.nextDouble() - .5) * .2 - axis.x * .12, .08 + r.nextDouble() * .12,
                            (r.nextDouble() - .5) * .2 - axis.z * .12);
                }
            }
        } else {
            lastImpact = null;
            if (contact != null) { contact.destroy(false); contact = null; }
        }
        lastFrom = from;
        double age = now - started, hitAge = now - impactBorn;
        boolean largeFlash = age >= 10 && age < 14;
        List<Wave> visibleWaves = List.copyOf(waves);
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(stack, CORE, (pose, buf) -> needle(pose, buf, from, to, .025f * scale));
        out.submitCustomGeometry(stack, GLOW, (pose, buf) -> band(pose, buf, from, to, camera.pos, .105f * scale));
        out.submitCustomGeometry(stack, FLASH, (pose, buf) -> {
            float muzzle = age < 10 ? .69f : .24f;
            flare(pose, buf, from, camera, muzzle * scale, .12f, 0xE8FFFFF3);
            if (largeFlash) flare(pose, buf, from, camera, 3.2f * scale, .6f, 0xF8FFFFF5);
            if (impacting) {
                float flash = (float)Math.max(0, 1 - hitAge / 5);
                float pulse = (float)(.5 + .5 * Math.sin(now * 1.9));
                flare(pose, buf, impactAt, camera, (1.05f + flash * 1.5f + pulse * .18f) * scale, -.28f, 0xDCFFFFEB);
                impactCuts(pose, buf, impactAt, camera.pos, axis, scale, now, flash);
            }
        });
        out.submitCustomGeometry(stack, AIR, (pose, buf) -> {
            for (Wave w : visibleWaves) {
                float t = (float)(now - w.born), distance = t * (w.launch ? .85f : 1.1f);
                float fade = Math.max(0, 1 - t / 14) * Math.min(1, Math.max(0, w.length - distance) / .6f);
                float phase = Math.min(1, t / 14);
                float r = (w.launch ? .3f + 1.7f * phase * phase : .22f + .95f * phase * phase) * scale;
                Vec3 at = w.origin.add(w.axis.scale(distance));
                pressureWave(pose, buf, at, w.axis, r, (w.launch ? .2f + phase * .24f : .12f + phase * .12f) * scale,
                        fade * (w.launch ? .85f : .48f), now);
                if (w.launch) pressureWave(pose, buf, at.subtract(w.axis.scale(.22 * scale)), w.axis,
                        r * .78f, .16f * scale, fade * .35f, now + 7);
            }
            if (impacting) {
                Vec3 normal = axis.scale(-1);
                float first = (float)(hitAge / 12);
                if (first < 1) pressureWave(pose, buf, impactAt, normal, (.25f + first * first * 3.4f) * scale,
                        (.18f + first * .35f) * scale, (1 - first) * .9f, now);
                float phase = (float)((hitAge % 12) / 12);
                pressureWave(pose, buf, impactAt.subtract(axis.scale(.02)), normal, (.2f + phase * phase * 2.2f) * scale,
                        (.14f + phase * .25f) * scale, (1 - phase) * .6f, now + 11);
            }
        });
        stack.popPose();
    }

    void end() {
        if (wake != null) { wake.destroy(false); wake = null; }
        if (contact != null) { contact.destroy(false); contact = null; }
        for (Puff puff : puffs) puff.runtime.destroy(false);
        puffs.clear(); waves.clear();
    }

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    private static FXRuntime emit(FX fx, Level level, Vec3 at, Vec3 axis, float scale) {
        FXRuntime runtime = fx.createRuntime();
        for (var object : runtime.objects.values()) if (object instanceof ParticleEmitter e)
            e.config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
        locate(runtime, at, axis, scale);
        runtime.emit(new Executor(level));
        return runtime;
    }

    private static void locate(FXRuntime runtime, Vec3 at, Vec3 axis, float scale) {
        runtime.getRoot().updatePos(at.toVector3f());
        runtime.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.toVector3f()));
        runtime.getRoot().updateScale(new Vector3f(scale));
    }

    private void updateWake(Level level, Vec3 from, Vec3 to, Vec3 axis, float scale, float length) {
        if (wake == null) wake = emit(WAKE, level, from.add(to).scale(.5), axis, scale);
        locate(wake, from.add(to).scale(.5), axis, scale);
        if (Math.abs(wakeLength - length) > .02f) {
            for (var object : wake.objects.values()) if (object instanceof ParticleEmitter e) {
                e.runtime().shape.scale.set(new NumberFunction3(.12f, length / scale, .12f));
                e.runtime().emission.emissionRate.set(NumberFunction.constant(Math.min(.3f, length * .025f)));
            }
            wakeLength = length;
        }
    }

    private static FX smoke(boolean loop, boolean impact) {
        FX fx = new FX();
        ParticleEmitter e = layer(Element.PHYSICAL, loop, loop ? (impact ? .55f : .12f) : 0, loop ? 0 : 5,
                size(impact ? .6f : .28f, impact ? 1 : .55f), range(impact ? .8f : .05f, impact ? 2 : .3f),
                range(14, impact ? 30 : 22), sphere(impact ? .2f : .08f));
        ProjectileParticles.configureTexture(e, "fire_damage/smoke", false);
        e.config.setSimulationSpace(ParticleConfig.Space.World);
        e.config.lights.setEnable(true);
        e.config.setStartColor(NumberFunction.color(impact ? 0xC0D0CDC6 : 0x68D4D7DB));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.physics.setEnable(false);
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0x80FFFFFF, 0x50CCCCCC, 0x00AAAAAA)));
        Curve spread = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .25f, .25f, .65f, .6f, .9f, 1, 1));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(spread, spread, spread));
        e.config.setMaxParticles(24);
        if (loop && !impact) e.config.shape.setShape(new Box());
        if (impact) {
            Cone outward = new Cone(); outward.setAngle(70); outward.setRadius(.08f);
            e.config.shape.setShape(outward);
        }
        fx.getFxData().objects().add(e);
        return fx;
    }

    private static Vec3 perpendicular(Vec3 axis, Vec3 view) {
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        return side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
    }

    private static void needle(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, float radius) {
        Vec3 axis = to.subtract(from).normalize(), right = perpendicular(axis, new Vec3(0, 1, 0)), up = axis.cross(right);
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI / 5, b = (i + 1) * Math.PI / 5;
            Vec3 x = right.scale(Math.cos(a) * radius).add(up.scale(Math.sin(a) * radius));
            Vec3 y = right.scale(Math.cos(b) * radius).add(up.scale(Math.sin(b) * radius));
            quad(pose, buf, from.add(x), from.add(y), to.add(y), to.add(x), 0, 1, 0xFFFFFFF5);
        }
    }

    private static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius) {
        Vec3 side = perpendicular(to.subtract(from), camera.subtract(from.add(to).scale(.5))).scale(radius);
        quad(pose, buf, from.add(side), from.subtract(side), to.subtract(side), to.add(side), 0, 1, 0xA8FFF5DD);
    }

    private static void flare(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, CameraRenderState camera, float radius, float roll, int color) {
        Vector3f x = new Vector3f((float)Math.cos(roll), (float)Math.sin(roll), 0);
        Vector3f y = new Vector3f(-(float)Math.sin(roll), (float)Math.cos(roll), 0);
        Vec3 right = new Vec3(camera.orientation.transform(x)).scale(radius), up = new Vec3(camera.orientation.transform(y)).scale(radius);
        quad(pose, buf, at.subtract(right).add(up), at.subtract(right).subtract(up),
                at.add(right).subtract(up), at.add(right).add(up), 0, 1, color);
    }

    /** A broad, curled compression sheet with a ruffled silhouette rather than a smooth planar hoop. */
    private static void pressureWave(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Vec3 axis,
            float radius, float width, float alpha, double now) {
        if (alpha <= 0) return;
        Vec3 right = perpendicular(axis, new Vec3(0, 1, 0)), up = axis.cross(right);
        int color = Math.min(255, (int)(alpha * 255)) << 24 | 0xE6E8E9;
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI / 32, b = (i + 1) * Math.PI / 32;
            for (int j = 0; j < 5; j++) {
                float v0 = j / 5f, v1 = (j + 1) / 5f;
                vertex(pose, buf, wavePoint(at, axis, right, up, a, v0, radius, width, now), i / 64f, v0, color);
                vertex(pose, buf, wavePoint(at, axis, right, up, b, v0, radius, width, now), (i + 1) / 64f, v0, color);
                vertex(pose, buf, wavePoint(at, axis, right, up, b, v1, radius, width, now), (i + 1) / 64f, v1, color);
                vertex(pose, buf, wavePoint(at, axis, right, up, a, v1, radius, width, now), i / 64f, v1, color);
            }
        }
    }

    private static Vec3 wavePoint(Vec3 at, Vec3 axis, Vec3 right, Vec3 up, double angle, float v,
            float radius, float width, double now) {
        double ruffle = .08 * Math.sin(angle * 5 + now * .09) + .035 * Math.sin(angle * 11 - now * .065)
                + .018 * Math.sin(angle * 23 + now * .13);
        double localWidth = width * (.8 + .2 * Math.sin(angle * 7 + now * .1));
        double r = Math.max(.03, radius * (1 + ruffle) + (v * 2 - 1) * localWidth);
        double curl = -localWidth * (1 - v) * (1 - v) * 1.2 + localWidth * .15 * Math.sin(v * Math.PI);
        return at.add(right.scale(Math.cos(angle) * r)).add(up.scale(Math.sin(angle) * r)).add(axis.scale(curl));
    }

    private static void impactCuts(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Vec3 camera, Vec3 axis, float scale, double now, float flash) {
        Vec3 right = perpendicular(axis, new Vec3(0, 1, 0)), up = axis.cross(right);
        for (int i = 0; i < 11; i++) {
            double angle = i * 2.399963 + Math.floor(now / 3) * .17;
            Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            double length = scale * (.55 + .8 * (.5 + .5 * Math.sin(i * 4.1 + now * .5)) + flash * 1.1);
            Vec3 tip = at.add(radial.scale(length)).subtract(axis.scale(length * .35));
            Vec3 side = perpendicular(tip.subtract(at), camera.subtract(at)).scale(scale * (.025 + flash * .035));
            quad(pose, buf, at.add(side), at.subtract(side), tip, tip, 0, 1, 0xD0FFFFE8);
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float u0, float u1, int color) {
        vertex(pose, buf, a, u0, 0, color); vertex(pose, buf, b, u0, 1, color);
        vertex(pose, buf, c, u1, 1, color); vertex(pose, buf, d, u1, 0, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static RenderType material(String name, String texture, String define, boolean additive) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/physical_beam_" + name)).withFragmentShader(id("core/physical_beam"))
                .withShaderDefine(define).withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (name.equals("air")) pipeline.withSampler("NoiseTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/" + texture + ".png"));
        if (name.equals("air")) setup.withTexture("NoiseTex", id("textures/vfx/void_beam/warp.png"));
        return RenderType.create("logos_physical_beam_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
