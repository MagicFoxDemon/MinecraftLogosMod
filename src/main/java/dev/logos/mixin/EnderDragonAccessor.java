package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.pathfinder.Node;

/** The dragon's flight nodes: built once around its fight origin; cleared so they are built again around a master. */
@Mixin(EnderDragon.class)
public interface EnderDragonAccessor {
    @Accessor("nodes")
    Node[] logos$nodes();
}
