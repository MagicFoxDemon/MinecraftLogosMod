package dev.logos.client;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Materials and geometry for the form effects (FORM_VFX.md: pillar burst, mana tornado, surface aura), public so
 * addons can draw their own effects the same way. Textures are {@code logos:textures/vfx/...}; the geometry is
 * camera-facing sprites, flat quads, streaks, open cylinders and small cubes. Every vertex is written in full
 * (position, colour, UV, light, normal) and the callers submit through {@link ShaderPackOverlay}'s collector, so
 * it all shows under a shader pack too.
 */
public final class VfxParts {
    private VfxParts() {}

    private static final Map<Identifier, RenderType> ADD = new HashMap<>();
    private static final Map<Identifier, RenderType> BLEND = new HashMap<>();
    private static final Map<Identifier, RenderType> CUT = new HashMap<>();

    /** Additive glow over {@code logos:textures/vfx/<texture>.png}. */
    public static RenderType add(String texture) {
        return add(vfx(texture));
    }

    /** Additive glow over any texture (an addon's own, say). */
    public static RenderType add(Identifier texture) {
        return ADD.computeIfAbsent(texture, k -> material(texture, true));
    }

    /** Translucent blend over {@code logos:textures/vfx/<texture>.png}. */
    public static RenderType blend(String texture) {
        return blend(vfx(texture));
    }

    public static RenderType blend(Identifier texture) {
        return BLEND.computeIfAbsent(texture, k -> material(texture, false));
    }

    /**
     * Hard-edged cutout (Unreal's Masked) over {@code logos:textures/vfx/<texture>.png}, on vanilla's particle
     * shader: pixels whose texture alpha × vertex alpha is under 0.1 are not drawn, the rest blend and write depth,
     * so what is drawn later is hidden behind it. The texture's RGB is brightness, times the vertex colour, so one
     * texture has light and dark in it. Lowering the vertex alpha cuts the low-alpha parts away first: a dissolve.
     */
    public static RenderType cut(String texture) {
        return cut(vfx(texture));
    }

    public static RenderType cut(Identifier texture) {
        return CUT.computeIfAbsent(texture, k -> {
            String key = "cut_" + key(texture);
            var pipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
                    .withLocation(id("pipeline/vfx_" + key)).withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
            return RenderType.create("logos_vfx_" + key, RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", texture).useLightmap().sortOnUpload().createRenderSetup());
        });
    }

    /** Translucent over the block atlas: debris wearing the ground blocks' own textures, writing depth so pieces hide each other. */
    public static final RenderType BLOCKS = RenderType.create("logos_vfx_blocks", RenderSetup.builder(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET).withLocation(id("pipeline/vfx_blocks")).withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
            .withTexture("Sampler0", Identifier.withDefaultNamespace("textures/atlas/blocks.png")).sortOnUpload().createRenderSetup());

    private static RenderType material(Identifier texture, boolean additive) {
        String key = (additive ? "add_" : "blend_") + key(texture);
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(id("pipeline/vfx_" + key)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_vfx_" + key, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(LogosMod.MODID, path);
    }

    private static Identifier vfx(String texture) {
        return id("textures/vfx/" + texture + ".png");
    }

    /** A name for a texture's pipeline: its path, prefixed by its namespace unless that is {@code logos}. */
    private static String key(Identifier texture) {
        String path = texture.getPath().replace("textures/vfx/", "").replace(".png", "").replace('/', '_');
        return texture.getNamespace().equals(LogosMod.MODID) ? path : texture.getNamespace() + "_" + path;
    }

    /**
     * Where layer {@code n} is submitted so it draws after layer {@code n - 1}. Vanilla keys one batch of custom
     * geometry by render type in a HashMap, so which material draws first changes from launch to launch, and a dark
     * translucent layer drawn first is washed out by the additive glow drawn after it. Without a shader pack each
     * layer therefore goes into its own {@code order(n)} (batches drawn by number); with one, ShaderPackOverlay
     * already draws in submission order and gets everything. {@code raw} is the event's collector, {@code out} the
     * one through ShaderPackOverlay.
     */
    public static IntFunction<OrderedSubmitNodeCollector> layered(SubmitNodeCollector raw, SubmitNodeCollector out) {
        return out == raw ? n -> raw.order(LAYER_ORDER + n) : n -> out;
    }

    /** The first order the layers take; vanilla's and the other spell visuals' custom geometry is all at 0. */
    private static final int LAYER_ORDER = 1;

    // ---- geometry ------------------------------------------------------------------------------

    public static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** A quad centred on {@code c}, spanned by the half edges {@code a} and {@code b}, over the whole texture. */
    public static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, int color) {
        vertex(pose, buf, c.subtract(a).add(b), 0, 0, color);
        vertex(pose, buf, c.subtract(a).subtract(b), 0, 1, color);
        vertex(pose, buf, c.add(a).subtract(b), 1, 1, color);
        vertex(pose, buf, c.add(a).add(b), 1, 0, color);
    }

    /** A camera-facing quad of half size {@code r}, turned by {@code roll}. */
    public static void sprite(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, float r, float roll, int color) {
        float cos = Mth.cos(roll) * r, sin = Mth.sin(roll) * r;
        quad(pose, buf, c, view.right.scale(cos).add(view.up.scale(sin)), view.up.scale(cos).subtract(view.right.scale(sin)), color);
    }

    /** A camera-facing quad of half width {@code rx}, half height {@code ry}, turned by {@code roll}, showing only [u0,u1]×[v0,v1] (a flipbook frame). */
    public static void sprite(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, float rx, float ry, float roll,
            float u0, float v0, float u1, float v1, int color) {
        float cos = Mth.cos(roll), sin = Mth.sin(roll);
        Vec3 a = view.right.scale(cos * rx).add(view.up.scale(sin * rx)), b = view.up.scale(cos * ry).subtract(view.right.scale(sin * ry));
        vertex(pose, buf, c.subtract(a).add(b), u0, v0, color);
        vertex(pose, buf, c.subtract(a).subtract(b), u0, v1, color);
        vertex(pose, buf, c.add(a).subtract(b), u1, v1, color);
        vertex(pose, buf, c.add(a).add(b), u1, v0, color);
    }

    /** A quad lying in the plane of the unit vectors {@code a} and {@code b}, half size {@code r}, turned by {@code turn}. */
    public static void flat(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, float r, float turn, int color) {
        float cos = Mth.cos(turn) * r, sin = Mth.sin(turn) * r;
        quad(pose, buf, c, a.scale(cos).add(b.scale(sin)), b.scale(cos).subtract(a.scale(sin)), color);
    }

    /** A quad lying flat on the ground. */
    public static void ground(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, float turn, int color) {
        flat(pose, buf, c, new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, turn, color);
    }

    /** A camera-facing strip {@code w} wide from {@code tail} to {@code head}, the texture's U running tail to head. */
    public static void streak(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 tail, Vec3 head, float w, int color) {
        streak(pose, buf, view, tail, head, w, 0, 0, 1, 1, color);
    }

    /** As {@link #streak}, showing only [u0,u1]×[v0,v1] of the texture: for flipbook frames laid out in one image. */
    public static void streak(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 tail, Vec3 head, float w,
            float u0, float v0, float u1, float v1, int color) {
        Vec3 along = head.subtract(tail);
        Vec3 side = along.cross(head.add(tail).scale(.5).subtract(view.cam));
        if (side.lengthSqr() < 1e-10) return;
        side = side.normalize().scale(w * .5);
        vertex(pose, buf, tail.add(side), u0, v0, color);
        vertex(pose, buf, tail.subtract(side), u0, v1, color);
        vertex(pose, buf, head.subtract(side), u1, v1, color);
        vertex(pose, buf, head.add(side), u1, v0, color);
    }

    /**
     * An open cylinder standing on {@code base}, {@code radius} wide and {@code height} tall: U starts at {@code u0}
     * and repeats {@code uTiles} times round it, V spans {@code vTiles} from {@code v0} at the bottom. The bottom and
     * top take their own colours.
     */
    public static void cylinder(PoseStack.Pose pose, VertexConsumer buf, Vec3 base, float radius, float height, int segments,
            float u0, float uTiles, float v0, float vTiles, int bottom, int top) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            Vec3 p0 = base.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
            Vec3 p1 = base.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            float s0 = u0 + uTiles * i / segments, s1 = u0 + uTiles * (i + 1) / segments;
            vertex(pose, buf, p0, s0, v0 + vTiles, bottom);
            vertex(pose, buf, p0.add(0, height, 0), s0, v0, top);
            vertex(pose, buf, p1.add(0, height, 0), s1, v0, top);
            vertex(pose, buf, p1, s1, v0 + vTiles, bottom);
        }
    }

    /** A small cube centred on {@code c}, half size {@code r}, turned by {@code turn}, each face showing [u0,u1]×[v0,v1] and shaded by where it faces. */
    public static void cube(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, Quaternionf turn, float u0, float v0, float u1, float v1,
            int rgb, float alpha) {
        Vector3f[] axes = {turn.transform(new Vector3f(r, 0, 0)), turn.transform(new Vector3f(0, r, 0)), turn.transform(new Vector3f(0, 0, r))};
        for (int f = 0; f < 6; f++) {
            Vector3f n = new Vector3f(axes[f % 3]).mul(f < 3 ? 1 : -1);
            Vector3f a = axes[(f + 1) % 3], b = axes[(f + 2) % 3];
            // Lit from above: the top face brightest, the bottom darkest.
            float shade = .62f + .38f * Mth.clamp(n.y / r * .5f + .5f, 0, 1);
            int color = argb(alpha, lerpColor(0, rgb, shade));
            Vec3 m = c.add(n.x, n.y, n.z), av = new Vec3(a.x, a.y, a.z), bv = new Vec3(b.x, b.y, b.z);
            vertex(pose, buf, m.subtract(av).add(bv), u0, v0, color);
            vertex(pose, buf, m.subtract(av).subtract(bv), u0, v1, color);
            vertex(pose, buf, m.add(av).subtract(bv), u1, v1, color);
            vertex(pose, buf, m.add(av).add(bv), u1, v0, color);
        }
    }

    /** Where the camera is this frame, and its screen axes. */
    public record View(Vec3 cam, Vec3 right, Vec3 up) {
        public static View of(CameraRenderState camera) {
            return new View(camera.pos, new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))),
                    new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))));
        }
    }

    // ---- maths and colour ----------------------------------------------------------------------

    public static float smooth(float lo, float hi, float x) {
        float k = Mth.clamp((x - lo) / (hi - lo), 0, 1);
        return k * k * (3 - 2 * k);
    }

    /** 0 within {@code lo} blocks of the camera, 1 beyond {@code hi}: what comes close fades out instead of filling the view. */
    public static float fade(Vec3 cam, Vec3 p, double lo, double hi) {
        return smooth((float) lo, (float) hi, (float) cam.distanceTo(p));
    }

    public static int argb(float alpha, int rgb) {
        return (int) (Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
    }

    public static int lerpColor(int a, int b, float k) {
        k = Mth.clamp(k, 0, 1);
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** {@code rgb} with its hue turned by {@code turns} (1 is a full turn), saturation and value kept. */
    public static int hue(int rgb, float turns) {
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d <= 0 || max <= 0) return rgb & 0xFFFFFF;
        float h = max == r ? (g - b) / d : max == g ? 2 + (b - r) / d : 4 + (r - g) / d;
        h = h / 6 + turns;
        return Mth.hsvToRgb(h - Mth.floor(h), d / max, max) & 0xFFFFFF;
    }

    // ---- the ground ----------------------------------------------------------------------------

    /** The colour of dust: the map colour of the block under {@code at}, leaning to pale grey so grass does not raise green dust. */
    public static int groundTint(Level level, Vec3 at) {
        BlockPos below = BlockPos.containing(at.x, at.y - .5, at.z);
        BlockState state = level.getBlockState(below);
        int map = state.isAir() ? 0x9A8F80 : state.getMapColor(level, below).col;
        return lerpColor(map, 0xB8AFA2, .45f);
    }

    /** The texture of the block under height {@code groundY} at {@code (x, z)}, for debris; null for air or specially rendered blocks. */
    public static TextureAtlasSprite blockSprite(Level level, double x, double groundY, double z) {
        BlockState state = level.getBlockState(BlockPos.containing(x, groundY - .5, z));
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) return null;
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        return sprite.atlasLocation().getPath().contains("blocks") ? sprite : null;
    }
}
