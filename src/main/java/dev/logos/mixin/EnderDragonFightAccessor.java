package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.dimension.end.EnderDragonFight;

/** Whether the End's own dragon has been defeated (and not yet brought back). */
@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {
    @Accessor("dragonKilled")
    boolean logos$dragonKilled();
}
