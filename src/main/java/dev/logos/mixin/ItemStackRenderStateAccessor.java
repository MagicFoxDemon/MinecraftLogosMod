package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.item.ItemStackRenderState;

/** An item's resolved render layers, so the wand tip can follow the same display transform the item is drawn with. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] logos$layers();

    @Accessor("activeLayerCount")
    int logos$activeLayerCount();
}
