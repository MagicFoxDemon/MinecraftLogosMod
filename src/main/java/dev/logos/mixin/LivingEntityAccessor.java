package dev.logos.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a living entity is set to jump: 26.1 asks a player with {@code isJumping()}, which 1.21.1 lacks. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("jumping")
    boolean logos$isJumping();
}
