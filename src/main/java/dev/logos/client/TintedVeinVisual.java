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
        if (vein.ended()) {
            if (vein.sinceEnd() == 1 && vein.struck()) {
                LogosFx.accents(level, element, vein.pos().add(0, vein.height() / 2, 0), 10, vein.width() / 2, 0.06);
            }
            return false;
        }
        RandomSource r = level.getRandom();
        Vec3 from = vein.prev(), to = vein.pos();
        Vec3 side = new Vec3(-vein.dir().z, 0, vein.dir().x);
        int motes = Math.max(1, (int) Math.ceil(from.distanceTo(to) / SPACING));
        for (int k = 0; k < motes; k++) {
            Vec3 at = from.lerp(to, (k + r.nextDouble()) / motes).add(side.scale((r.nextDouble() - 0.5) * vein.width()));
            LogosFx.accents(level, element, at.add(0, 0.08, 0), 1, 0.03, 0.01);
            if (k % 2 == 0) crumb(level, r, at);
        }
        LogosFx.accents(level, element, to.add(0, vein.height() / 2, 0), 2, Math.min(vein.width(), vein.height()) / 3, 0.02);
        return true;
    }

    /** A crumb of the ground at {@code at}, kicked up. */
    private static void crumb(Level level, RandomSource r, Vec3 at) {
        BlockState ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.1, at.z));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.05, at.z,
                (r.nextDouble() - 0.5) * 0.1, 0.1 + r.nextDouble() * 0.1, (r.nextDouble() - 0.5) * 0.1);
    }
}
