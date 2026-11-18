package dev.logos.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.LogosMod;
import dev.logos.item.AlchemyTableItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * The alchemy table item, drawn from the block's own model as a freshly placed table looks: no
 * mana left in the hourglass, an empty tank and no flame. Centred on the seam between the halves.
 */
public class AlchemyTableItemRenderer extends GeoItemRenderer<AlchemyTableItem> {
    private static final String[] HIDDEN = {"mana_sand_upper_0", "mana_sand_upper_1", "mana_sand_upper_2", "mana_sand_upper_3",
            "water_tank_fill", "flame_outer"};
    /** Modelled at twice its size, as for the block. */
    private static final float SCALE = 0.5f;
    /** Half the table's height in blocks, so the item turns about its middle. */
    private static final float HALF_HEIGHT = 23.5f / 2 / 16;

    public AlchemyTableItemRenderer() {
        super(new DefaultedBlockGeoModel<>(ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "alchemy_table")));
        withScale(SCALE);
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public void preRender(PoseStack poseStack, AlchemyTableItem item, BakedGeoModel model, MultiBufferSource buffers, VertexConsumer buffer,
            boolean isReRender, float partialTick, int light, int overlay, int colour) {
        super.preRender(poseStack, item, model, buffers, buffer, isReRender, partialTick, light, overlay, colour);
        if (isReRender) return;
        // As on main: GeckoLib 4 and 5 both scale first and then move to (0.5, 0.51, 0.5) of the scaled model, so only
        // the drop by half the table is ours.
        poseStack.translate(0, -HALF_HEIGHT / SCALE, 0);
    }

    /** 26.1 lights it as a 3D item in the inventory (its model lights like a block); GeckoLib 4 would light it flat. */
    @Override
    public void setupLightingForGuiRender() {
        Lighting.setupFor3DItems();
    }

    /** The block renderer shares these bones and leaves them as its table was; set every one this look depends on. */
    @Override
    public void renderRecursively(PoseStack poseStack, AlchemyTableItem item, GeoBone bone, RenderType renderType, MultiBufferSource buffers,
            VertexConsumer buffer, boolean isReRender, float partialTick, int light, int overlay, int colour) {
        if (AlchemyTableRenderer.indexOf(HIDDEN, bone.getName()) >= 0) bone.setHidden(true);
        else if (AlchemyTableRenderer.indexOf(AlchemyTableRenderer.LOWER, bone.getName()) >= 0) {
            bone.setHidden(false);
            bone.setScaleY(1);
        }
        super.renderRecursively(poseStack, item, bone, renderType, buffers, buffer, isReRender, partialTick, light, overlay, colour);
    }

    /** Glass is see-through. */
    @Override
    public RenderType getRenderType(AlchemyTableItem item, ResourceLocation texture, MultiBufferSource buffers, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }
}
