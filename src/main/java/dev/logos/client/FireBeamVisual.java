package dev.logos.client;

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
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.lang.Element;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector2i;
import org.joml.Vector3f;
import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;

/** MagmaRay's two flowing bands and curled outlet, with Solar Flares continuously burning at the end. */
final class FireBeamVisual {
    private static final RenderType MAGMA = material(false);
    private static final RenderType ACCENTS = material(true);
    private static final RenderType WIND = windMaterial();
    private static final RenderType END_CORE = endMaterial();
    private static final FX OUTLET = effect(false), TERMINUS = effect(true);
    private static final FX BODY = bodyEffect();
    // Preserve the original outlet/terminus; only the along-beam particle cloud is omitted.
    private static final boolean EMIT_PARTICLES = true;
    private static final boolean EMIT_BODY = false;
    private FXRuntime outlet, terminus, body;
    private float bodyLength = -1;
    private Vec3 lastFrom;

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < .0025) return;
        Vec3 direction = delta.normalize();
        float scale = Math.max(.2f, radius / .5f);
        if (EMIT_PARTICLES && outlet == null) {
            outlet = emit(OUTLET, level, from, scale);
            terminus = emit(TERMINUS, level, to, scale);
            if (EMIT_BODY) body = emit(BODY, level, from.add(to).scale(.5), scale);
        }
        // Keep the cap on the drawn wand tip every frame, not just on server packet ticks.
        if (outlet != null) locate(outlet, from, direction, scale);
        if (terminus != null) locate(terminus, to.subtract(direction.scale(.04)), direction.scale(-1), scale);
        if (body != null) locate(body, from.add(to).scale(.5), direction, scale);
        float len = (float)delta.length();
        if (body != null && Math.abs(len - bodyLength) > .02f) {
            // Stretch the emission volume only: particle sizes keep the radius-based scale.
            for (var object : body.objects.values()) if (object instanceof ParticleEmitter e) {
                e.runtime().shape.scale.set(new NumberFunction3(.7f, len / scale, .7f));
                float density = e.getName().equals("sparks") ? .18f : .36f;
                e.runtime().emission.emissionRate.set(NumberFunction.constant(Math.min(12, len * density)));
            }
            bodyLength = len;
        }
        lastFrom = from;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float r = radius;
        out.submitCustomGeometry(stack, MAGMA, (pose, buf) -> band(pose, buf, from, to, camera.pos, r * .46f));
        out.submitCustomGeometry(stack, ACCENTS, (pose, buf) -> band(pose, buf, from, to, camera.pos, r * 2));
        FireBeamCore.submit(from, to, r, level.getGameTime() + net.minecraft.client.Minecraft.getInstance()
                .getDeltaTracker().getGameTimeDeltaPartialTick(false), camera, stack, out);
        out.submitCustomGeometry(stack, WIND, (pose, buf) -> outletCurl(pose, buf, from, direction, r));
        // A steady white-gold core covers the beam's clipped end between individual Photon flares.
        out.submitCustomGeometry(stack, END_CORE, (pose, buf) -> endCore(pose, buf,
                to.subtract(direction.scale(.06)), camera, r * 1.8f));
        stack.popPose();
    }

    void end() {
        if (outlet != null) { outlet.destroy(false); outlet = null; }
        if (terminus != null) { terminus.destroy(false); terminus = null; }
        if (body != null) { body.destroy(false); body = null; }
    }

    private static void locate(FXRuntime fx, Vec3 p, Vec3 axis, float scale) {
        fx.getRoot().updatePos(new Vector3f((float)p.x, (float)p.y, (float)p.z));
        fx.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float)axis.x, (float)axis.y, (float)axis.z)));
        fx.getRoot().updateScale(new Vector3f(scale));
    }

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    private static FXRuntime emit(FX template, Level level, Vec3 at, float scale) {
        FXRuntime runtime = template.createRuntime();
        // Keep Photon materials and the original cap animation; the inherited LATE stage loses entity depth.
        for (var object : runtime.objects.values()) if (object instanceof ParticleEmitter e) {
            e.config.renderer.setCompositeMode(com.lowdragmc.photon.client.render.FXCompositeMode.VANILLA);
        }
        locate(runtime, at, new Vec3(0, 1, 0), scale);
        runtime.emit(new Executor(level));
        return runtime;
    }

    private static FX effect(boolean end) {
        FX fx = new FX();
        if (end) {
            fx.getFxData().objects().addAll(List.of(
                    cap("flare", .9f, 1.1f, .42f, 7, 10, 0xD0FFE078),
                    cap("circle", 1.1f, 1.5f, .26f, 12, 16, 0xB8FF9C13),
                    cap("spiky_ring", 1.45f, 1.9f, .22f, 12, 20, 0x7CD63D03),
                    cap("glow", 1.05f, 1.3f, .25f, 8, 12, 0xA0FFAE28),
                    cap("cross", .75f, 1.05f, .18f, 7, 11, 0x70FF9C21),
                    flecks()));
        } else {
            fx.getFxData().objects().addAll(List.of(
                    cap("glow", .48f, .6f, .3f, 7, 10, 0xBCFF850D),
                    cap("cross", .4f, .55f, .18f, 8, 12, 0x9CFFC42A),
                    cap("circle", .65f, .85f, .18f, 10, 14, 0x98FF5805)));
        }
        return fx;
    }

    private static ParticleEmitter cap(String texture, float min, float max, float rate, float lifeMin, float lifeMax, int color) {
        ParticleEmitter e = layer(Element.FIRE, true, rate, 0, size(min, max), range(0, 0), range(lifeMin, lifeMax), sphere(0));
        ProjectileParticles.configureTexture(e, "fire_beam/" + texture,
                !texture.equals("circle") && !texture.equals("spiky_ring"));
        e.config.setSimulationSpace(ParticleConfig.Space.Local);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.physics.setEnable(false);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0xD0FFFFFF, 0xA0FFB470, 0x00FF6000)));
        Curve grow = new Curve(0, 1, 0, 1, "age", "size", new ECBCurves(0, .5f, .25f, .8f, .65f, 1, 1, 1.15f));
        e.config.sizeOverLifetime.setSize(new NumberFunction3(grow, grow, grow));
        e.config.setMaxParticles(20);
        return e;
    }

    private static ParticleEmitter flecks() {
        ParticleEmitter e = layer(Element.FIRE, true, .7f, 0, size(.08f, .18f), range(.025f, .10f), range(8, 18), sphere(.15f));
        ProjectileParticles.configureTexture(e, "fire_beam/fleck", true);
        e.config.setStartColor(NumberFunction.color(0xD0FF7A0B));
        e.config.physics.setEnable(false);
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(2, 2));
        e.config.uvAnimation.setStartFrame(range(0, 3.999f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        return e;
    }

    /** MagmaRay's Smoke_Harsh and Flecks_Fluid emit along the entire line, not just at its end. */
    private static FX bodyEffect() {
        FX fx = new FX();
        fx.getFxData().objects().addAll(List.of(
                bodyLayer("smoke_harsh", "smoke", .65f, 1.05f, 10, 18, 2, 2, 0xE0FFE72A),
                bodyLayer("fleck", "flecks", .45f, .85f, 9, 16, 2, 2, 0xE8FFE532),
                bodyLayer("clipped", "sparks", .035f, .075f, 12, 24, 3, 3, 0xFFFFC64A)));
        return fx;
    }

    private static ParticleEmitter bodyLayer(String texture, String name, float min, float max,
            float lifeMin, float lifeMax, int columns, int rows, int color) {
        ParticleEmitter e = layer(Element.FIRE, true, 1, 0, size(min, max), range(0, 0), range(lifeMin, lifeMax), new Box());
        e.setName(name);
        ProjectileParticles.configureTexture(e, "fire_beam/" + texture, false);
        e.config.setStartColor(NumberFunction.color(color));
        e.config.setSimulationSpace(ParticleConfig.Space.Local);
        e.config.physics.setEnable(false);
        e.config.setMaxParticles(160);
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0xFFFFFFFF, 0xE0FFF4A0, 0x00FFAB40)));
        e.config.velocityOverLifetime.setEnable(true);
        e.config.velocityOverLifetime.setLinear(new NumberFunction3(range(-.02f, .02f), range(.02f, .08f), range(-.02f, .02f)));
        e.config.rotationOverLifetime.setEnable(true);
        e.config.rotationOverLifetime.setRoll(range(-2, 2));
        e.config.uvAnimation.setEnable(true);
        e.config.uvAnimation.setTiles(new Vector2i(columns, rows));
        e.config.uvAnimation.setStartFrame(range(0, columns * rows - .001f));
        e.config.uvAnimation.setFrameOverTime(NumberFunction.constant(0));
        return e;
    }

    private static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float halfWidth) {
        Vec3 axis = to.subtract(from);
        Vec3 side = axis.cross(camera.subtract(from.add(to).scale(.5)));
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1e-8) side = new Vec3(1, 0, 0);
        side = side.normalize();
        float repeat = (float)axis.length() / 4;
        // LineRenderer uses camera-facing strips; subdivide the outlet's widening section.
        for (int i = 0; i < 24; i++) {
            float a = i / 24f, b = (i + 1) / 24f;
            Vec3 pa = from.add(axis.scale(a)), pb = from.add(axis.scale(b));
            Vec3 sa = side.scale(halfWidth * width(a, axis.length())), sb = side.scale(halfWidth * width(b, axis.length()));
            vertex(pose, buf, pa.add(sa), a * repeat, 0);
            vertex(pose, buf, pa.subtract(sa), a * repeat, 1);
            vertex(pose, buf, pb.subtract(sb), b * repeat, 1);
            vertex(pose, buf, pb.add(sb), b * repeat, 0);
        }
    }

    private static float width(float t, double len) {
        float rise = (float)Math.min(1, t * len / .8);
        return .24f + .76f * rise * rise * (3 - 2 * rise);
    }

    /** MagmaRay's short tornado mesh around the outlet, rebuilt as a flared sleeve. */
    private static void outletCurl(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 axis, float r) {
        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = axis.cross(right).normalize();
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12, b = (i + 1) * Math.PI / 12;
            Vec3 da = right.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
            Vec3 db = right.scale(Math.cos(b)).add(up.scale(Math.sin(b)));
            Vec3 near = from.add(axis.scale(r * .12)), far = from.add(axis.scale(r * 2));
            vertex(pose, buf, near.add(da.scale(r * .23)), i / 12f, 0);
            vertex(pose, buf, far.add(da.scale(r * .9)), i / 12f, 1);
            vertex(pose, buf, far.add(db.scale(r * .9)), (i + 1) / 12f, 1);
            vertex(pose, buf, near.add(db.scale(r * .23)), (i + 1) / 12f, 0);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(0xFFFFFFFF).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static void endCore(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, CameraRenderState camera, float r) {
        Vec3 right = new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))).scale(r);
        Vec3 up = new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))).scale(r);
        vertex(pose, buf, at.subtract(right).add(up), 0, 0);
        vertex(pose, buf, at.subtract(right).subtract(up), 0, 1);
        vertex(pose, buf, at.add(right).subtract(up), 1, 1);
        vertex(pose, buf, at.add(right).add(up), 1, 0);
    }

    private static RenderType material(boolean accents) {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/fire_beam_" + (accents ? "accents" : "magma")))
                .withFragmentShader(id("core/fire_beam"))
                .withSampler("NoiseTex").withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(accents ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (accents) pipeline.withShaderDefine("FIRE_ACCENTS").withSampler("MaskTex");
        var setup = RenderSetup.builder(pipeline.build())
                .withTexture("Sampler0", id("textures/vfx/fire_beam/magma.png"))
                .withTexture("NoiseTex", id("textures/vfx/fire_beam/noise.png"));
        if (accents) setup.withTexture("MaskTex", id("textures/vfx/fire_beam/accent_mask.png"));
        return RenderType.create("logos_fire_beam", setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    private static RenderType windMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/fire_beam_wind")).withFragmentShader(id("core/fire_beam_wind"))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
        return RenderType.create("logos_fire_beam_wind", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/fire_beam/wind.png")).sortOnUpload().createRenderSetup());
    }

    private static RenderType endMaterial() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/fire_beam_end")).withFragmentShader(id("core/fire_beam_end"))
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build();
        return RenderType.create("logos_fire_beam_end", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/fire_beam/flare.png")).sortOnUpload().createRenderSetup());
    }
}
