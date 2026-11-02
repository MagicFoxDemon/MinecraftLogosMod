package dev.logos.client;

import java.util.ArrayDeque;

import dev.logos.lang.Element;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The shared look of a vein that lingers: where its head has crawled the ground stays broken open, and short columns of
 * the element's particles keep standing up out of it all along the way, thinning as each stretch nears its end, until
 * the last of it has faded. Its head looks as {@link TintedVeinVisual}'s does, and bursts where it stops on something.
 */
final class TintedLingeringVeinVisual implements VeinVisual {
    /** One mark for every so many blocks crawled. */
    private static final double SPACING = 0.35;
    /** The most columns all its marks stand up together in a tick, however long the way. */
    private static final int COLUMNS = 6;

    /** A place it crawled over, out of the surface {@code normal}, kept until tick {@code until}. */
    private record Mark(Vec3 at, Vec3 normal, long laid, long until) {}

    private final Element element;
    private final ArrayDeque<Mark> marks = new ArrayDeque<>();
    private double carried;

    TintedLingeringVeinVisual(Element element) {
        this.element = element;
    }

    @Override
    public boolean tick(Level level, LiveVein vein) {
        long now = ClientCharges.ticks();
        RandomSource r = level.getRandom();
        Vec3 n = vein.normal();
        if (!vein.ended()) {
            Vec3 from = vein.prev(), to = vein.pos();
            double length = from.distanceTo(to);
            Vec3 side = vein.dir().cross(n);
            side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
            // Lays a mark every SPACING crawled, carrying what is left over to the next tick.
            for (double s = SPACING - carried; s <= length; s += SPACING) {
                Vec3 at = from.lerp(to, length < 1e-9 ? 1 : s / length).add(side.scale((r.nextDouble() - 0.5) * vein.width()));
                marks.addLast(new Mark(at, n, now, now + Math.max(1, vein.linger())));
                crumb(level, r, at, n);
            }
            carried = (carried + length) % SPACING;
            LogosFx.accents(level, element, to.add(n.scale(vein.height() / 2)), 2, Math.min(vein.width(), vein.height()) / 3, 0.02);
        } else if (vein.sinceEnd() == 1 && vein.struck()) {
            LogosFx.accents(level, element, vein.pos().add(n.scale(vein.height() / 2)), 10, vein.width() / 2, 0.06);
        }
        while (!marks.isEmpty() && marks.peekFirst().until() <= now) marks.pollFirst();
        if (marks.isEmpty()) return false;
        Mark[] all = marks.toArray(Mark[]::new);
        for (int k = 0; k < Math.min(COLUMNS, all.length); k++) {
            Mark m = all[r.nextInt(all.length)];
            // Fewer columns from a stretch the nearer it is to fading.
            double left = (double) (m.until() - now) / Math.max(1, m.until() - m.laid());
            if (r.nextDouble() > left) continue;
            double tall = vein.height() * (0.4 + 0.6 * r.nextDouble());
            for (int up = 0; up < 3; up++) {
                LogosFx.accents(level, element, m.at().add(m.normal().scale(tall * (up + r.nextDouble()) / 3)), 1, 0.03, 0.01);
            }
        }
        return true;
    }

    /** A crumb of the block whose surface (facing {@code n}) is at {@code at}, broken off it. */
    private static void crumb(Level level, RandomSource r, Vec3 at, Vec3 n) {
        BlockState ground = level.getBlockState(BlockPos.containing(at.subtract(n.scale(0.1))));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        Vec3 p = at.add(n.scale(0.05));
        Vec3 v = n.scale(0.15 + r.nextDouble() * 0.1);
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.x, p.y, p.z, v.x, v.y, v.z);
    }
}
