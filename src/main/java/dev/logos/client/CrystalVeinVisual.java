package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The shared spiky look of a vein that lingers (see VEIN_VFX.md): crystals ({@link VeinCrystals}) thrust up out of
 * the surface all along where its head crawled, breaking crumbs off it, and stay as long as what the vein left there
 * stays. An element gives the crystals its {@link VeinCrystals.Style} and may add its own accents in
 * {@link #crawling}, {@link #thrust} and {@link #standing}; the default accents are the element's particles at the head
 * and a burst where it stops on something.
 */
public class CrystalVeinVisual implements VeinVisual {
    protected final Element element;
    protected final VeinCrystals crystals;
    private final List<VeinCrystals.Crystal> laid = new ArrayList<>();

    public CrystalVeinVisual(Element element, VeinCrystals.Style style) {
        this.element = element;
        this.crystals = new VeinCrystals(style);
    }

    @Override
    public boolean tick(Level level, LiveVein vein) {
        long now = ClientCharges.ticks();
        RandomSource r = level.getRandom();
        if (!vein.ended()) {
            laid.clear();
            crystals.lay(vein, r, now, laid);
            for (VeinCrystals.Crystal c : laid) {
                if (c.big()) crumb(level, r, c.base().add(c.axis().scale(c.length() * .12f)), vein.normal());
                thrust(level, vein, c, r);
            }
            crawling(level, vein, r);
        } else if (vein.sinceEnd() == 1 && vein.struck()) {
            stopped(level, vein, r);
        }
        crystals.prune(now);
        if (crystals.isEmpty()) return !vein.ended();
        standing(level, vein, r);
        return true;
    }

    /** Each tick the head crawls, after the crystals were laid: the element's particles welling up at it. */
    protected void crawling(Level level, LiveVein vein, RandomSource r) {
        LogosFx.accents(level, element, vein.pos().add(vein.normal().scale(vein.height() / 2)), 1,
                Math.min(vein.width(), vein.height()) / 3, 0.02);
    }

    /** As crystal {@code c} is laid (it thrusts up over the next ticks). Nothing by default. */
    protected void thrust(Level level, LiveVein vein, VeinCrystals.Crystal c, RandomSource r) {}

    /** Once, the tick after the head stopped on something (a body, a wall). */
    protected void stopped(Level level, LiveVein vein, RandomSource r) {
        LogosFx.accents(level, element, vein.pos().add(vein.normal().scale(vein.height() / 2)), 10, vein.width() / 2, 0.06);
    }

    /** Each tick any crystal still stands, crawling or not. Nothing by default. */
    protected void standing(Level level, LiveVein vein, RandomSource r) {}

    /** A crystal still standing, picked at random; null if none. */
    protected VeinCrystals.Crystal any(RandomSource r) {
        int n = crystals.crystals().size();
        if (n == 0) return null;
        int k = r.nextInt(n);
        for (VeinCrystals.Crystal c : crystals.crystals()) if (k-- == 0) return c;
        return null;
    }

    @Override
    public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        double now = ClientCharges.ticks() + (ClientCharges.frozen() ? 0 : partialTick);
        crystals.submit(camera.pos, now, pose, out);
    }

    /** A crumb of the block whose surface (facing {@code n}) is at {@code at}, broken off it. */
    protected static void crumb(Level level, RandomSource r, Vec3 at, Vec3 n) {
        BlockState ground = level.getBlockState(BlockPos.containing(at.subtract(n.scale(0.1))));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        Vec3 p = at.add(n.scale(0.05));
        Vec3 v = n.scale(0.15 + r.nextDouble() * 0.1);
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y, p.z, v.x, v.y, v.z);
    }
}
