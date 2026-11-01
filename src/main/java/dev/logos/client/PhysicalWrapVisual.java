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
 * The 物理 wrap (FORM_VFX.md, 魔力缠绕): not mana but raw force, a fighter's aura (Dragon Ball's ki) on the hand.
 * <p>On the item or fist: a white-hot skin of energy hugging the surface that flickers and surges, and spiky tongues
 * of force licking upwards off it, white at the root going faintly gold at the tips — the pillar burst's flame
 * tongues ({@code pillar_burst/flame}), small and short-lived, each born at a new spot and rising as it fades. A
 * translucent warm-grey body goes under the tongues so they keep their shape against a bright sky.
 * <p>In the world: hot specks shooting straight up off the outline, now and then a faint ring of squeezed air
 * pushing out from the hand (the physical charge's pressure ring), and in a swing white streaks of displaced air
 * along the blade's path.
 */
final class PhysicalWrapVisual extends WrapVisual {
    /** White-hot, the physical element's white gold (its projectile and damage), a faint warm gold for the tips, and the warm grey under them by day. */
    private static final int WHITE = 0xFFFFFF, HOT = 0xFFF8EA, GLOW = 0xFFF0C6, TIP = 0xFFD58A, SHADE = 0x9C8A66;

    private static final RenderType SKIN = VfxParts.add("fire/ares_noise"), TONGUE_BODY = VfxParts.blend("pillar_burst/flame"),
            TONGUE = VfxParts.add("pillar_burst/flame"), DOT = VfxParts.add("fire_impact/ember"), RING = VfxParts.add("force/ring"),
            AIR = VfxParts.add("force/streak");

    private final List<Fleck> flecks = new ArrayList<>();

    // ---- clinging to the item ------------------------------------------------------------------

    @Override
    void local(ItemSurface surface, VfxParts.View view, float t, long seed, PoseStack stack, SubmitNodeCollector collector) {
        float surge = surge(seed, t);
        collector.submitCustomGeometry(stack, SKIN, (pose, buf) ->
                SurfaceAuraVisual.shell(pose, buf, surface, t, 2.6f, .16f, .3f / 16, VfxParts.argb(.18f + .2f * surge, HOT)));
        collector.order(1).submitCustomGeometry(stack, TONGUE_BODY, (pose, buf) -> tongues(pose, buf, view, surface, t, seed, surge, true));
        collector.order(2).submitCustomGeometry(stack, TONGUE, (pose, buf) -> tongues(pose, buf, view, surface, t, seed, surge, false));
    }

    /** How hard the aura is pushing, 0–1: a slow swell with a quick flicker on it, so it surges rather than breathes. */
    private static float surge(long seed, float t) {
        int i = Mth.floor(t);
        float f = t - i;
        float flicker = Mth.lerp(f * f * (3 - 2 * f), rand(seed, 91, i), rand(seed, 91, i + 1));
        float swell = .5f + .5f * Mth.sin(t * .21f + rand(seed, 92, 0) * Mth.TWO_PI);
        return Mth.clamp(.35f * swell + .65f * flicker, 0, 1);
    }

    /**
     * The tongues of force: each lives a short cycle (6–9 ticks), born at a random spot on the item, leaping up to
     * full length in a third of it and rising as it fades, flicking to another frame of the flame strip every two
     * ticks. They point up the screen (the camera's up in model space), as an anime aura does. With {@code body}
     * the warm grey under them; otherwise the white-gold glow, white at the root going gold at the tip, and a thin
     * white core.
     */
    private static void tongues(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, ItemSurface s, float t, long seed,
            float surge, boolean body) {
        float len = s.length();
        boolean fist = len < .5f;
        int n = fist ? 8 : 10;
        // A fist's tongues stand taller than it; an item's stay short beside the blade.
        float reach = fist ? .32f : Math.max(.22f, .3f * len);
        Vec3 up = view.up();
        for (int k = 0; k < n; k++) {
            float period = 6 + 3 * rand(seed, k, -1);
            float a = t / period + rand(seed, k, -2);
            int cycle = Mth.floor(a);
            float age = a - cycle;
            float along = fist ? rand(seed, k, cycle) : .18f + .82f * rand(seed, k, cycle);
            float side = rand(seed, k ^ 0x55, cycle) * 2 - 1;
            Vec3 at = new Vec3(s.along(along, new Vector3f())), toCam = view.cam().subtract(at).normalize();
            // Out to either side of the silhouette as the camera sees it and a little towards the camera, so the tongues are not hidden in the arm or behind the blade.
            Vec3 root = at.add(view.right().scale(side * s.radius * 1.6f)).add(toCam.scale(s.radius * 1.2f));
            float grow = VfxParts.smooth(0, .3f, age), fade = 1 - VfxParts.smooth(.5f, 1, age);
            float length = reach * (.65f + .5f * rand(seed, k ^ 0xAA, cycle)) * (.45f + .55f * grow) * (.75f + .5f * surge);
            Vec3 tail = root.add(up.scale(length * (.3f * age - .22f)));
            Vec3 head = tail.add(up.scale(length));
            float width = length * (fist ? .55f : .45f);
            int frame = (int) (rand(seed, k ^ 0x33, cycle) * 4 + t / 2) % 4;
            float v0 = frame / 4f, v1 = v0 + .25f, alpha = grow * fade;
            if (alpha <= 0) continue;
            if (body) {
                strip(pose, buf, view, tail, head, width, v0, v1, VfxParts.argb(alpha * .45f, SHADE), VfxParts.argb(alpha * .3f, SHADE));
                continue;
            }
            strip(pose, buf, view, tail, head, width, v0, v1, VfxParts.argb(alpha * .85f, GLOW), VfxParts.argb(alpha * .7f, TIP));
            Vec3 coreHead = tail.add(up.scale(length * .7f));
            strip(pose, buf, view, tail, coreHead, width * .4f, v0, v1, VfxParts.argb(alpha * .8f, WHITE), VfxParts.argb(alpha * .35f, WHITE));
        }
    }

    /** A camera-facing strip from {@code tail} to {@code head} showing rows [v0,v1] of the texture, its colour going from {@code from} at the tail to {@code to} at the head. */
    private static void strip(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 tail, Vec3 head, float w,
            float v0, float v1, int from, int to) {
        Vec3 side = head.subtract(tail).cross(head.add(tail).scale(.5).subtract(view.cam()));
        if (side.lengthSqr() < 1e-10) return;
        side = side.normalize().scale(w * .5);
        VfxParts.vertex(pose, buf, tail.add(side), 0, v0, from);
        VfxParts.vertex(pose, buf, tail.subtract(side), 0, v1, from);
        VfxParts.vertex(pose, buf, head.subtract(side), 1, v1, to);
        VfxParts.vertex(pose, buf, head.add(side), 1, v0, to);
    }

    private static float rand(long seed, int k, int cycle) {
        long x = seed * 0x9E3779B97F4A7C15L + k * 0xC2B2AE3D27D4EB4FL + cycle * 0x165667B19E3779F9L;
        x = (x ^ x >>> 31) * 0xBF58476D1CE4E5B9L;
        return ((x ^ x >>> 29) >>> 40) / (float) (1L << 24);
    }

    // ---- into the world ------------------------------------------------------------------------

    @Override
    void emit(SurfaceAuras.Emitter e, RandomSource r) {
        float scale = e.scale();
        if (scale < 1e-3f) return;
        ItemSurface s = e.surface;
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        if (r.nextFloat() < .4f) {
            s.sample(r, true, pos, normal);
            Vec3 n = e.direction(normal).normalize();
            Vec3 v = n.scale(.004 * scale).add(0, (.05 + .03 * r.nextFloat()) * Math.max(.5f, scale), 0);
            flecks.add(new Fleck(Fleck.Kind.SPECK, e.world(pos, r.nextFloat()), v, 6 + r.nextInt(5), Math.max(.01f, .02f * scale)));
        }
        if (r.nextInt(24) == 0) {
            flecks.add(new Fleck(Fleck.Kind.RING, e.world(s.center, 1), Vec3.ZERO, 8, Math.max(.3f, .9f * scale)));
        }
        // Displaced air in a swing, by distance moved: a streak along the path each point of the blade has just cut.
        float swing = (float) Math.min(8, e.moved() / scale * 5);
        int count = (int) swing + (r.nextFloat() < swing - (int) swing ? 1 : 0);
        for (int i = 0; i < count; i++) {
            s.along(.25f + .75f * r.nextFloat(), pos);
            float k = r.nextFloat();
            Vec3 at = e.world(pos, k), before = e.world(pos, Math.max(0, k - .5f));
            flecks.add(new Fleck(Fleck.Kind.GUST, at, at.subtract(before), 4 + r.nextInt(2), Math.max(.03f, .08f * scale)));
        }
    }

    @Override
    void tick() {
        flecks.removeIf(f -> !f.tick());
    }

    @Override
    void world(VfxParts.View view, float pt, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        OrderedSubmitNodeCollector to = layers.apply(1);
        to.submitCustomGeometry(stack, DOT, (pose, buf) -> {
            for (Fleck f : flecks) {
                if (f.kind != Fleck.Kind.SPECK) continue;
                float k = f.k(pt);
                Vec3 p = f.at(pt), v = new Vec3(f.vx, f.vy, f.vz);
                float a = (1 - VfxParts.smooth(.4f, 1, k)) * VfxParts.fade(view.cam(), p, .8, 1.8);
                Vec3 dir = v.lengthSqr() < 1e-10 ? new Vec3(0, 1, 0) : v.normalize();
                double stretch = Math.max(f.size * 2, v.length() * 1.4);
                VfxParts.streak(pose, buf, view, p.subtract(dir.scale(stretch)), p.add(dir.scale(f.size * .5)), f.size,
                        .5f, 0, .5f, 1, VfxParts.argb(a, VfxParts.lerpColor(WHITE, TIP, k)));
            }
        });
        to.submitCustomGeometry(stack, RING, (pose, buf) -> {
            for (Fleck f : flecks) {
                if (f.kind != Fleck.Kind.RING) continue;
                float k = f.k(pt);
                Vec3 p = f.at(pt);
                float grow = 1 - (1 - k) * (1 - k);
                float a = .22f * (1 - k) * VfxParts.fade(view.cam(), p, .5, 1.4);
                VfxParts.sprite(pose, buf, view, p, f.size * (.35f + .65f * grow), 0, VfxParts.argb(a, GLOW));
            }
        });
        to.submitCustomGeometry(stack, AIR, (pose, buf) -> {
            for (Fleck f : flecks) {
                if (f.kind != Fleck.Kind.GUST) continue;
                float k = f.k(pt);
                Vec3 p = f.at(pt), v = new Vec3(f.vx, f.vy, f.vz);
                float a = .3f * (1 - k) * (1 - k) * VfxParts.fade(view.cam(), p, .3, .9);
                VfxParts.streak(pose, buf, view, p.subtract(v.scale(1.2)), p, f.size * (1 - .5f * k), VfxParts.argb(a, WHITE));
            }
        });
    }

    @Override
    int particles() { return flecks.size(); }

    @Override
    void clear() { flecks.clear(); }

    /** A particle left in the world: a SPECK shooting up, a RING of squeezed air pushing out, a GUST of air cut in a swing. */
    private static final class Fleck {
        enum Kind { SPECK, RING, GUST }

        final Kind kind;
        double x, y, z, px, py, pz, vx, vy, vz;
        final int life;
        final float size;
        int age;

        Fleck(Kind kind, Vec3 at, Vec3 velocity, int life, float size) {
            this.kind = kind;
            x = px = at.x; y = py = at.y; z = pz = at.z;
            vx = velocity.x; vy = velocity.y; vz = velocity.z;
            this.life = life;
            this.size = size;
        }

        boolean tick() {
            px = x; py = y; pz = z;
            // A gust keeps its velocity only as its direction and length; it does not move.
            if (kind == Kind.SPECK) {
                x += vx; y += vy; z += vz;
                vx *= .9; vy *= .92; vz *= .9;
            }
            return ++age < life;
        }

        Vec3 at(float pt) { return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z)); }

        float k(float pt) { return Mth.clamp((age + pt) / life, 0, 1); }
    }
}
