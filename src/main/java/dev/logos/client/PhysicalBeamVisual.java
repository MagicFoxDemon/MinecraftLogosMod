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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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

/**
 * A lasting physical beam, the held version of the physical ray: a needle of white light with the cross flash
 * and the sonic-boom cones on the very first tick, vapour rings popping out along the line as its end races
 * out, then a fresh pressure pulse every {@link #PULSE} ticks that runs down the line faster than the eye and
 * leaves a row of rings behind it. Where it bites a block or a body the air bursts back toward the caster and
 * keeps thumping. Air, flash and halo share the physical ray's shader and textures ({@code physical_ray/}).
 */
final class PhysicalBeamVisual {
    private static final RenderType CORE = material("core", "physical_beam", "fire_beam/laser", "PHYSICAL_CORE", true);
    private static final RenderType GLOW = material("glow", "physical_beam", "fire_beam/laser", "PHYSICAL_GLOW", true);
    private static final RenderType NEEDLE = material("needle", "physical_ray_fx", "physical_ray/glow", "RAY_LINE", true);
    private static final RenderType FLASH = material("cross", "physical_ray_fx", "physical_ray/cross", "RAY_FLASH", true);
    private static final RenderType HALO = material("halo", "physical_ray_fx", "physical_ray/glow", "RAY_FLASH", true);
    private static final RenderType AIR = material("air", "physical_ray_fx", "physical_ray/air", "RAY_AIR", false);
    private static final FX LAUNCH_DUST = smoke(false, false), WAKE = smoke(true, false), CONTACT_DUST = smoke(true, true);

    /** Ticks between the pressure pulses pushed down a held beam, and how fast each runs (blocks a tick). */
    private static final float PULSE = 9, PULSE_SPEED = 9;
    /** Ticks between the thumps where it bites. */
    private static final float THUMP = 7;
    /** The launch's vapour rings: spacing along the line and how long each lives. */
    private static final float RING_SPACING = 3, RING_LIFE = 12;

    private record Puff(FXRuntime runtime, double born) {}
    private final List<Puff> puffs = new ArrayList<>();
    private final boolean defaultMotes;
    private final int seed = (int) System.nanoTime();
    private FXRuntime wake, contact;
    private double started = Double.NaN, impactBorn, bodyBorn, nextImpactSound, nextPulseSound;
    private Vec3 lastFrom, lastImpact, lastBody;
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
            started = now;
            nextPulseSound = now + PULSE;
            if (defaultMotes) puffs.add(new Puff(emit(LAUNCH_DUST, level, from, axis, scale), now));
            level.playLocalSound(from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, .55f, 1.8f, false);
            level.playLocalSound(from.x, from.y, from.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .5f, .6f, false);
        }
        if (now >= nextPulseSound) {
            nextPulseSound += PULSE;
            level.playLocalSound(from.x, from.y, from.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .14f, .7f, false);
        }
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
        // Where its end bites a block (the end races out, so this follows it).
        BlockHitResult hit = level.clip(new ClipContext(from, to.add(axis.scale(.3)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, Minecraft.getInstance().player));
        boolean impacting = hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(to) < .65;
        Vec3 impactAt = impacting ? hit.getLocation().subtract(axis.scale(.07)) : to;
        if (impacting) {
            boolean fresh = lastImpact == null || lastImpact.distanceToSqr(impactAt) > 2.25;
            if (fresh) {
                impactBorn = now;
                level.playLocalSound(impactAt.x, impactAt.y, impactAt.z, SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.BLOCKS, .5f, 1.25f, false);
                nextImpactSound = now + 24;
                debris(level, impactAt, axis, hit.getBlockPos(), 12);
            }
            lastImpact = impactAt;
            if (defaultMotes) {
                Vec3 dustAt = impactAt.subtract(axis.scale(.2 * scale));
                if (contact == null) contact = emit(CONTACT_DUST, level, dustAt, axis.scale(-1), scale);
                locate(contact, dustAt, axis.scale(-1), scale);
            }
            if (now >= nextImpactSound) {
                level.playLocalSound(impactAt.x, impactAt.y, impactAt.z, SoundEvents.GENERIC_EXPLODE.value(),
                        SoundSource.BLOCKS, .3f, .75f, false);
                nextImpactSound = now + 24;
            }
            if (defaultMotes && level.getGameTime() != lastDustTick && level.getGameTime() % 5 == 0) {
                lastDustTick = level.getGameTime();
                debris(level, impactAt, axis, hit.getBlockPos(), 3);
            }
        } else {
            lastImpact = null;
            if (contact != null) { contact.destroy(false); contact = null; }
        }
        // The beam runs through bodies; the first one it passes takes a burst of pressure where it goes in.
        Vec3 bodyAt = firstBody(level, from, to);
        if (bodyAt != null && (lastBody == null || lastBody.distanceToSqr(bodyAt) > 2.25)) {
            bodyBorn = now;
            level.playLocalSound(bodyAt.x, bodyAt.y, bodyAt.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, .6f, .7f, false);
        }
        lastBody = bodyAt;
        lastFrom = from;

        float age = (float)(now - started), hitAge = (float)(now - impactBorn), bodyAge = (float)(now - bodyBorn);
        Vec3 eye = camera.pos;
        // From behind the wand (first person) the launch dressing moves a little way out and keeps small.
        float near = (float)Math.min(1, Math.max(0, (eye.distanceTo(from) - .8) / 3));
        Vec3 muzzle = from.add(axis.scale(Math.min(length * .5, 2.4 * (1 - near))));
        Vec3 right = perpendicular(axis, new Vec3(0, 1, 0)), up = axis.cross(right);
        boolean struck = impacting;
        stack.pushPose();
        stack.translate(-eye.x, -eye.y, -eye.z);
        out.submitCustomGeometry(stack, CORE, (pose, buf) -> needle(pose, buf, from, to, .025f * scale));
        out.submitCustomGeometry(stack, GLOW, (pose, buf) -> band(pose, buf, from, to, eye, .105f * scale, 0xA8FFF5DD));
        out.submitCustomGeometry(stack, NEEDLE, (pose, buf) -> {
            // The launch punches through hot and wide, then settles onto the held core.
            if (age < 8) {
                float k = smooth(age / 8);
                rayBand(pose, buf, from, to, axis, eye, (.2f + (.06f - .2f) * k) * (.55f + .45f * near) * scale, 1 - .5f * k);
            } else rayBand(pose, buf, from, to, axis, eye, .06f * scale, .5f + .12f * (float)Math.sin(now * 1.7));
        });
        out.submitCustomGeometry(stack, HALO, (pose, buf) -> {
            float flash = flashCurve(age, 10);
            if (flash > 0) {
                float r = (.8f + 1.8f * near) * flash * scale;
                flare(pose, buf, muzzle, camera, Math.min(r, (.14f + .16f * near) * (float)eye.distanceTo(muzzle)), 0, alpha(.5f * (.4f + .6f * near)));
            }
            if (struck) {
                float h = flashCurve(hitAge, 9);
                float glow = .55f + .1f * (float)Math.sin(now * 1.9);
                flare(pose, buf, impactAt, camera, (1.1f + 1.6f * h) * scale, 0, alpha(Math.max(glow * .55f, .6f * h)));
            }
            if (bodyAt != null) {
                float b = flashCurve(bodyAge, 8);
                flare(pose, buf, bodyAt, camera, (.7f + 1.2f * b) * scale, 0, alpha(.35f + .3f * b));
            }
        });
        out.submitCustomGeometry(stack, FLASH, (pose, buf) -> {
            // The cross flash on the very first tick, together with the beam.
            float flash = flashCurve(age, 9);
            if (flash > 0) {
                float r = (.9f + 2.2f * near) * flash * scale;
                // Never more than about a fifth of the view across, however close the camera.
                r = Math.min(r, (.17f + .23f * near) * (float)eye.distanceTo(muzzle));
                flare(pose, buf, muzzle, camera, r, .05f + age * .01f, alpha(.95f * (.55f + .45f * near)));
            }
            // A small hot star stays on the muzzle while it is held; from the caster's own eyes it is barely there.
            float hold = (.12f + .2f * near) * scale * (.85f + .15f * (float)Math.sin(now * 2.3));
            flare(pose, buf, from.add(axis.scale(.05)), camera, Math.min(hold, (.04f + .08f * near) * (float)eye.distanceTo(from)), .12f, alpha(.75f));
            if (struck) {
                float h = flashCurve(hitAge, 7);
                float pulse = .5f + .5f * (float)Math.sin(now * 1.9);
                flare(pose, buf, impactAt, camera, (.8f + h * 1.6f + pulse * .15f) * scale, -.12f, alpha(.9f));
            }
            if (bodyAt != null) {
                float b = flashCurve(bodyAge, 6);
                flare(pose, buf, bodyAt, camera, (.45f + .9f * b) * scale, .3f, alpha(.75f));
            }
        });
        if (struck) out.submitCustomGeometry(stack, GLOW, (pose, buf) ->
                impactCuts(pose, buf, impactAt, eye, axis, scale, now, flashCurve(hitAge, 8)));
        out.submitCustomGeometry(stack, AIR, (pose, buf) -> {
            float own = .4f + .6f * near;
            // The sonic boom: two cones of shocked air flung forward off the muzzle on the first ticks.
            Vec3 base = from.add(axis.scale(.25 + 1.6 * (1 - near)));
            boom(pose, buf, base, axis, age, 10, 2.4f * own * scale, own, seed);
            boom(pose, buf, base, axis, age - 2, 9, 1.5f * own * scale, .75f * own, seed + 7);
            // From the caster's own eyes the nearest rings would ring the crosshair: they start further out and fainter.
            float clear = 5f * (1 - near);
            // Vapour rings popping out along the line as its end races past (BEAM_REACH, 4 blocks a tick).
            int rings = Math.min(22, (int)(length / RING_SPACING));
            for (int i = 1; i <= rings; i++) {
                float along = i * RING_SPACING + .5f * hash(seed, i, 3);
                if (along < clear || along > length - .8f) continue;
                float ringAge = age - along / 4 - .3f * hash(seed, i, 5);
                if (ringAge < 0 || ringAge > RING_LIFE) continue;
                vapour(pose, buf, from, axis, right, up, along, ringAge, RING_LIFE, 1.25f * scale, 1, seed + i * 13);
            }
            // Held: a pressure pulse every PULSE ticks, a cone off the muzzle and a row of rings left where its front passes.
            int last = (int)Math.floor(age / PULSE);
            for (int k = Math.max(1, last - 3); k <= last; k++) {
                float pulseAge = age - k * PULSE;
                int ps = seed + k * 101;
                // From the caster's own eyes the held pulses barely show at the muzzle, so the view down the beam stays clear.
                boom(pose, buf, base, axis, pulseAge, 8, 1.3f * own * scale, .55f * (.12f + .88f * near), ps);
                for (int i = 0; i < 24; i++) {
                    float along = 1.5f + i * 3.6f + 1.2f * hash(ps, i, 3);
                    if (along > length - .8f) break;
                    if (along < clear) continue;
                    float ringAge = pulseAge - along / PULSE_SPEED;
                    if (ringAge < 0 || ringAge > 10) continue;
                    vapour(pose, buf, from, axis, right, up, along, ringAge, 10, .8f * scale, .6f * (.55f + .45f * near), ps + i * 17);
                }
            }
            if (struck) {
                Vec3 back = axis.scale(-1);
                // The first bite: a broad shock ring, a second one behind it and a cone thrown back at the caster.
                if (hitAge < 14) {
                    float p = hitAge / 14, e = 1 - (1 - p) * (1 - p) * (1 - p);
                    ring(pose, buf, impactAt, back, (.3f + 3.4f * e) * scale, (.2f + .45f * e) * scale, (1 - p) * .95f, .35f * e, seed + 101, 64);
                }
                if (hitAge >= 2 && hitAge < 14) {
                    float p = (hitAge - 2) / 12, e = 1 - (1 - p) * (1 - p);
                    ring(pose, buf, impactAt.add(back.scale(.15)), back, (.2f + 2.1f * e) * scale, (.14f + .3f * e) * scale,
                            (1 - p) * .7f, .2f * e, seed + 211, 56);
                }
                boom(pose, buf, impactAt.add(back.scale(.1)), back, hitAge, 11, 2.6f * scale, .9f, seed + 307);
                // Then it keeps thumping while it is held there.
                float since = hitAge - 6;
                if (since >= 0) {
                    float p = (since % THUMP) / THUMP, e = 1 - (1 - p) * (1 - p) * (1 - p);
                    int n = (int)(since / THUMP);
                    ring(pose, buf, impactAt.add(back.scale(.05)), back, (.25f + 1.9f * e) * scale, (.12f + .25f * e) * scale,
                            (1 - p) * .75f, .25f * e, seed + n * 31, 56);
                    if (p < .7f) boom(pose, buf, impactAt.add(back.scale(.08)), back, p * THUMP, THUMP * .7f, 1.4f * scale, .45f, seed + n * 47);
                }
            }
            if (bodyAt != null) {
                Vec3 back = axis.scale(-1);
                if (bodyAge < 12) {
                    float p = bodyAge / 12, e = 1 - (1 - p) * (1 - p) * (1 - p);
                    ring(pose, buf, bodyAt, back, (.25f + 2.2f * e) * scale, (.16f + .3f * e) * scale, (1 - p) * .85f, .3f * e, seed + 401, 56);
                }
                float p = (bodyAge % THUMP) / THUMP, e = 1 - (1 - p) * (1 - p);
                ring(pose, buf, bodyAt, back, (.2f + 1.2f * e) * scale, (.1f + .2f * e) * scale, (1 - p) * .55f, .2f * e,
                        seed + (int)(bodyAge / THUMP) * 29, 48);
            }
        });
        stack.popPose();
    }

    void end() {
        if (wake != null) { wake.destroy(false); wake = null; }
        if (contact != null) { contact.destroy(false); contact = null; }
        for (Puff puff : puffs) puff.runtime.destroy(false);
        puffs.clear();
    }

    // ---- what it touches -------------------------------------------------------------------------

    /** Where the drawn line first enters a living body other than whoever holds it (or the camera), or null. */
    private static Vec3 firstBody(Level level, Vec3 from, Vec3 to) {
        Entity viewer = Minecraft.getInstance().getCameraEntity();
        Vec3 best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity)null, new AABB(from, to).inflate(1), e -> e instanceof LivingEntity && e.isAlive()
                && e != viewer && e.getEyePosition().distanceToSqr(from) > 2.25)) {
            AABB box = e.getBoundingBox().inflate(.1);
            if (box.contains(from)) continue;
            var at = box.clip(from, to);
            if (at.isEmpty()) continue;
            double d = at.get().distanceToSqr(from);
            if (d < bestSq) { bestSq = d; best = at.get(); }
        }
        return best;
    }

    /** Chips of the bitten block thrown back toward the caster. */
    private static void debris(Level level, Vec3 at, Vec3 axis, BlockPos pos, int count) {
        var state = level.getBlockState(pos);
        if (state.isAir()) return;
        var r = level.getRandom();
        for (int i = 0; i < count; i++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z,
                -axis.x * .25 + (r.nextDouble() - .5) * .4, .1 + r.nextDouble() * .25, -axis.z * .25 + (r.nextDouble() - .5) * .4);
    }

    // ---- particles -------------------------------------------------------------------------------

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

    // ---- shapes ----------------------------------------------------------------------------------

    /** Snaps on within a third of a tick, then decays to 0 at {@code life} like an exposure flash. */
    private static float flashCurve(float t, float life) {
        if (t < 0 || t >= life) return 0;
        if (t < .35f) return .55f + .45f * t / .35f;
        float p = (t - .35f) / (life - .35f);
        return (float)Math.pow(1 - p, 1.4);
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

    private static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius, int color) {
        Vec3 side = perpendicular(to.subtract(from), camera.subtract(from.add(to).scale(.5))).scale(radius);
        quad(pose, buf, from.add(side), from.subtract(side), to.subtract(side), to.add(side), 0, 1, color);
    }

    /** The ray's white needle: cut finer near the start so it thins right at the camera and never vanishes far off. */
    private static void rayBand(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 axis, Vec3 eye, float width, float alpha) {
        double len = a.distanceTo(b);
        double[] cuts = {.3, .8, 1.6, 3.2, 6, len};
        double prev = 0;
        int color = alpha(alpha);
        for (double cut : cuts) {
            double c = Math.min(cut, len);
            if (c <= prev) break;
            Vec3 p = a.add(axis.scale(prev)), q = a.add(axis.scale(c));
            Vec3 side = perpendicular(axis, eye.subtract(p.add(q).scale(.5)));
            float wp = rayWidth(p, eye, width), wq = rayWidth(q, eye, width);
            vertex(pose, buf, p.add(side.scale(wp)), 0, 0, color);
            vertex(pose, buf, p.subtract(side.scale(wp)), 0, 1, color);
            vertex(pose, buf, q.subtract(side.scale(wq)), 1, 1, color);
            vertex(pose, buf, q.add(side.scale(wq)), 1, 0, color);
            prev = c;
        }
    }

    private static float rayWidth(Vec3 p, Vec3 eye, float width) {
        float d = (float)p.distanceTo(eye);
        return Math.max(width * Math.min(1, .08f + d / 6), d * .0055f);
    }

    private static void flare(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, CameraRenderState camera, float radius, float roll, int color) {
        if (radius <= 0) return;
        Vector3f x = new Vector3f((float)Math.cos(roll), (float)Math.sin(roll), 0);
        Vector3f y = new Vector3f(-(float)Math.sin(roll), (float)Math.cos(roll), 0);
        Vec3 right = new Vec3(camera.orientation.transform(x)).scale(radius), up = new Vec3(camera.orientation.transform(y)).scale(radius);
        vertex(pose, buf, at.subtract(right).add(up), 0, 0, color);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1, color);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1, color);
        vertex(pose, buf, at.add(right).add(up), 1, 0, color);
    }

    /** One vapour ring at {@code along} on the line, {@code age} ticks old: it snaps out, drifts forward and thins away. */
    private static void vapour(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 axis, Vec3 right, Vec3 up,
            float along, float age, float life, float size, float alpha, int seed) {
        float p = age / life, e = 1 - (1 - p) * (1 - p) * (1 - p);
        float tiltX = (hash(seed, 0, 7) - .5f) * .5f, tiltY = (hash(seed, 0, 11) - .5f) * .5f;
        Vec3 n = axis.add(right.scale(tiltX)).add(up.scale(tiltY)).normalize();
        Vec3 c = from.add(axis.scale(along + .6 * e));
        ring(pose, buf, c, n, .12f + size * e, .07f + .17f * e * size, alpha * (float)Math.pow(1 - p, 1.5), .3f * e, seed, 40);
    }

    /** A cone of shocked air: a ring whose outer edge is swept back against {@code dir}, flung forward and widening. */
    private static void boom(PoseStack.Pose pose, VertexConsumer buf, Vec3 base, Vec3 dir, float t, float life, float size, float alpha, int seed) {
        if (t < 0 || t >= life || alpha <= 0) return;
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
                float v0 = j / (float)rows, v1 = (j + 1) / (float)rows;
                vertex(pose, buf, conePoint(c, dir, rr, uu, a0, v0, inner, outer, sweep, seed), i / (float)segments, v0, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a1, v0, inner, outer, sweep, seed), (i + 1) / (float)segments, v0, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a1, v1, inner, outer, sweep, seed), (i + 1) / (float)segments, v1, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a0, v1, inner, outer, sweep, seed), i / (float)segments, v1, color);
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
                float v0 = j / (float)rows, v1 = (j + 1) / (float)rows;
                vertex(pose, buf, ringPoint(c, n, rr, uu, a0, v0, radius, width, curl, seed), i / (float)segments, v0, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a1, v0, radius, width, curl, seed), (i + 1) / (float)segments, v0, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a1, v1, radius, width, curl, seed), (i + 1) / (float)segments, v1, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a0, v1, radius, width, curl, seed), i / (float)segments, v1, color);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 c, Vec3 n, Vec3 rr, Vec3 uu, double angle, float v, float radius, float width, float curl, int seed) {
        double ruffle = .07 * Math.sin(angle * 5 + seed) + .03 * Math.sin(angle * 11 - seed * .3);
        double r = Math.max(.02, radius * (1 + ruffle) + (v * 2 - 1) * width);
        return c.add(rr.scale(Math.cos(angle) * r)).add(uu.scale(Math.sin(angle) * r)).subtract(n.scale(curl * (v * 2 - 1) * (v * 2 - 1)));
    }

    /** Short white cuts splayed back from where it bites, longest on the first hit. */
    private static void impactCuts(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Vec3 camera, Vec3 axis, float scale, double now, float flash) {
        Vec3 right = perpendicular(axis, new Vec3(0, 1, 0)), up = axis.cross(right);
        for (int i = 0; i < 11; i++) {
            double angle = i * 2.399963 + Math.floor(now / 3) * .17;
            Vec3 radial = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            double length = scale * (.55 + .8 * (.5 + .5 * Math.sin(i * 4.1 + now * .5)) + flash * 1.4);
            Vec3 tip = at.add(radial.scale(length)).subtract(axis.scale(length * .35));
            Vec3 side = perpendicular(tip.subtract(at), camera.subtract(at)).scale(scale * (.025 + flash * .035));
            quad(pose, buf, at.add(side), at.subtract(side), tip, tip, 0, 1, 0xD0FFFFE8);
        }
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
        return (h >>> 8) / (float)(1 << 24);
    }

    private static int alpha(float a) {
        return Math.max(0, Math.min(255, (int)(a * 255))) << 24 | 0xFFFFFF;
    }

    private static Vec3 perpendicular(Vec3 axis, Vec3 view) {
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(1, 0, 0));
        return side.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : side.normalize();
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float u0, float u1, int color) {
        vertex(pose, buf, a, u0, 0, color); vertex(pose, buf, b, u0, 1, color);
        vertex(pose, buf, c, u1, 1, color); vertex(pose, buf, d, u1, 0, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** A physical-beam render type over an existing shader and texture (both read-only, shared with the ray). */
    private static RenderType material(String name, String shader, String texture, String define, boolean additive) {
        boolean air = define.endsWith("_AIR");
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/physical_beam_" + name)).withFragmentShader(id("core/" + shader))
                .withShaderDefine(define).withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (air) pipeline.withSampler("NoiseTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/" + texture + ".png"));
        if (air) setup.withTexture("NoiseTex", id("textures/vfx/physical_ray/wind.png"));
        return RenderType.create("logos_physical_beam_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
