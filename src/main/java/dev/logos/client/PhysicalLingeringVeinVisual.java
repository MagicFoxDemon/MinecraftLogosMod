package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A physical vein that lingers (see VEIN_VFX.md): tall, needle-sharp spikes driven up all along where it crawled,
 * the fire and frost crystals' shape drawn out to a point, after the references the maintainer picked (the ground
 * attack pack's {@code AberrateObelisk} and {@code DashMagma}), crowded close. The shared crystal look in the physical
 * element's pale gold, their edges lit paler still. Each big spike throws up a puff of dust as it breaks out.
 */
final class PhysicalLingeringVeinVisual extends CrystalVeinVisual {
    private static final RenderType DUST = VfxParts.blend("physical_ray/smoke");
    /** The most dust puffs one head keeps at once. */
    private static final int MAX_DUST = 20;

    /** A puff of dust at {@code at}, drifting {@code drift} a tick, for {@code life} ticks. */
    private record Puff(Vec3 at, Vec3 drift, float size, float roll, int tint, long born, int life) {
        float k(double now) { return (float) Mth.clamp((now - born) / life, 0, 1); }
    }

    private final List<Puff> dust = new ArrayList<>();

    PhysicalLingeringVeinVisual() {
        super(Element.PHYSICAL, VeinCrystals.Style.of("ward/noise", 0xFFF4D6).withTint(0xF3D9A0).withContrast(.3f)
                .withShape(4, .62f, .12f, 20).withSize(.8f, 1.35f).withCount(2.4f, 5.5f));
    }

    @Override
    protected void thrust(Level level, LiveVein vein, VeinCrystals.Crystal c, RandomSource r) {
        if (!c.big() || dust.size() >= MAX_DUST) return;
        long now = ClientCharges.ticks();
        Vec3 n = vein.normal();
        Vec3 foot = c.base().add(c.axis().scale(c.length() * .12f)).add(n.scale(.15));
        Vec3 out = c.x().scale(r.nextGaussian()).add(c.y().scale(r.nextGaussian()));
        out = out.subtract(n.scale(out.dot(n)));
        out = out.lengthSqr() < 1e-6 ? Vec3.ZERO : out.normalize();
        dust.add(new Puff(foot, out.scale(.02).add(n.scale(.015)), vein.width() * (.35f + .2f * r.nextFloat()), r.nextFloat() * 6,
                VfxParts.groundTint(level, foot), now, 16 + r.nextInt(10)));
    }

    @Override
    protected void standing(Level level, LiveVein vein, RandomSource r) {
        long now = ClientCharges.ticks();
        dust.removeIf(d -> now - d.born() >= d.life());
    }

    @Override
    public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, vein, camera, partialTick, pose, out);
        if (dust.isEmpty()) return;
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : partialTick);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(pose, DUST, (p, buf) -> {
            for (Puff d : dust) {
                float k = d.k(now);
                if (k >= 1) continue;
                Vec3 at = d.at().add(d.drift().scale(now - d.born()));
                float a = VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.35f, 1, k)) * VfxParts.fade(view.cam(), at, .5, 1.5) * .45f;
                VfxParts.sprite(p, buf, view, at, d.size() * (.6f + .8f * k), d.roll() + k * .5f, VfxParts.argb(a, d.tint()));
            }
        });
        pose.popPose();
    }
}
