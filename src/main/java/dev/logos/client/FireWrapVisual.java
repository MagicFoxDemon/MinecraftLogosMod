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
 * The 火焰 wrap (FORM_VFX.md, 魔力缠绕), in the fire element's own language rather than the surface aura's: the item
 * runs hot, a flowing heat glow on its surface (the fire projectile's Ares turbulence) with coals flaring and dying
 * along its outline; small tongues of flame lick up off the outline and burn out (the fire ray's flame flipbook,
 * a translucent body under the glow so they keep their shape against a day sky), and a few embers rise and cool
 * (the impact's ember, in the ray's ember gradient). A swing throws a few puffs of flame off the blade by distance
 * moved. All textures are the fire element's existing ones.
 */
final class FireWrapVisual extends WrapVisual {
    /** The fire flames' gradient (FireRayVisual): yellow-gold, orange-gold, orange, a deep red. */
    private static final int GOLD = 0xFFD27A, AMBER = 0xFFA338, ORANGE = 0xFF6A14, RED = 0xC82A06;
    /** The ray's embers: white-gold to red. */
    private static final int EMBER_HOT = 0xFFF0C0, EMBER = 0xFFB347, EMBER_COOL = 0xFF3A0A;
    /** The flame body drawn translucent under the glow. */
    private static final int BODY = 0xD8400F;

    private static final RenderType HEAT = VfxParts.add("fire/ares_noise"), COAL = VfxParts.add("fire_impact/ember"),
            FLAMES = VfxParts.add("fire_ray/flames"), FLAMES_BODY = VfxParts.blend("fire_ray/flames"), EMBER_TEX = VfxParts.add("fire_impact/ember");

    /** The ray's flame flipbook: 4×4 frames, a ball of flame burning out to wisps. */
    private static final int COLS = 4, FRAMES = 16;

    private final List<SurfaceAuraVisual.Mote> motes = new ArrayList<>();

    // ---- clinging to the item ------------------------------------------------------------------

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        float pulse = .26f + .05f * Mth.sin(t * .7f) + .03f * Mth.sin(t * 1.9f + 1);
        collector.submitCustomGeometry(stack, HEAT, (pose, buf) ->
                SurfaceAuraVisual.shell(pose, buf, surface, t, 1.8f, .07f, .4f / 16, VfxParts.argb(pulse, ORANGE)));
        collector.submitCustomGeometry(stack, COAL, (pose, buf) -> coals(pose, buf, view, surface, t, seed));
    }

    /**
     * Coals along the outline (the whole surface for a fist, which has none): each flares up at a random place,
     * flickers and dies down, then comes back somewhere else; a hot gold heart in a wider orange glow. Deterministic
     * from the time, so nothing needs keeping.
     */
    private static void coals(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface surface, float t, long seed) {
        float len = surface.length(), k = Mth.clamp(len / 1.1f, .35f, 1);
        int n = Math.round(Mth.clamp(len * 18, 5, 22));
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        for (int i = 0; i < n; i++) {
            float period = 16 + (i * 7 % 9);
            float a = t / period + i * .618f;
            int cycle = Mth.floor(a);
            float age = a - cycle;
            surface.sample(RandomSource.create(seed * 31 + i * 0x9E3779B9L + cycle * 0x632BE5ABL), true, pos, normal);
            pos.fma(.3f / 16, normal);
            float flicker = .75f + .25f * Mth.sin(t * 1.3f + i * 2.1f);
            float life = Mth.sin(Mth.PI * age) * flicker;
            Vec3 c = new Vec3(pos);
            VfxParts.sprite(pose, buf, view, c, 2.4f / 16 * k, 0, VfxParts.argb(.32f * life, ORANGE));
            VfxParts.sprite(pose, buf, view, c, .9f / 16 * k, 0, VfxParts.argb(.85f * life, VfxParts.lerpColor(EMBER_HOT, GOLD, age)));
        }
    }

    // ---- into the world ------------------------------------------------------------------------

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource r) {
        float scale = e.scale();
        if (scale < 1e-3f) return;
        ItemSurface s = e.surface;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        float swing = (float) Math.min(6, e.moved() / scale * 4);
        int swingCount = (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        if (r.nextFloat() < .6f) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            add(SurfaceAuraVisual.Mote.Kind.TONGUE, e.world(pos, r.nextFloat()), n.scale(.003 * scale).add(0, .01 * scale, 0),
                    9 + r.nextInt(6), Math.max(.12f, scale * .28f) * (.9f + .3f * r.nextFloat()), r);
        }
        if (r.nextFloat() < .55f) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.01 * scale).add(0, .016 * scale, 0).add(jitter(r, .007 * scale));
            add(SurfaceAuraVisual.Mote.Kind.EMBER, e.world(pos, r.nextFloat()), v, 18 + r.nextInt(16), Math.max(.011f, .02f * scale), r);
        }
        for (int i = 0; i < swingCount; i++) {
            s.along(.2f + .8f * r.nextFloat(), pos);
            add(SurfaceAuraVisual.Mote.Kind.FLAME, e.world(pos, r.nextFloat()), new Vec3(0, .008 * scale, 0).add(jitter(r, .005 * scale)),
                    8 + r.nextInt(6), scale * (.3f + .1f * r.nextFloat()), r);
        }
    }

    private void add(SurfaceAuraVisual.Mote.Kind kind, Vec3 at, Vec3 v, int life, float size, RandomSource r) {
        motes.add(new SurfaceAuraVisual.Mote(kind, at, v, life, size, 0, r));
    }

    private static Vec3 jitter(RandomSource r, double amount) {
        return new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(amount);
    }

    @Override
    void tick() {
        motes.removeIf(m -> !m.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        // The flames' translucent body first, then the same frames as additive glow over it.
        layers.apply(0).submitCustomGeometry(stack, FLAMES_BODY, (pose, buf) -> {
            for (SurfaceAuraVisual.Mote m : motes) flame(pose, buf, view, m, pt, true);
        });
        OrderedSubmitNodeCollector to = layers.apply(1);
        to.submitCustomGeometry(stack, FLAMES, (pose, buf) -> {
            for (SurfaceAuraVisual.Mote m : motes) flame(pose, buf, view, m, pt, false);
        });
        to.submitCustomGeometry(stack, EMBER_TEX, (pose, buf) -> {
            for (SurfaceAuraVisual.Mote m : motes) {
                if (m.kind != SurfaceAuraVisual.Mote.Kind.EMBER) continue;
                float k = m.k(pt);
                Vec3 p = m.at(pt), v = new Vec3(m.vx, m.vy, m.vz);
                int rgb = k < .35f ? VfxParts.lerpColor(EMBER_HOT, EMBER, k / .35f) : VfxParts.lerpColor(EMBER, EMBER_COOL, (k - .35f) / .65f);
                float a = VfxParts.smooth(0, .08f, k) * (1 - VfxParts.smooth(.55f, 1, k)) * VfxParts.fade(view.cam(), p, .3, .9);
                double stretch = Math.max(m.size * 1.2, v.length() * 2.2);
                Vec3 dir = v.lengthSqr() < 1e-10 ? new Vec3(0, 1, 0) : v.normalize();
                VfxParts.streak(pose, buf, view, p.subtract(dir.scale(stretch)), p.add(dir.scale(m.size * .5)), m.size * (1 - .4f * k), VfxParts.argb(a, rgb));
            }
        });
    }

    /**
     * A TONGUE licks up off the outline, its base held where it left and its tip rising, burning through the
     * flipbook from a ball to wisps; a FLAME is a puff thrown in a swing, turning as it burns out. Gold cooling to
     * red; {@code body} draws the darker translucent shape under the glow.
     */
    private static void flame(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, SurfaceAuraVisual.Mote m, float pt, boolean body) {
        if (m.kind != SurfaceAuraVisual.Mote.Kind.TONGUE && m.kind != SurfaceAuraVisual.Mote.Kind.FLAME) return;
        float k = m.k(pt);
        Vec3 p = m.at(pt);
        int f = Math.min(FRAMES - 1, (int) (k * FRAMES));
        float u0 = (f % COLS) / (float) COLS, v0 = (f / COLS) / (float) COLS, u1 = u0 + 1f / COLS, v1 = v0 + 1f / COLS;
        int rgb = body ? BODY : k < .3f ? VfxParts.lerpColor(GOLD, AMBER, k / .3f) : k < .65f ? VfxParts.lerpColor(AMBER, ORANGE, (k - .3f) / .35f)
                : VfxParts.lerpColor(ORANGE, RED, (k - .65f) / .35f);
        float near = VfxParts.fade(view.cam(), p, .3, .9);
        if (m.kind == SurfaceAuraVisual.Mote.Kind.TONGUE) {
            float hgt = m.size * (.55f + .45f * Mth.sqrt(k));
            float a = VfxParts.smooth(0, .12f, k) * (1 - VfxParts.smooth(.55f, 1, k)) * near * (body ? .5f : .95f);
            VfxParts.sprite(pose, buf, view, p.add(view.up().scale(hgt * .25)), hgt * .34f, hgt * .5f, 0, u0, v0, u1, v1, VfxParts.argb(a, rgb));
        } else {
            float grow = .6f + .4f * Mth.sqrt(k);
            float a = VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.45f, 1, k)) * near * (body ? .45f : .9f);
            float r = m.size * grow / 2;
            VfxParts.sprite(pose, buf, view, p, r, r, m.roll, u0, v0, u1, v1, VfxParts.argb(a, rgb));
        }
    }

    @Override
    int particles() { return motes.size(); }

    @Override
    void clear() { motes.clear(); }
}
