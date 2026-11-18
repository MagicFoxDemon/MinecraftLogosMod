package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * What an item is drawn with on 1.21.1, as ItemRenderer draws it: each render pass of its resolved model, with its
 * quads and render types. Stands in, for the scenes, for 26.1's item render state layers.
 */
record ItemPasses(List<Pass> passes) {
    record Pass(List<BakedQuad> quads, List<RenderType> types) {
        /** Whether it draws translucent (its types blend), as 26.1's layers report. */
        boolean blended() {
            return types.contains(RenderType.translucent());
        }

        boolean opaque() {
            return types.stream().anyMatch(t -> t != RenderType.translucent());
        }
    }

    static ItemPasses of(ItemStack stack, @javax.annotation.Nullable LivingEntity holder) {
        var mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, holder, 0);
        RandomSource random = RandomSource.create();
        List<Pass> out = new ArrayList<>();
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            List<BakedQuad> quads = new ArrayList<>();
            for (Direction d : Direction.values()) {
                random.setSeed(42);
                quads.addAll(pass.getQuads(null, d, random));
            }
            random.setSeed(42);
            quads.addAll(pass.getQuads(null, null, random));
            out.add(new Pass(quads, List.copyOf(pass.getRenderTypes(stack, true))));
        }
        return new ItemPasses(out);
    }
}
