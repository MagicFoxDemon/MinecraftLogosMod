package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/** How many pyramid layers a beacon stands on, which vanilla keeps to itself: sublimation needs a lit beacon. */
@Mixin(BeaconBlockEntity.class)
public interface BeaconAccessor {
    @Accessor("levels")
    int logos$levels();
}
