package dev.logos.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Objects;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.fx.IEffectExecutor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
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
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import static dev.logos.client.LogosFx.layer;
import static dev.logos.client.LogosFx.range;
import static dev.logos.client.LogosFx.size;
import static dev.logos.client.LogosFx.sphere;

/**
 * Ray 4's nested tubes and Energy/Hit 4 spheres, with Ray 3 aura and a screen-projected void interior.
 * Kept compact (closer to Piloto's VoidRay, a thin dark line in a slim haze): every layer grows out of a
 * narrow neck at the wand, and the knot at the wand stays small, shrinking further right in front of the lens.
 */
final class VoidBeamVisual {
    private static final Mesh TUBE = Mesh.load("tube_1"), SWELL = Mesh.load("tube_2"), SPHERE = Mesh.load("sphere");
    private static final RenderType CORE = material("core", "solid", "VOID_CORE", false);
    private static final RenderType SOLID = material("solid", "solid", "VOID_SOLID", true);
    private static final RenderType FLOW = material("flow", "flow", "VOID_FLOW", true);
    private static final RenderType INNER_FLOW = material("inner_flow", "flow", "VOID_INNER_FLOW", true);
    private static final RenderType AURA = material("aura", "ribbon", "VOID_RIBBON", true);
    private static final RenderType PATCHES = material("patches", "solid", "VOID_PATCHES", false);
    private static final RenderType SPHERE_CORE = material("sphere_core", "solid", "VOID_SPHERE_CORE", false);
    private static final RenderType SPHERE_SOLID = material("sphere_solid", "solid", "VOID_SPHERE_SOLID", true);
    private static final RenderType SPHERE_FLOW = material("sphere_flow", "flow", "VOID_SPHERE_FLOW", true);
    private static final RenderType SPHERE_DARK = material("sphere_dark", "flow", "VOID_SPHERE_DARK", false);
    private static final RenderType SPHERE_AURA = material("sphere_aura", "aura", "VOID_SPHERE_AURA", true);
    private static final FX TERMINUS = motes();
    private FXRuntime terminus;
    private Vec3 lastFrom;
    private final boolean defaultMotes;
    /** Overall scale of the beam's radius; the visible tube's half-width is this times the requested radius. */
    static final float BODY = .62f;
    /** The tubes widen from a neck at the wand over this many blocks. */
    private static final float NECK = 1.4f;
    /** Lines drawn lately and the radius they were drawn with, so the wake left by a beam can match it. */
    private record Drawn(Vec3 from, Vec3 to, float radius) {}
    private static final ArrayDeque<Drawn> DRAWN = new ArrayDeque<>();

    VoidBeamVisual(boolean defaultMotes) { this.defaultMotes = defaultMotes; }

    void submit(Level level, Vec3 from, Vec3 to, float radius, CameraRenderState camera,
            PoseStack stack, SubmitNodeCollector out) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < .0025) return;
        Vec3 axis = delta.normalize();
        float requested = radius;
        radius *= BODY;
        float length = (float)delta.length(), scale = Math.max(.2f, radius / .5f);
        final float width = radius;
        double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (terminus == null && defaultMotes) {
            terminus = TERMINUS.createRuntime();
            for (var object : terminus.objects.values()) if (object instanceof ParticleEmitter e)
                e.config.renderer.setCompositeMode(FXCompositeMode.VANILLA);
            locate(terminus, to, axis, scale);
            terminus.emit(new Executor(level));
        }
        if (terminus != null) locate(terminus, to.subtract(axis.scale(.1)), axis.scale(-1), scale);
        lastFrom = from;
        remember(from, to, requested);
        double eye = from.distanceTo(camera.pos);
        // Right in front of the lens (first person) the neck is drawn out further, so the beam opens up away from the view.
        float close = 1 - smooth((float)((eye - .6) / 2.5)), neckLength = NECK * (1 + 3 * close);
        float neck = Math.min(.45f, neckLength / length), base = .3f - .18f * close;
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.toVector3f());
        tube(stack, out, CORE, TUBE, from, rotation, radius * .52f, length, now, false, neck, base);
        // The three mesh layers, drawn closer together than the original 1 / .7 / .4.
        tube(stack, out, SOLID, TUBE, from, rotation, radius * .85f, length, now, true, neck, base);
        tube(stack, out, FLOW, SWELL, from, rotation, radius * .62f, length, now, false, neck, base);
        tube(stack, out, INNER_FLOW, TUBE, from, rotation, radius * .4f, length, now, false, neck, base);
        out.submitCustomGeometry(stack, AURA, (pose, buf) -> aura(pose, buf, from, to, camera.pos, width, now, neckLength));
        // The dark patches keep clear of the wand, where they would sweep across a first-person view.
        float patchStart = Math.min(length * .25f, Math.max(radius * 1.4f, neckLength)), patchEnd = Math.min(length * .2f, radius * 1.8f);
        if (length - patchStart - patchEnd > .1f) tube(stack, out, PATCHES, SWELL, from.add(axis.scale(patchStart)), rotation,
                radius * .95f, length - patchStart - patchEnd, now, false, 0, 1);
        // A small knot at the wand, smaller still right in front of the lens.
        float knot = radius * .6f * (1 - .8f * close);
        endpoint(stack, out, from.add(axis.scale(.02)), rotation, knot, now, false);
        endpoint(stack, out, to.subtract(axis.scale(.06)), rotation, radius * 1.25f, now, true);
        stack.popPose();
    }

    void end() {
        if (terminus != null) { terminus.destroy(false); terminus = null; }
    }

    private static void remember(Vec3 from, Vec3 to, float radius) {
        DRAWN.removeIf(d -> d.to.distanceToSqr(to) < 1e-6 || d.from.distanceToSqr(from) < 1e-6);
        DRAWN.addFirst(new Drawn(from, to, radius));
        while (DRAWN.size() > 16) DRAWN.removeLast();
    }

    /** The radius a void beam was last drawn with along this line (its wake matches it), or {@code fallback}. */
    static float drawnRadius(Vec3 from, Vec3 to, float fallback) {
        for (Drawn d : DRAWN) if (d.to.distanceToSqr(to) < 1e-4 && d.from.distanceToSqr(from) < 1e-4) return d.radius;
        return fallback;
    }

    private static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    private record Executor(Level level) implements IEffectExecutor {
        @Override public Level getLevel() { return level; }
    }

    private static void locate(FXRuntime fx, Vec3 at, Vec3 axis, float scale) {
        fx.getRoot().updatePos(at.toVector3f());
        fx.getRoot().updateRotation(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.toVector3f()));
        fx.getRoot().updateScale(new Vector3f(scale));
    }

    private static FX motes() {
        FX fx = new FX();
        ParticleEmitter e = layer(Element.VOID, true, .35f, 0, size(.08f, .21f), range(.02f, .065f), range(8, 18), sphere(.25f));
        ProjectileParticles.configureTexture(e, "void_beam/energy", true);
        e.config.setSimulationSpace(ParticleConfig.Space.Local);
        e.config.setStartColor(NumberFunction.color(0xC8C08AFF));
        e.config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), range(0, 360)));
        e.config.physics.setEnable(false);
        e.config.colorOverLifetime.setColor(new Gradient(new GradientColor(0x00FFFFFF, 0xFFFFFFFF, 0x809A28FF, 0x00FFFFFF)));
        e.config.setMaxParticles(24);
        fx.getFxData().objects().add(e);
        return fx;
    }

    private static void tube(PoseStack stack, SubmitNodeCollector out, RenderType material, Mesh mesh,
            Vec3 from, Quaternionf rotation, float radius, float length, double now, boolean displaced, float neck, float base) {
        stack.pushPose();
        stack.translate(from.x, from.y, from.z);
        stack.mulPose(rotation);
        stack.scale(radius, length, radius);
        out.submitCustomGeometry(stack, material, (pose, buf) -> mesh.draw(pose, buf, now, displaced, neck, base));
        stack.popPose();
    }

    private static void endpoint(PoseStack stack, SubmitNodeCollector out, Vec3 at, Quaternionf rotation,
            float radius, double now, boolean end) {
        stack.pushPose();
        stack.translate(at.x, at.y, at.z);
        stack.mulPose(rotation);
        float pulse = (float)(1 + .035 * Math.sin(now * .15 + (end ? 2 : 0)));
        stack.scale(radius * pulse, radius * pulse, radius * pulse);
        sphereLayer(stack, out, SPHERE_CORE, .85f, now, false);
        sphereLayer(stack, out, SPHERE_DARK, .9f, now, false);
        sphereLayer(stack, out, SPHERE_SOLID, .95f, now, true);
        sphereLayer(stack, out, SPHERE_FLOW, 1.04f, now + (end ? 17 : 0), false);
        sphereLayer(stack, out, SPHERE_AURA, 1.2f, now, false);
        stack.popPose();
    }

    private static void sphereLayer(PoseStack stack, SubmitNodeCollector out, RenderType type,
            float radius, double now, boolean displaced) {
        stack.pushPose(); stack.scale(radius, radius, radius);
        out.submitCustomGeometry(stack, type, (pose, buf) -> SPHERE.draw(pose, buf, now, displaced, 0, 1));
        stack.popPose();
    }

    /** Ray 3's slowly panning aura occupies a loose ribbon around the tube, without snowflake sprites. */
    private static void aura(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius, double now, float neck) {
        Vec3 delta = to.subtract(from), side = delta.cross(camera.subtract(from.add(to).scale(.5)));
        if (side.lengthSqr() < 1e-8) side = delta.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
        float length = (float)delta.length(), repeat = length / 3;
        for (int i = 0; i < 48; i++) {
            float a = i / 48f, b = (i + 1) / 48f;
            Vec3 p = from.add(delta.scale(a)), q = from.add(delta.scale(b));
            // A slimmer haze than Ray 3's, pinched in at the wand like the tubes.
            double wa = radius * (1.2 + .1 * Math.sin(a * 19 + now * .035)) * (.2 + .8 * smooth(a * length / (neck * 1.3f)));
            double wb = radius * (1.2 + .1 * Math.sin(b * 19 + now * .035)) * (.2 + .8 * smooth(b * length / (neck * 1.3f)));
            vertex(pose, buf, p.add(side.scale(wa)), a * repeat, 0);
            vertex(pose, buf, p.subtract(side.scale(wa)), a * repeat, 1);
            vertex(pose, buf, q.subtract(side.scale(wb)), b * repeat, 1);
            vertex(pose, buf, q.add(side.scale(wb)), b * repeat, 0);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v) {
        buf.addVertex(pose, (float)p.x, (float)p.y, (float)p.z).setColor(0xFF9A28FF).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** Indexed game export: two big-endian counts, eight floats per vertex (XYZ, UV, normal), then triangle indices. */
    private record Mesh(float[] vertices, int[] triangles) {
        static Mesh load(String name) {
            try (var in = new DataInputStream(Objects.requireNonNull(VoidBeamVisual.class.getResourceAsStream(
                    "/assets/logos/models/vfx/void_beam/" + name + ".bin")))) {
                int count = in.readInt(), indices = in.readInt();
                if (count < 1 || count > 20000 || indices < 3 || indices > 100000 || indices % 3 != 0)
                    throw new IOException("Invalid void beam mesh: " + name);
                float[] vertices = new float[count * 8]; int[] triangles = new int[indices];
                for (int i = 0; i < vertices.length; i++) vertices[i] = in.readFloat();
                for (int i = 0; i < indices; i++) {
                    triangles[i] = in.readInt();
                    if (triangles[i] < 0 || triangles[i] >= count) throw new IOException("Invalid void beam index");
                }
                return new Mesh(vertices, triangles);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }

        /** A positive {@code neck} narrows the tube to {@code base} at y = 0, reaching full width at y = neck. */
        void draw(PoseStack.Pose pose, VertexConsumer buf, double now, boolean displaced, float neck, float base) {
            for (int i = 0; i < triangles.length; i += 3) {
                put(pose, buf, triangles[i], now, displaced, neck, base);
                put(pose, buf, triangles[i + 1], now, displaced, neck, base);
                put(pose, buf, triangles[i + 2], now, displaced, neck, base);
                put(pose, buf, triangles[i + 2], now, displaced, neck, base);
            }
        }

        private void put(PoseStack.Pose pose, VertexConsumer buf, int index, double now, boolean displaced, float neck, float base) {
            int k = index * 8;
            float bulge = displaced ? (float)(.035 * Math.sin(vertices[k + 3] * 18 + now * .08)
                    * Math.sin(vertices[k + 4] * 25 - now * .05)) : 0;
            float narrow = neck > 0 ? base + (1 - base) * smooth(vertices[k + 1] / neck) : 1;
            buf.addVertex(pose, (vertices[k] + vertices[k + 5] * bulge) * narrow, vertices[k + 1],
                            (vertices[k + 2] + vertices[k + 7] * bulge) * narrow)
                    .setColor(0xFF9A28FF).setUv(vertices[k + 3], vertices[k + 4])
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, vertices[k + 5], vertices[k + 6], vertices[k + 7]);
        }
    }

    private static RenderType material(String name, String texture, String define, boolean additive) {
        boolean core = name.equals("core") || name.equals("sphere_core");
        boolean flow = name.equals("flow") || name.equals("inner_flow") || name.equals("sphere_flow");
        boolean warp = flow || name.equals("sphere_dark") || name.equals("sphere_aura");
        // Opaque endpoint interiors and dark fragments must occlude the beam even if render types are reordered.
        boolean opaque = name.equals("sphere_core") || name.equals("patches");
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_beam_" + name)).withVertexShader(id("core/void_projectile"))
                .withFragmentShader(id("core/void_beam")).withShaderDefine(define).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, opaque))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (flow) pipeline.withSampler("NoiseTex");
        if (warp) pipeline.withSampler("WarpTex");
        if (core) pipeline.withSampler("StarTex").withSampler("AuraTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", id("textures/vfx/void_beam/" + texture + ".png"));
        if (flow) setup.withTexture("NoiseTex", id("textures/vfx/void_beam/dissolve.png"));
        if (warp) setup.withTexture("WarpTex", id("textures/vfx/void_beam/warp.png"));
        if (core) setup.withTexture("StarTex", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"))
                .withTexture("AuraTex", id("textures/vfx/void_beam/aura.png"));
        return RenderType.create("logos_void_beam_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
