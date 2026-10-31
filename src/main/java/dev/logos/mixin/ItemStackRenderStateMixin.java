package dev.logos.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.SurfaceAuras;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;

/** An item's render state remembers the surface aura its item wears and who holds it, and draws the aura along with the item. */
@Mixin(ItemStackRenderState.class)
abstract class ItemStackRenderStateMixin implements SurfaceAuras.Carrier {
    @Shadow ItemDisplayContext displayContext;
    @Unique private @Nullable SurfaceAura logos$aura;
    @Unique private int logos$owner = -1;

    @Override public void logos$aura(@Nullable SurfaceAura aura, int owner) {
        logos$aura = aura;
        logos$owner = owner;
    }

    @Override public @Nullable SurfaceAura logos$aura() { return logos$aura; }

    @Override public int logos$owner() { return logos$owner; }

    @Override public ItemDisplayContext logos$context() { return displayContext; }

    @Inject(method = "clear", at = @At("HEAD"))
    private void logos$clear(CallbackInfo ci) {
        logos$aura = null;
        logos$owner = -1;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void logos$submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline, CallbackInfo ci) {
        if (logos$aura != null) SurfaceAuras.submitItem(this, poseStack, collector);
    }
}
