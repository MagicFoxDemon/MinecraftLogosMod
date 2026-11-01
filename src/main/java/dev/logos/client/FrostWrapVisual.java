package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The 寒霜 wrap (FORM_VFX.md, 魔力缠绕), in the frost element's own language: the item is iced over, still patches
 * of white rime frozen onto it (the frost ray's rime noise) under a faint cold glow laced with the crystal-cell
 * ridges of frost's ice texture (the frost field's line fill), a crust of small ice crystals standing out of its
 * outline (the projectile's winter shards), and now and then a glint catching on the rime (the impact's star). Cold
 * mist breathes off it and sinks (the ray's frost mist), a few ice motes twinkle as they drift down, and a swing
 * shakes a few shards of ice loose by distance moved. All the textures are the frost element's existing ones.
 */
final class FrostWrapVisual extends WrapVisual {
    /** The frost family's ice blues (FrostRayVisual, FrostDamageVisual): the glow, the pale ice, near-white highlights, the rime, the mist. */
    private static final int GLOW = 0x5CB4FF, ICE = 0xB8E4FF, WHITE = 0xE8F8FF, RIME_WHITE = 0xE4F4FF, MIST = 0xC4DAF2, MIST_DEEP = 0x9CB6D8;

    private static final RenderType FROST_CRUST = VfxParts.blend("frost_ray/noise"), COLD = VfxParts.add("frost/winter_trail"), SHARDS = VfxParts.add("frost/winter_shards"),
            STAR = VfxParts.add("frost_impact/star"), MIST_TEX = VfxParts.blend("frost_ray/mist"), GLINTS = VfxParts.add("frost_ray/glints"),
            SHARDS_BODY = VfxParts.blend("frost/winter_shards");

    private final List<Bit> bits = new ArrayList<>();

    // ---- clinging to the item ------------------------------------------------------------------

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        // The rime first, translucent (so it whitens the item by day), then the cold glow and the crystals over it.
        collector.order(1).submitCustomGeometry(stack, FROST_CRUST, (pose, buf) -> rime(pose, buf, surface, .5f / 16, VfxParts.argb(.14f, RIME_WHITE)));
        float breathe = .1f + .02f * Mth.sin(t * .12f);
        OrderedSubmitNodeCollector over = collector.order(2);
        over.submitCustomGeometry(stack, COLD, (pose, buf) ->
                SurfaceAuraVisual.shell(pose, buf, surface, t, 2.6f, .004f, .35f / 16, VfxParts.argb(breathe, GLOW)));
        over.submitCustomGeometry(stack, SHARDS, (pose, buf) -> crust(pose, buf, view, surface, t, seed));
        over.submitCustomGeometry(stack, STAR, (pose, buf) -> glints(pose, buf, view, surface, t, seed));
    }

    /**
     * Patches of rime frozen onto the surface (the frost ray's rime noise), still: each cell of the surface just
     * outside it, not enlarged, so the translucent patches do not pile up where cells would overlap.
     */
    private static void rime(PoseStack.Pose pose, VertexConsumer buf, ItemSurface surface, float offset, int color) {
        Vector3f axis = surface.axis, across = surface.across;
        float tiles = 4.5f;
        surface.forEachCell((cx, cy, cz, ax, ay, az, bx, by, bz, nx, ny, nz) -> {
            float ox = cx + nx * offset, oy = cy + ny * offset, oz = cz + nz * offset;
            for (int i = 0; i < 4; i++) {
                float sa = i == 0 || i == 1 ? -1 : 1, sb = i == 0 || i == 3 ? 1 : -1;
                float x = ox + sa * ax + sb * bx, y = oy + sa * ay + sb * by, z = oz + sa * az + sb * bz;
                // The out-of-plane direction folds in too, so the outline's side strips get their own patches.
                float u = (x * axis.x + y * axis.y + z * axis.z) * tiles + (x * nx + y * ny + z * nz) * 1.7f;
                float v = (x * across.x + y * across.y + z * across.z) * tiles;
                VfxParts.vertex(pose, buf, new Vec3(x, y, z), u, v, color);
            }
        });
    }

    /**
     * Ice crystals standing out of the outline (the whole surface for a fist, which has none): each grows out slowly,
     * stays a long while and thaws away, then forms again somewhere else. Deterministic from the time.
     */
    private static void crust(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float t, long seed) {
        float len = surface.length(), k = Mth.clamp(len / 1.1f, .6f, 1);
        int n = Math.round(Mth.clamp(len * 16, 5, 18));
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        for (int i = 0; i < n; i++) {
            float period = 140 + (i * 37 % 60);
            float a = t / period + i * .618f;
            int cycle = Mth.floor(a);
            float age = a - cycle;
            RandomSource r = RandomSource.create(seed * 17 + i * 0x9E3779B9L + cycle * 0x632BE5ABL);
            surface.sample(r, true, pos, normal);
            float grow = VfxParts.smooth(0, .2f, age) * (1 - VfxParts.smooth(.8f, 1, age));
            if (grow <= .01f) continue;
            float h = (1.1f + .9f * r.nextFloat()) / 16 * k * (.5f + .5f * grow), w = (.75f + .3f * r.nextFloat()) / 16 * k;
            // Leaning a little off the normal, as frost needles do.
            Vector3f lean = new Vector3f(r.nextFloat() - .5f, r.nextFloat() - .5f, r.nextFloat() - .5f).mul(.7f).add(normal).normalize();
            Vec3 base = new Vec3(pos.fma(-.2f / 16, normal)), tip = base.add(lean.x * h, lean.y * h, lean.z * h);
            float u0 = r.nextBoolean() ? 0 : .5f;
            needle(pose, buf, view, base, tip, w, u0, VfxParts.argb(.75f * grow, i % 3 == 0 ? WHITE : ICE));
        }
    }

    /** One of the two upright shards in the texture, stood from {@code base} to {@code tip}, {@code w} wide and turned to the camera. */
    private static void needle(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 base, Vec3 tip, float w, float u0, int color) {
        Vec3 along = tip.subtract(base);
        Vec3 side = along.cross(base.add(tip).scale(.5).subtract(view.cam()));
        if (side.lengthSqr() < 1e-12) return;
        side = side.normalize().scale(w * .5);
        VfxParts.vertex(pose, buf, base.subtract(side), u0, 1, color);
        VfxParts.vertex(pose, buf, base.add(side), u0 + .5f, 1, color);
        VfxParts.vertex(pose, buf, tip.add(side), u0 + .5f, 0, color);
        VfxParts.vertex(pose, buf, tip.subtract(side), u0, 0, color);
    }

    /** Now and then a glint catches on the rime: a sharp star twinkling up and gone, a few at a time. */
    private static void glints(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float t, long seed) {
        float len = surface.length(), k = Mth.clamp(len / 1.1f, .45f, 1);
        int n = len > .6f ? 6 : 3;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        for (int i = 0; i < n; i++) {
            float period = 34 + (i * 13 % 17);
            float a = t / period + i * .37f;
            int cycle = Mth.floor(a);
            float age = a - cycle;
            if (age > .45f) continue;
            float s = Mth.sin(Mth.PI * age / .45f), twinkle = s * s * s;
            surface.sample(RandomSource.create(seed * 41 + i * 0x2545F491L + cycle * 0x9E3779B9L), true, pos, normal);
            pos.fma(.4f / 16, normal);
            VfxParts.sprite(pose, buf, view, new Vec3(pos), 4.2f / 16 * k * (.6f + .4f * s), .3f * i, VfxParts.argb(.9f * twinkle, WHITE));
        }
    }

    // ---- into the world ------------------------------------------------------------------------

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource r) {
        float scale = e.scale();
        if (scale < 1e-3f) return;
        ItemSurface s = e.surface;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        if (r.nextFloat() < .5f) {
            s.sample(r, false, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.004 * scale).add(0, -.006 * scale, 0).add(jitter(r, .002 * scale));
            bits.add(new Bit(Bit.Kind.MIST, e.world(pos, r.nextFloat()), v, 34 + r.nextInt(16), Math.max(.06f, .22f * scale), r));
        }
        if (r.nextFloat() < .4f) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.006 * scale).add(jitter(r, .003 * scale));
            bits.add(new Bit(Bit.Kind.MOTE, e.world(pos, r.nextFloat()), v, 26 + r.nextInt(18), Math.max(.012f, .03f * scale), r));
        }
        float swing = (float) Math.min(3, e.moved() / scale * 2.5);
        int swingCount = (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        for (int i = 0; i < swingCount; i++) {
            s.along(.25f + .75f * r.nextFloat(), pos);
            bits.add(new Bit(Bit.Kind.SHARD, e.world(pos, r.nextFloat()), jitter(r, .012 * scale).add(0, .01 * scale, 0), 14 + r.nextInt(8),
                    Math.max(.014f, .028f * scale) * (.8f + .4f * r.nextFloat()), r));
        }
    }

    private static Vec3 jitter(RandomSource r, double amount) {
        return new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(amount);
    }

    @Override
    void tick() {
        bits.removeIf(b -> !b.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        if (bits.isEmpty()) return;
        // The mist and the shards' translucent bodies first, then the glows over them.
        layers.apply(0).submitCustomGeometry(stack, MIST_TEX, (pose, buf) -> {
            for (Bit b : bits) {
                if (b.kind != Bit.Kind.MIST) continue;
                float k = b.k(pt);
                Vec3 p = b.at(pt);
                float a = VfxParts.smooth(0, .25f, k) * (1 - VfxParts.smooth(.45f, 1, k)) * VfxParts.fade(view.cam(), p, .35, 1.0) * .42f;
                float r = b.size * (.55f + .45f * Mth.sqrt(k));
                int col = b.frame % 2, row = b.frame / 2 % 2;
                VfxParts.sprite(pose, buf, view, p, r, r, b.roll, col * .5f, row * .5f, col * .5f + .5f, row * .5f + .5f,
                        VfxParts.argb(a, VfxParts.lerpColor(MIST, MIST_DEEP, k)));
            }
        });
        layers.apply(0).submitCustomGeometry(stack, SHARDS_BODY, (pose, buf) -> {
            for (Bit b : bits) if (b.kind == Bit.Kind.SHARD) shard(pose, buf, view, b, pt, .5f, MIST);
        });
        OrderedSubmitNodeCollector to = layers.apply(1);
        to.submitCustomGeometry(stack, SHARDS, (pose, buf) -> {
            for (Bit b : bits) if (b.kind == Bit.Kind.SHARD) shard(pose, buf, view, b, pt, .8f, ICE);
        });
        to.submitCustomGeometry(stack, GLINTS, (pose, buf) -> {
            for (Bit b : bits) {
                if (b.kind != Bit.Kind.MOTE) continue;
                float k = b.k(pt);
                Vec3 p = b.at(pt);
                float tw = .55f + .45f * Mth.sin((b.age + pt) * .5f + b.roll * 3);
                float a = VfxParts.smooth(0, .1f, k) * (1 - VfxParts.smooth(.6f, 1, k)) * tw * VfxParts.fade(view.cam(), p, .3, .9);
                int f = b.frame % 9;
                float u = f % 3 / 3f, v = f / 3 / 3f;
                VfxParts.sprite(pose, buf, view, p, b.size * 2.4f, b.size * 2.4f, 0, u, v, u + 1 / 3f, v + 1 / 3f, VfxParts.argb(a, WHITE));
            }
        });
    }

    /** A shard of ice shaken loose in a swing, tumbling as it falls and shrinking away. */
    private static void shard(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Bit b, float pt, float alpha, int rgb) {
        float k = b.k(pt);
        Vec3 p = b.at(pt);
        float a = VfxParts.smooth(0, .08f, k) * (1 - VfxParts.smooth(.6f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9) * alpha;
        float r = b.size * (1 - .4f * k);
        float u0 = b.frame % 2 * .5f;
        VfxParts.sprite(pose, buf, view, p, r * .6f, r, b.roll + b.spin * (b.age + pt), u0, 0, u0 + .5f, 1, VfxParts.argb(a, rgb));
    }

    @Override
    int particles() { return bits.size(); }

    @Override
    void clear() { bits.clear(); }

    /** A particle let off into the world: mist sinking, an ice mote drifting down, a shard falling. */
    private static final class Bit {
        enum Kind { MIST, MOTE, SHARD }

        final Kind kind;
        double x, y, z, px, py, pz, vx, vy, vz;
        final int life, frame;
        final float size, roll, spin;
        int age;

        Bit(Kind kind, Vec3 at, Vec3 velocity, int life, float size, RandomSource r) {
            this.kind = kind;
            x = px = at.x; y = py = at.y; z = pz = at.z;
            vx = velocity.x; vy = velocity.y; vz = velocity.z;
            this.life = life;
            this.size = size;
            frame = r.nextInt(36);
            roll = r.nextFloat() * Mth.TWO_PI;
            spin = (r.nextFloat() - .5f) * (kind == Kind.SHARD ? .5f : .03f);
        }

        boolean tick() {
            px = x; py = y; pz = z;
            x += vx; y += vy; z += vz;
            double drag = switch (kind) { case MIST -> .94; case MOTE -> .92; case SHARD -> .9; };
            vx *= drag; vy *= drag; vz *= drag;
            // Cold air sinks: the mist settles downward, the motes drift down, the shards fall.
            vy -= switch (kind) { case MIST -> .00025 * size / .12; case MOTE -> .0003; case SHARD -> .003; };
            return ++age < life;
        }

        Vec3 at(float pt) { return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z)); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }
}
