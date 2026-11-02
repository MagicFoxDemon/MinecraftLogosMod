package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A fire vein that lingers (see VEIN_VFX.md): crystals of fire thrust up all along where it crawled, molten swirls
 * flowing up through them and their edges burning gold, after the reference the maintainer picked (Piloto's
 * {@code Impale_Fire}). At their feet the ground glows, flames lick up between them and embers drift off; each
 * big crystal throws a puff of flame as it breaks out.
 */
final class FireLingeringVeinVisual extends CrystalVeinVisual {
    /** The fire flames' gradient (FireRayVisual): yellow-gold, orange-gold, orange, a deep red. */
    private static final int GOLD = 0xFFD27A, AMBER = 0xFFA338, ORANGE = 0xFF6A14, RED = 0xC82A06;
    /** The flame body drawn translucent under the glow. */
    private static final int BODY = 0xD8400F;
    private static final RenderType FLAMES = VfxParts.add("fire_ray/flames"), FLAMES_BODY = VfxParts.blend("fire_ray/flames"),
            GLOW = VfxParts.add("fire_impact/glow"), EMBER = VfxParts.add("fire_impact/ember");
    /** The ray's flame flipbook: 4×4 frames, a ball of flame burning out to wisps. */
    private static final int COLS = 4, FRAMES = 16;
    /** The most flames and embers one head keeps at once. */
    private static final int MAX_FLAMES = 40, MAX_EMBERS = 24;

    /** A flame licking up (or an ember drifting) from {@code at} along {@code up} at {@code speed} a tick, for {@code life} ticks. */
    private record Lick(Vec3 at, Vec3 up, double speed, float size, float roll, long born, int life) {
        float k(double now) { return (float) Mth.clamp((now - born) / life, 0, 1); }

        Vec3 now(double now) { return at.add(up.scale(speed * (now - born))); }
    }

    private final List<Lick> flames = new ArrayList<>(), embers = new ArrayList<>();

    FireLingeringVeinVisual() {
        super(Element.FIRE, VeinCrystals.Style.of("fire_vein/crystal", 0xFFC93A).withFlow(.9f, .1f, .25f));
    }

    @Override
    protected void thrust(Level level, LiveVein vein, VeinCrystals.Crystal c, RandomSource r) {
        if (!c.big()) return;
        Vec3 foot = c.base().add(c.axis().scale(c.length() * .12f));
        for (int k = 0; k < 3; k++) {
            Vec3 v = c.axis().scale(.04 + r.nextDouble() * .05).add(r.nextGaussian() * .02, r.nextGaussian() * .02, r.nextGaussian() * .02);
            level.addParticle(ParticleTypes.FLAME, foot.x, foot.y, foot.z, v.x, v.y, v.z);
        }
        lick(vein, c, r, ClientCharges.ticks(), 1.4f);
    }

    @Override
    protected void standing(Level level, LiveVein vein, RandomSource r) {
        long now = ClientCharges.ticks();
        flames.removeIf(f -> now - f.born() >= f.life());
        embers.removeIf(e -> now - e.born() >= e.life());
        for (int k = 0; k < 3; k++) {
            VeinCrystals.Crystal c = any(r);
            if (c != null && c.out(now) > .5f && c.dissolved(now) < .3f) lick(vein, c, r, now, 1);
        }
        VeinCrystals.Crystal c = any(r);
        if (c != null && embers.size() < MAX_EMBERS && r.nextInt(2) == 0) {
            Vec3 at = c.base().add(c.axis().scale(c.length() * (.2 + .6 * r.nextDouble())));
            Vec3 up = c.axis().add(r.nextGaussian() * .4, .6, r.nextGaussian() * .4).normalize();
            embers.add(new Lick(at, up, .03 + .03 * r.nextDouble(), .04f + .03f * r.nextFloat(), 0, now, 18 + r.nextInt(14)));
        }
    }

    /** A flame licking up at the foot of crystal {@code c}, {@code scale} its usual size. */
    private void lick(LiveVein vein, VeinCrystals.Crystal c, RandomSource r, long now, float scale) {
        if (flames.size() >= MAX_FLAMES) return;
        Vec3 side = c.x().scale(r.nextGaussian() * c.radius() * 1.5).add(c.y().scale(r.nextGaussian() * c.radius() * 1.5));
        Vec3 at = c.base().add(c.axis().scale(c.length() * .12f)).add(side);
        float size = vein.height() * (.35f + .25f * r.nextFloat()) * scale;
        flames.add(new Lick(at, vein.normal(), .015 + .01 * r.nextDouble(), size, (r.nextFloat() - .5f) * .5f, now, 10 + r.nextInt(8)));
    }

    @Override
    public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, vein, camera, partialTick, pose, out);
        if (crystals.isEmpty()) return;
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : partialTick);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
            for (VeinCrystals.Crystal c : crystals.crystals()) if (c.big()) glow(p, buf, vein, c, now);
        });
        out.submitCustomGeometry(pose, FLAMES_BODY, (p, buf) -> {
            for (Lick f : flames) flame(p, buf, view, f, now, true);
        });
        out.submitCustomGeometry(pose, FLAMES, (p, buf) -> {
            for (Lick f : flames) flame(p, buf, view, f, now, false);
        });
        out.submitCustomGeometry(pose, EMBER, (p, buf) -> {
            for (Lick e : embers) {
                float k = e.k(now);
                float a = VfxParts.smooth(0, .1f, k) * (1 - VfxParts.smooth(.5f, 1, k));
                VfxParts.sprite(p, buf, view, e.now(now), e.size(), 0, VfxParts.argb(a, VfxParts.lerpColor(GOLD, RED, k)));
            }
        });
        pose.popPose();
    }

    /** The ground glowing round the foot of big crystal {@code c}, lying on the surface, as long as it stands. */
    private static void glow(PoseStack.Pose pose, VertexConsumer buf, LiveVein vein, VeinCrystals.Crystal c, double now) {
        float out = Mth.clamp(c.out(now), 0, 1) * (1 - c.dissolved(now));
        if (out <= .02f) return;
        Vec3 n = vein.normal();
        Vec3 foot = c.base().add(c.axis().scale(c.length() * .12f)).add(n.scale(.02));
        Vec3 a = Math.abs(n.y) < .9 ? new Vec3(0, 1, 0).cross(n).normalize() : new Vec3(1, 0, 0);
        Vec3 b = n.cross(a);
        float flicker = .85f + .15f * Mth.sin((float) (now * .4 + c.seed() * 40));
        VfxParts.flat(pose, buf, foot, a, b, c.radius() * 4.5f, c.seed() * 6, VfxParts.argb(.45f * out * flicker, ORANGE));
    }

    /** A flame licking up, burning through the flipbook from a ball to wisps; {@code body} the darker shape under the glow. */
    private static void flame(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Lick f, double now, boolean body) {
        float k = f.k(now);
        int frame = Math.min(FRAMES - 1, (int) (k * FRAMES));
        float u0 = (frame % COLS) / (float) COLS, v0 = (frame / COLS) / (float) COLS, u1 = u0 + 1f / COLS, v1 = v0 + 1f / COLS;
        int rgb = body ? BODY : k < .3f ? VfxParts.lerpColor(GOLD, AMBER, k / .3f) : k < .65f ? VfxParts.lerpColor(AMBER, ORANGE, (k - .3f) / .35f)
                : VfxParts.lerpColor(ORANGE, RED, (k - .65f) / .35f);
        Vec3 at = f.now(now);
        float tall = f.size() * (.55f + .45f * Mth.sqrt(k));
        float a = VfxParts.smooth(0, .12f, k) * (1 - VfxParts.smooth(.55f, 1, k)) * VfxParts.fade(view.cam(), at, .3, .9) * (body ? .45f : .9f);
        VfxParts.sprite(pose, buf, view, at.add(f.up().scale(tall * .3)), tall * .34f, tall * .5f, f.roll(), u0, v0, u1, v1, VfxParts.argb(a, rgb));
    }
}
