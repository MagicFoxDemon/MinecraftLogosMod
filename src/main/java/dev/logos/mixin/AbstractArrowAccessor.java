package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/** An arrow's base damage, which vanilla only writes: a construct takes the hit the arrow would have dealt. */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Accessor("baseDamage")
    double logos$baseDamage();
}
