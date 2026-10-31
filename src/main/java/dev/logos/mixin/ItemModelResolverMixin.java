package dev.logos.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.logos.client.SurfaceAuras;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Every item drawn (in hand, on the ground, in a frame) resolves its model here: notes whether it wears a surface aura. */
@Mixin(ItemModelResolver.class)
abstract class ItemModelResolverMixin {
    @Inject(method = "updateForTopItem", at = @At("TAIL"))
    private void logos$aura(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext, @Nullable Level level,
            @Nullable ItemOwner owner, int seed, CallbackInfo ci) {
        SurfaceAuras.resolved(output, item, displayContext, owner);
    }
}
