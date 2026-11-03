package dev.logos.runtime;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The effect behind 水上行走: the surface of any fluid (water, lava, a modded fluid, still or
 * flowing, or the water in a waterlogged block) holds its bearer up like ground. They walk on it,
 * and a fall onto it hurts like a fall onto ground, since they never get into the fluid to break
 * it. Sneaking sinks through; a bearer under the surface who is not sneaking floats up to it.
 * A player incarnated as a strider ({@link Incarnation#walksOnLava}) walks on lava the same way.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class WaterWalking extends MobEffect {
    /** How fast a submerged bearer rises, in blocks per tick. */
    public static final double RISE = 0.1;
    /**
     * How far above the fluid's own top its surface sits. Vanilla reckons an entity touching that top
     * (worked out in floats) as inside the fluid, which on lava sets it alight.
     */
    private static final double CLEARANCE = 1e-5;

    public WaterWalking() {
        super(MobEffectCategory.BENEFICIAL, 0x3FA6DC);
    }

    /** {@code shape}, the block's own collision, joined with its fluid's surface when that should hold the colliding entity up. */
    public static VoxelShape withSurface(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, VoxelShape shape) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || context.isDescending() || context.isPlacement()
                || !(context instanceof EntityCollisionContext c) || !(c.getEntity() instanceof LivingEntity walker)
                || !(walker.hasEffect(LogosMod.WATER_WALKING) || fluid.is(FluidTags.LAVA) && Incarnation.walksOnLava(walker))) return shape;
        // Only the top of a column of fluid is a surface.
        if (level.getFluidState(pos.above()).getType().isSame(fluid.getType())) return shape;
        float height = fluid.getOwnHeight();
        if (height <= 0) return shape;
        // The top as vanilla's fluid box has it (Fluid#getAABB adds the height to the block's y in floats).
        float top = pos.getY() + height;
        VoxelShape surface = Shapes.box(0, 0, 0, 1, Math.min(1, (double) top - pos.getY() + CLEARANCE), 1);
        if (!context.isAbove(surface, pos, false)) return shape;
        return shape.isEmpty() ? surface : Shapes.or(shape, surface);
    }

    @SubscribeEvent
    static void onTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LivingEntity walker) || walker.isDescending() || !walker.isInFluidType()
                || !(walker.hasEffect(LogosMod.WATER_WALKING) || walker.isInLava() && Incarnation.walksOnLava(walker))) return;
        Vec3 v = walker.getDeltaMovement();
        if (v.y < RISE) walker.setDeltaMovement(v.x, RISE, v.z);
    }
}
