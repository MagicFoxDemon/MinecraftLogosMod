package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

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
 * A frost vein that lingers (see VEIN_VFX.md): sharp spikes of ice thrust up all along where it crawled, clear enough
 * to see through, their edges bright with cold light, after the reference the maintainer picked (Piloto's
 * {@code Impale_Frost}). Cold mist creeps low round their feet and points of light glint on them; each big spike
 * throws up a puff of snow as it breaks out.
 */
final class FrostLingeringVeinVisual extends CrystalVeinVisual {
    private static final int MIST_TINT = 0xD8EEFF, GLINT = 0xF2FBFF;
    private static final RenderType MIST = VfxParts.blend("frost_ray/mist"), STAR = VfxParts.add("frost_impact/star");
    /** The most mist puffs and glints one head keeps at once. */
    private static final int MAX_MIST = 24, MAX_GLINTS = 12;

    /** A puff of mist (or a glint) at {@code at}, drifting {@code drift} a tick, for {@code life} ticks. */
    private record Puff(Vec3 at, Vec3 drift, float size, float roll, long born, int life) {
        float k(double now) { return (float) Mth.clamp((now - born) / life, 0, 1); }

        Vec3 now(double now) { return at.add(drift.scale(now - born)); }
    }

    private final List<Puff> mist = new ArrayList<>(), glints = new ArrayList<>();

    FrostLingeringVeinVisual() {
        super(Element.FROST, VeinCrystals.Style.of("frost_vein/crystal", 0xD6F4FF).withTint(0xE8F6FF).withClear(.62f)
                .withFlow(.7f, 0, -.03f).withShape(5, .55f, .1f, 25).withSize(.85f, 1.15f));
    }

    @Override
    protected void thrust(Level level, LiveVein vein, VeinCrystals.Crystal c, RandomSource r) {
        if (!c.big()) return;
        Vec3 foot = c.base().add(c.axis().scale(c.length() * .12f));
        for (int k = 0; k < 2; k++) {
            Vec3 v = c.axis().scale(.05 + r.nextDouble() * .05).add(r.nextGaussian() * .03, r.nextGaussian() * .03, r.nextGaussian() * .03);
            level.addParticle(ParticleTypes.SNOWFLAKE, foot.x, foot.y, foot.z, v.x, v.y, v.z);
        }
        puff(vein, c, r, ClientCharges.ticks());
    }

    @Override
    protected void standing(Level level, LiveVein vein, RandomSource r) {
        long now = ClientCharges.ticks();
        mist.removeIf(m -> now - m.born() >= m.life());
        glints.removeIf(g -> now - g.born() >= g.life());
        VeinCrystals.Crystal c = any(r);
        if (c != null && c.dissolved(now) < .3f && r.nextInt(2) == 0) puff(vein, c, r, now);
        c = any(r);
        if (c != null && c.big() && glints.size() < MAX_GLINTS && c.out(now) > .9f && r.nextInt(3) == 0) {
            Vec3 at = c.base().add(c.axis().scale(c.length() * (.4 + .5 * r.nextDouble())))
                    .add(c.x().scale(c.radius() * .7 * (r.nextBoolean() ? 1 : -1)));
            glints.add(new Puff(at, Vec3.ZERO, .12f + .08f * r.nextFloat(), r.nextFloat() * 6, now, 8 + r.nextInt(6)));
        }
    }

    /** A puff of cold mist creeping out low round the foot of crystal {@code c}. */
    private void puff(LiveVein vein, VeinCrystals.Crystal c, RandomSource r, long now) {
        if (mist.size() >= MAX_MIST) return;
        Vec3 n = vein.normal();
        Vec3 out = c.x().scale(r.nextGaussian()).add(c.y().scale(r.nextGaussian()));
        out = out.subtract(n.scale(out.dot(n)));
        out = out.lengthSqr() < 1e-6 ? Vec3.ZERO : out.normalize();
        Vec3 at = c.base().add(c.axis().scale(c.length() * .12f)).add(n.scale(.12));
        mist.add(new Puff(at, out.scale(.012).add(n.scale(.004)), vein.width() * (.35f + .2f * r.nextFloat()), r.nextFloat() * 6,
                now, 30 + r.nextInt(20)));
    }

    @Override
    public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, vein, camera, partialTick, pose, out);
        if (crystals.isEmpty()) return;
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : partialTick);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(pose, MIST, (p, buf) -> {
            for (Puff m : mist) {
                float k = m.k(now);
                Vec3 at = m.now(now);
                float a = VfxParts.smooth(0, .25f, k) * (1 - VfxParts.smooth(.5f, 1, k)) * VfxParts.fade(view.cam(), at, .5, 1.5) * .3f;
                VfxParts.sprite(p, buf, view, at, m.size() * (.7f + .5f * k), m.roll() + k * .6f, VfxParts.argb(a, MIST_TINT));
            }
        });
        out.submitCustomGeometry(pose, STAR, (p, buf) -> {
            for (Puff g : glints) {
                float k = g.k(now);
                float a = (float) Math.sin(Math.PI * k);
                VfxParts.sprite(p, buf, view, g.now(now), g.size() * (.6f + .4f * a), g.roll() + k, VfxParts.argb(a * a, GLINT));
            }
        });
        pose.popPose();
    }
}
