package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Vector3f;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The 虚空 wrap (FORM_VFX.md, 魔力缠绕): the wrapped item or fist turns into a window onto the void. Its surface is
 * laid over with the open void of the colossal void charge's closing sphere ({@code void_charge_shell.fsh}, read in
 * place): the end portal's stars projected in screen space, so they stay put on the screen while the item moves and
 * the item looks cut out of the night sky, over a dark violet nebula, rimmed in violet where the surface turns away.
 * It is see-through, thicker in a slow wave running up to the tip, so the weapon's own shape and colours still show.
 * The one accent is the void damage's motes: pale specks drawn in from around the hand, falling into the surface and
 * gone.
 */
final class VoidWrapVisual extends WrapVisual {
    private static final int VIOLET = 0x9A28FF, PALE = 0xD5A8FF;
    /** The window's rim: the void violet darkened, since a flat blade seen edge-on is all rim. */
    private static final int RIM = 0x6A20C0;
    /** How far the window sits off the item's faces (model units), clear of them without a visible gap. */
    private static final float OFFSET = .25f / 16;

    /**
     * The void charge's see-through shell material as it is (vertex {@code core/void_projectile} for the view
     * position its rim needs), translucent and not writing depth, so what lies behind the window still draws.
     */
    private static final RenderType WINDOW = window();
    private static final RenderType MOTE = VfxParts.add("void_damage/mote");

    private final List<Speck> specks = new ArrayList<>();

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        // After the item (order 0), so the window lies on it and not under it.
        collector.order(1).submitCustomGeometry(stack, WINDOW, (pose, buf) -> window(pose, buf, surface, t));
    }

    /** Every cell of the surface, pushed out a little, its alpha a slow wave running up the item. */
    private static void window(PoseStack.Pose pose, VertexConsumer buf, ItemSurface surface, float t) {
        Vector3f axis = surface.axis, across = surface.across, out = surface.out, c = surface.center;
        float half = surface.half;
        // A bare fist is a few pixels across: its window is thicker so it still reads at a distance; a blade's is thinner so the blade shows.
        float small = 1 - VfxParts.smooth(.35f, .8f, surface.length()), base = .4f + .2f * small, swell = .38f - .08f * small;
        surface.forEachCell((cx, cy, cz, ax, ay, az, bx, by, bz, nx, ny, nz) -> {
            float ox = cx + nx * OFFSET, oy = cy + ny * OFFSET, oz = cz + nz * OFFSET;
            for (int i = 0; i < 4; i++) {
                float sa = i == 0 || i == 1 ? -1 : 1, sb = i == 0 || i == 3 ? 1 : -1;
                float x = ox + sa * ax + sb * bx, y = oy + sa * ay + sb * by, z = oz + sa * az + sb * bz;
                float along = (x - c.x) * axis.x + (y - c.y) * axis.y + (z - c.z) * axis.z, s = (along / half + 1) / 2;
                float wave = .5f + .5f * Mth.sin(Mth.TWO_PI * (s * 1.1f - t * .012f));
                float alpha = base + swell * wave;
                // The shell's surface noise, laid on in model space so it sits still on the item and flows by itself.
                float u = along * 1.2f, v = ((x - c.x) * across.x + (y - c.y) * across.y + (z - c.z) * across.z) * 1.2f
                        + ((x - c.x) * out.x + (y - c.y) * out.y + (z - c.z) * out.z) * .6f;
                VfxParts.vertex(pose, buf, new Vec3(x, y, z), u, v, VfxParts.argb(alpha, RIM));
            }
        });
    }

    // ---- specks falling in ---------------------------------------------------------------------

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource r) {
        float scale = e.scale();
        if (scale < 1e-3f || r.nextFloat() > .55f) return;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        e.surface.sample(r, false, pos, normal);
        Vec3 n = e.direction(normal).normalize();
        Vec3 jitter = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(.5);
        Vec3 from = n.add(jitter).normalize().scale(Math.max(.12, scale * (.3 + .25 * r.nextFloat())));
        specks.add(new Speck(e, pos, from, 12 + r.nextInt(8), Math.max(.014f, .03f * scale)));
    }

    @Override
    void tick() {
        specks.removeIf(s -> !s.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        if (specks.isEmpty()) return;
        layers.apply(1).submitCustomGeometry(stack, MOTE, (pose, buf) -> {
            for (Speck s : specks) {
                float k = s.k(pt);
                Vec3 p = s.at(pt);
                float a = VfxParts.smooth(0, .3f, k) * (1 - VfxParts.smooth(.8f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
                if (a <= 0) continue;
                // Stretched along the fall, the tail outwards, shrinking as it sinks in.
                float size = s.size * (1 - .5f * k);
                Vec3 tail = p.add(s.from.normalize().scale(size * 3.5));
                VfxParts.streak(pose, buf, view, tail, p, size * 2, VfxParts.argb(a * .9f, PALE));
                VfxParts.sprite(pose, buf, view, p, size * 1.6f, 0, VfxParts.argb(a * .25f * VfxParts.fade(view.cam(), p, .6, 1.4), VIOLET));
            }
        });
    }

    @Override
    int particles() { return specks.size(); }

    @Override
    void clear() { specks.clear(); }

    /**
     * A speck falling into a point of the surface: it starts {@code from} out from it and closes in, faster at the
     * end, following the point as the item moves.
     */
    private static final class Speck {
        final SurfaceAuras.Emitter emitter;
        final Vector3f local;
        final Vec3 from;
        final int life;
        final float size;
        Vec3 prev, now;
        int age;

        Speck(SurfaceAuras.Emitter emitter, Vector3f local, Vec3 from, int life, float size) {
            this.emitter = emitter;
            this.local = new Vector3f(local);
            this.from = from;
            this.life = life;
            this.size = size;
            now = prev = place();
        }

        private Vec3 place() {
            float k = Math.min(1, age / (float) life);
            return emitter.world(local, 1).add(from.scale((1 - k) * (1 - k)));
        }

        boolean tick() {
            prev = now;
            age++;
            now = place();
            return age < life;
        }

        Vec3 at(float pt) { return prev.lerp(now, pt); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }

    private static RenderType window() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_wrap_window")).withVertexShader(id("core/void_projectile"))
                .withFragmentShader(id("core/void_charge_shell")).withCull(false)
                .withSampler("StarTex").withSampler("AuraTex")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_void_wrap_window", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/void_beam/solid.png"))
                .withTexture("StarTex", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"))
                .withTexture("AuraTex", id("textures/vfx/void_beam/aura.png")).sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
