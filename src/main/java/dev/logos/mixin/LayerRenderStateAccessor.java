package dev.logos.mixin;

import java.util.List;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** One render layer's display transform (from the model's {@code display}) and local transform, and the faces a surface aura is drawn along. */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
    @Accessor("itemTransform")
    ItemTransform logos$itemTransform();

    @Accessor("localTransform")
    Matrix4f logos$localTransform();

    @Accessor("quads")
    List<BakedQuad> logos$quads();

    @Accessor("specialRenderer")
    @Nullable SpecialModelRenderer<Object> logos$special();
}
