package dev.logos.client;

import dev.logos.lang.Element;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The shared vein look every element starts on: the element's particles laid along the ground where it crawled and
 * welling up at its head, with crumbs of the ground it breaks through, and a burst where it stops on something.
 */
final class TintedVeinVisual implements VeinVisual {
    /** One mote for every so many blocks crawled. */
    private static final double SPACING = 0.35;

    private final Element element;

    TintedVeinVisual(Element element) {
        this.element = element;
    }

    @Override
    public boolean tick(Level level, LiveVein vein) {
        Vec3 n = vein.normal();
        if (vein.ended()) {
            if (vein.sinceEnd() == 1 && vein.struck()) {
                LogosFx.accents(level, element, vein.pos().add(n.scale(vein.height() / 2)), 10, vein.width() / 2, 0.06);
            }
            return false;
        }
        RandomSource r = level.getRandom();
        Vec3 from = vein.prev(), to = vein.pos();
        // Across the surface it is on, square to its heading.
        Vec3 side = vein.dir().cross(n);
        if (side.lengthSqr() < 1e-6) side = new Vec3(-vein.dir().z, 0, vein.dir().x);
        side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
        int motes = Math.max(1, (int) Math.ceil(from.distanceTo(to) / SPACING));
        for (int k = 0; k < motes; k++) {
            Vec3 at = from.lerp(to, (k + r.nextDouble()) / motes).add(side.scale((r.nextDouble() - 0.5) * vein.width()));
            LogosFx.accents(level, element, at.add(n.scale(0.08)), 1, 0.03, 0.01);
            if (k % 2 == 0) crumb(level, r, at, n);
        }
        LogosFx.accents(level, element, to.add(n.scale(vein.height() / 2)), 2, Math.min(vein.width(), vein.height()) / 3, 0.02);
        return true;
    }

    /** A crumb of the block whose surface (facing {@code n}) is at {@code at}, kicked off it. */
    private static void crumb(Level level, RandomSource r, Vec3 at, Vec3 n) {
        BlockState ground = level.getBlockState(BlockPos.containing(at.subtract(n.scale(0.1))));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        Vec3 p = at.add(n.scale(0.05));
        // Off the surface, scattered a little along it.
        Vec3 jitter = new Vec3((r.nextDouble() - 0.5) * 0.1, (r.nextDouble() - 0.5) * 0.1, (r.nextDouble() - 0.5) * 0.1);
        Vec3 v = n.scale(0.1 + r.nextDouble() * 0.1).add(jitter.subtract(n.scale(jitter.dot(n))));
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y, p.z, v.x, v.y, v.z);
    }
}
